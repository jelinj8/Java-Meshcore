package cz.bliksoft.meshcore.companion;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.javautils.ble.BleAdapter;
import cz.bliksoft.javautils.ble.BleException;
import cz.bliksoft.javautils.ble.BlePeripheral;
import cz.bliksoft.javautils.ble.ConnectionParameterPreset;
import cz.bliksoft.javautils.ble.ConnectionParameters;
import cz.bliksoft.javautils.ble.ScanFilter;
import cz.bliksoft.javautils.ble.utils.BleUtils;

/**
 * BLE transport for MeshcoreCompanion using the Nordic UART Service (NUS). Uses
 * cz.bliksoft.java:common-java-utils-ble (BSToolbox-BLE) for cross-platform BLE
 * support.
 *
 * BSToolbox-BLE drives BLE through a bundled Rust sidecar process rather than
 * an in-process native binding, so a native-side BLE fault (which the previous
 * SimpleBLE/simplejavable-based implementation could hit on connect) surfaces
 * as an IOException here instead of crashing the JVM. Every reconnect attempt
 * starts a fresh sidecar process, so a crashed sidecar is simply retried like
 * any other disconnect.
 *
 * NUS UUIDs are exposed as public constants so callers can override if the
 * firmware uses a different BLE profile. Confirmed against the firmware's own
 * BLE companion protocol docs (meshcore-dev/MeshCore wiki, "Companion Radio
 * Protocol" / BLE section): companion_radio does use the standard NUS UUIDs
 * below, with matching read/write directions to what's implemented here (UUIDs
 * are named from the phone's perspective in this class, from the firmware's
 * perspective in the firmware docs — same characteristics, opposite-facing
 * names).
 *
 * That same source confirms the firmware <b>requires OS-level pairing/bonding
 * with MITM protection</b> (static PIN, typically {@code 123456}) before the
 * connection can be used — this isn't a library limitation, it's how the
 * firmware's GATT security is configured on both ESP32 and NRF52 builds. Pair
 * the device via the OS's own Bluetooth settings first; neither this library
 * nor the underlying btleplug sidecar can drive that pairing flow itself.
 *
 * Unlike the USB/serial transport, BLE frames carry <b>no
 * {@code '&lt;'}/{@code '&gt;'} + u16le length header</b> — per the firmware's
 * own docs ("for BLE, a frame is simply a single characteristic value; the BLE
 * link layer already does all the integrity checks"), each GATT
 * write/notification value *is* one complete frame. The original
 * SimpleBLE-based implementation (and this class, until verified against a live
 * device) wrongly reused the serial framing here; {@link #sendBinaryFrame} and
 * {@link #getBinaryFrame} do not add or expect that header.
 */
public class BleMeshcoreCompanion extends MeshcoreCompanion {
	private static final Logger log = Logger.getLogger(BleMeshcoreCompanion.class.getName());

	/**
	 * Nordic UART Service (NUS) — base service UUID; standard serial-over-BLE
	 * profile.
	 */
	public static final String NUS_SERVICE = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
	/** NUS TX characteristic — phone writes to device (Write Without Response). */
	public static final String NUS_TX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
	/** NUS RX characteristic — device sends notifications to the phone. */
	public static final String NUS_RX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

	private static final String PAIRING_HINT = " — if this device requires bonding, pair it via "
			+ "your OS's Bluetooth settings first (MeshCore's default BLE PIN is 123456)";

	/** Scan duration used when connecting (milliseconds). */
	private static final int SCAN_TIMEOUT_MS = 5000;

	private final String deviceAddress;
	private volatile BleAdapter adapter;
	private volatile BlePeripheral peripheral;

	// Set only by the BleAdapter-accepting constructor, and consumed (nulled) the
	// first time connectBle() runs - see that constructor's doc.
	private volatile BleAdapter initialAdapter;

	// Each queued entry is already one complete frame - see the class-level note on
	// BLE framing.
	private final LinkedBlockingQueue<byte[]> rxChunks = new LinkedBlockingQueue<>();

	/**
	 * Creates a companion that connects to a MeshCore radio over BLE and
	 * immediately starts the reader loop.
	 *
	 * @param name          companion name (for logging/identity)
	 * @param deviceAddress BLE MAC address of the MeshCore radio (e.g.
	 *                      "AA:BB:CC:DD:EE:FF")
	 */
	public BleMeshcoreCompanion(String name, String deviceAddress) {
		super(name);
		this.deviceAddress = Objects.requireNonNull(deviceAddress, "deviceAddress");
		startLoop();
	}

	/**
	 * Creates a companion that connects to a MeshCore radio over BLE and
	 * immediately starts the reader loop, reusing {@code adapter} for the very
	 * first connect attempt instead of opening a new sidecar process and
	 * re-scanning for an address the caller already found - e.g. right after an
	 * interactive discovery scan on that same adapter (see
	 * {@link #scanForNusDevices(BleAdapter, int)}). {@code adapter} must have
	 * already discovered {@code deviceAddress} via a scan on itself - a peripheral
	 * is only connectable on the adapter that scanned for it (see
	 * {@link BleAdapter}'s own class doc). Ownership of {@code adapter} transfers
	 * to this companion, which closes it like any other adapter it creates.
	 *
	 * <p>
	 * Only the first connect attempt reuses {@code adapter} this way - if the
	 * connection later drops, subsequent automatic reconnects fall back to opening
	 * a fresh sidecar process and scanning again, exactly like
	 * {@link #BleMeshcoreCompanion(String, String)} always does.
	 *
	 * @param name          companion name (for logging/identity)
	 * @param deviceAddress BLE MAC address of the MeshCore radio, already
	 *                      discovered via a scan on {@code adapter}
	 * @param adapter       an adapter that has already scanned for
	 *                      {@code deviceAddress}; ownership transfers here
	 */
	public BleMeshcoreCompanion(String name, String deviceAddress, BleAdapter adapter) {
		super(name);
		this.deviceAddress = Objects.requireNonNull(deviceAddress, "deviceAddress");
		this.initialAdapter = Objects.requireNonNull(adapter, "adapter");
		startLoop();
	}

	// ─── Transport abstract methods ───────────────────────────────────────────

	@Override
	public boolean isConnected() {
		BlePeripheral p = peripheral;
		return p != null && p.isConnected();
	}

	@Override
	void checkConnection() throws IOException {
		if (!isConnected())
			throw new IOException("BLE not connected to " + deviceAddress);
	}

	@Override
	protected synchronized void sendBinaryFrame(byte[] payload) throws IOException {
		checkConnection();
		BlePeripheral p = peripheral;
		// One GATT write == one complete frame (no serial-style header) - see class
		// Javadoc. The
		// TX characteristic only declares WRITE (Write Request), not
		// WRITE_WITHOUT_RESPONSE -
		// verified against a live device - so a plain "write without response" here
		// would be
		// silently dropped by the BLE stack instead of erroring.
		try {
			p.writeCharacteristic(NUS_SERVICE, NUS_TX, payload, true);
		} catch (BleException e) {
			throw new IOException("BLE write failed", e);
		}
	}

	@Override
	protected byte[] getBinaryFrame() throws IOException {
		while (true) {
			byte[] frame;
			try {
				frame = rxChunks.poll(1, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			if (frame != null)
				return frame;
			if (!isConnected() || terminate)
				return null;
		}
	}

	// ─── Connection lifecycle ─────────────────────────────────────────────────

	/** Starts the BLE reader thread with exponential back-off reconnection. */
	protected void startLoop() {
		this.readerThread = new Thread(() -> {
			long backoffMs = 500;
			while (!terminate) {
				try {
					connectBle();
					backoffMs = 500;
					onDeviceConnected();
					runLoop();
					throw new EOFException("BLE disconnected");
				} catch (Exception e) {
					if (terminate)
						return;
					onDeviceDisconnected(e);
					disconnectBle();
					try {
						Thread.sleep(backoffMs);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return;
					}
					backoffMs = Math.min(10_000, backoffMs * 2);
				}
			}
		}, "MeshcoreBleReader");
		this.readerThread.setDaemon(true);
		this.readerThread.start();
	}

	private void connectBle() throws IOException {
		rxChunks.clear();

		// A caller-supplied, already-scanned adapter (see the BleAdapter-accepting
		// constructor) is only ever used for this very first connect attempt - null it
		// out immediately so a later reconnect (after a disconnect) falls back to the
		// normal fresh-sidecar-plus-scan path below, exactly like the plain
		// (name, address) constructor always does.
		BleAdapter newAdapter = initialAdapter;
		initialAdapter = null;
		boolean alreadyScanned = newAdapter != null;

		if (newAdapter == null) {
			// Every reconnect attempt gets a fresh sidecar process, so a crashed sidecar
			// from a
			// previous attempt is simply retried like any other disconnect — no restart
			// bookkeeping
			// needed here.
			try {
				newAdapter = new BleAdapter();
			} catch (BleException e) {
				throw new IOException("Failed to start BLE sidecar", e);
			}
		}
		this.adapter = newAdapter;

		if (!alreadyScanned) {
			// A scan is required before connect() - some BSToolbox-BLE backends only learn
			// about a
			// peripheral (even an already-bonded one) through an active scan, and reject
			// connect()
			// against an address they've never scanned. We already know the exact address
			// we're
			// reconnecting to, so ScanFilter.withAddress() makes the adapter stop the scan
			// as soon
			// as that address is seen instead of always burning the full SCAN_TIMEOUT_MS -
			// a missed
			// advertisement here just falls through to the timeout, it isn't treated as
			// proof the
			// device is unreachable; connect() itself reports the real error if it truly
			// can't
			// connect.
			try {
				BleUtils.scan(newAdapter, new ScanFilter().withServiceUuid(NUS_SERVICE).withAddress(deviceAddress),
						SCAN_TIMEOUT_MS);
			} catch (BleException e) {
				throw new IOException("BLE scan failed", e);
			}
		}

		BlePeripheral p = newAdapter.getPeripheral(deviceAddress);
		p.setDisconnectListener(reason -> log.info(String.format("BLE %s reported disconnect from %s: %s",
				"sidecar_crashed".equals(reason) ? "sidecar" : "peripheral", deviceAddress, reason)));

		try {
			p.connect();
			// On Windows, subscribe() goes through win_gatt.rs, which - unlike the
			// Linux/macOS path - has no internal retry loop to implicitly (re)discover
			// services if they aren't cached yet right after connecting; without this
			// explicit call, subscribe() below can fail on a freshly (re)connected
			// peripheral until something else happens to warm the OS's GATT cache first.
			p.discoverServices();
		} catch (BleException e) {
			throw new IOException("BLE connect failed to " + deviceAddress + PAIRING_HINT, e);
		}

		try {
			p.subscribe(NUS_SERVICE, NUS_RX, (charUuid, data) -> rxChunks.add(data));
		} catch (BleException e) {
			try {
				p.disconnect();
			} catch (BleException ignore) {
			}
			throw new IOException("Failed to subscribe to NUS notifications" + PAIRING_HINT, e);
		}

		this.peripheral = p;
	}

	private void disconnectBle() {
		BlePeripheral p = peripheral;
		BleAdapter a = adapter;
		peripheral = null;
		adapter = null;
		if (p != null) {
			try {
				p.disconnect();
			} catch (Exception ignore) {
			}
		}
		if (a != null) {
			a.close();
		}
	}

	@Override
	protected void onDeviceConnected() {
		super.onDeviceConnected();
		log.info(String.format("BLE connected to %s", deviceAddress));
		logConnectionDiagnostics();
	}

	/**
	 * Logs adapter/connection-quality diagnostics at INFO once per connect -
	 * backend support for these varies by platform (confirmed on Windows; see
	 * {@link BlePeripheral}'s own docs), so failures here are expected on some
	 * platforms and logged at FINE rather than surfaced.
	 */
	private void logConnectionDiagnostics() {
		BleAdapter a = adapter;
		BlePeripheral p = peripheral;
		if (a == null || p == null)
			return;
		try {
			log.info("BLE adapter state: " + a.getAdapterState());
		} catch (BleException e) {
			log.log(Level.FINE, "adapter state query failed/unsupported", e);
		}
		try {
			log.info("BLE MTU: " + p.getMtu() + " bytes");
		} catch (BleException e) {
			log.log(Level.FINE, "MTU query failed/unsupported", e);
		}
		try {
			log.info("BLE RSSI: " + p.readRssi() + " dBm");
		} catch (BleException e) {
			log.log(Level.FINE, "RSSI query failed/unsupported", e);
		}
		try {
			ConnectionParameters params = p.getConnectionParameters();
			log.info("BLE connection parameters: " + (params != null ? params : "not exposed on this platform"));
		} catch (BleException e) {
			log.log(Level.FINE, "connection parameters query failed/unsupported", e);
		}
	}

	/**
	 * Requests a connection-parameter preset from the OS - e.g.
	 * {@link ConnectionParameterPreset#THROUGHPUT_OPTIMIZED} before a bulk message
	 * sync, switched back to {@link ConnectionParameterPreset#BALANCED} afterward.
	 * Best-effort and advisory (the radio may accept or reject it - read
	 * {@link #getConnectionParameters()} afterward to see what actually took
	 * effect); silently no-ops rather than throwing if unsupported on this platform
	 * or not currently connected, so callers don't need to special-case that.
	 */
	public void requestConnectionParameters(ConnectionParameterPreset preset) {
		BlePeripheral p = peripheral;
		if (p == null)
			return;
		try {
			p.requestConnectionParameters(preset);
		} catch (BleException e) {
			log.log(Level.FINE, "connection parameter request failed/unsupported", e);
		}
	}

	/**
	 * Current BLE connection parameters as reported by the OS, or {@code null} if
	 * unsupported on this platform or not currently connected.
	 */
	public ConnectionParameters getConnectionParameters() {
		BlePeripheral p = peripheral;
		if (p == null)
			return null;
		try {
			return p.getConnectionParameters();
		} catch (BleException e) {
			return null;
		}
	}

	@Override
	protected void onDeviceDisconnected(Exception cause) {
		super.onDeviceDisconnected(cause);
		if (cause instanceof IOException)
			// Attach cause (not just its message) so the wrapped BleException's own detail
			// - e.g. the actual native error behind "Failed to subscribe..." - isn't lost.
			log.log(Level.WARNING, String.format("BLE disconnected from %s: %s", deviceAddress, cause), cause);
		else
			log.log(Level.SEVERE, "BLE communication error", cause);
	}

	@Override
	public void close() {
		super.close();
		disconnectBle();
	}

	// ─── Utility: scanning ────────────────────────────────────────────────────

	/**
	 * Scan for BLE devices advertising {@link #NUS_SERVICE} and return a list of
	 * "address (name)" strings, filtering out unrelated nearby BLE devices. Use
	 * this to discover the address to pass to the constructor.
	 *
	 * <p>
	 * Opens (and closes) a temporary adapter just for this scan. If the caller
	 * intends to connect to whichever device is picked from the result afterward,
	 * prefer {@link #scanForNusDevices(BleAdapter, int)} plus
	 * {@link #BleMeshcoreCompanion(String, String, BleAdapter)} instead, so that
	 * connect doesn't have to open a second adapter and re-scan for an address this
	 * call already found.
	 *
	 * @param timeoutMs scan duration in milliseconds
	 * @return list of "address (name)" strings for each discovered peripheral
	 * @throws IOException if no adapter found or scan fails
	 */
	public static List<String> scanForNusDevices(int timeoutMs) throws IOException {
		try (BleAdapter adapter = new BleAdapter()) {
			return scanForNusDevices(adapter, timeoutMs);
		} catch (BleException e) {
			throw new IOException("Failed to start BLE sidecar", e);
		}
	}

	/**
	 * Same as {@link #scanForNusDevices(int)}, but scans on a caller-supplied
	 * {@code adapter} instead of opening (and closing) a temporary one - not closed
	 * by this method. Pass the same {@code adapter} to
	 * {@link #BleMeshcoreCompanion(String, String, BleAdapter)} to connect to
	 * whichever address is picked from the result without a second, redundant scan;
	 * close {@code adapter} yourself if it ends up unused (e.g. the user cancels a
	 * device picker).
	 *
	 * @param adapter   the adapter to scan on
	 * @param timeoutMs scan duration in milliseconds
	 * @return list of "address (name)" strings for each discovered peripheral
	 * @throws IOException if the scan fails
	 */
	public static List<String> scanForNusDevices(BleAdapter adapter, int timeoutMs) throws IOException {
		try {
			// BleUtils.scan already dedupes by address, preferring a result with a non-null
			// name
			// over one without, across the repeated advertisements a device sends during
			// the
			// scan
			// window.
			List<BleUtils.BleDeviceResult> devices = BleUtils.scan(adapter,
					new ScanFilter().withServiceUuid(NUS_SERVICE), timeoutMs);
			List<String> result = new ArrayList<>();
			for (BleUtils.BleDeviceResult device : devices) {
				result.add(device.getAddress() + " (" + (device.getName() != null ? device.getName() : "") + ")");
			}
			return result;
		} catch (BleException e) {
			throw new IOException("BLE scan failed", e);
		}
	}

	/**
	 * Scans for NUS devices and returns a handle bundling the results with the
	 * adapter that found them, so a caller who lets the user pick one to connect to
	 * can do so via {@link NusScanResult#connect} - without a second, redundant
	 * scan, and without needing to reference {@link BleAdapter} (or any other
	 * BSToolbox-BLE type) directly. That matters for a caller that wants to depend
	 * only on this Meshcore library and not directly on BSToolbox-BLE - e.g. an
	 * application that otherwise only uses the TCP/Serial transports and only
	 * touches BLE-specific types through this class.
	 *
	 * @param timeoutMs scan duration in milliseconds
	 * @return the scan results plus a handle to connect (or close) with
	 * @throws IOException if no adapter found or scan fails
	 */
	public static NusScanResult scanForNusDevicesKeepingAdapter(int timeoutMs) throws IOException {
		BleAdapter adapter;
		try {
			adapter = new BleAdapter();
		} catch (BleException e) {
			throw new IOException("Failed to start BLE sidecar", e);
		}
		try {
			return new NusScanResult(adapter, scanForNusDevices(adapter, timeoutMs));
		} catch (IOException e) {
			adapter.close();
			throw e;
		}
	}

	/**
	 * Bundles a {@link #scanForNusDevicesKeepingAdapter} result with the adapter
	 * that produced it. Exactly one of {@link #connect} or {@link #close} should
	 * eventually be called: {@link #connect} takes over the adapter (the returned
	 * companion closes it like any other it owns); {@link #close} closes it
	 * directly, for when the scan result ends up unused (e.g. the user cancels a
	 * device picker). Calling {@link #close} after {@link #connect} is harmless - a
	 * no-op, since the companion already owns the adapter by then.
	 */
	public static final class NusScanResult implements AutoCloseable {
		private final BleAdapter adapter;
		private final List<String> devices;
		private volatile boolean adopted;

		private NusScanResult(BleAdapter adapter, List<String> devices) {
			this.adapter = adapter;
			this.devices = devices;
		}

		/**
		 * "address (name)" strings for each discovered peripheral - see
		 * {@link BleMeshcoreCompanion#scanForNusDevices(int)}.
		 */
		public List<String> getDevices() {
			return devices;
		}

		/**
		 * Connects to {@code address} (one of {@link #getDevices}'s addresses) using
		 * the same adapter this scan ran on - no re-scan. Ownership of the adapter
		 * transfers to the returned companion; {@link #close} on this result becomes a
		 * no-op afterward.
		 */
		public BleMeshcoreCompanion connect(String name, String address) {
			adopted = true;
			return new BleMeshcoreCompanion(name, address, adapter);
		}

		@Override
		public void close() {
			if (!adopted)
				adapter.close();
		}
	}
}
