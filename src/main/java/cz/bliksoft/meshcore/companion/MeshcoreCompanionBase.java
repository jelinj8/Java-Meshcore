package cz.bliksoft.meshcore.companion;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.meshcore.frames.CommandFrame;
import cz.bliksoft.meshcore.frames.Frame;
import cz.bliksoft.meshcore.frames.FrameConstants.ResponseFrameType;
import cz.bliksoft.meshcore.frames.ResponseFrame;
import cz.bliksoft.meshcore.frames.cmd.CmdAppStart;
import cz.bliksoft.meshcore.frames.cmd.CmdDeviceQuery;
import cz.bliksoft.meshcore.frames.resp.DeviceInfo;
import cz.bliksoft.meshcore.frames.resp.SelfInfo;
import cz.bliksoft.meshcore.utils.MeshcoreUtils;

/**
 * Abstract base for MeshCore companion device connections.
 *
 * <p>
 * Design: a single reader thread owns all frame reading and parsing; event
 * callbacks are dispatched to a dedicated {@code eventExecutor} so listeners
 * never block the reader. Blocking request/response calls are safe from any
 * thread except the reader thread itself, and are serialised by an internal
 * lock because the MeshCore companion protocol has no request-ID field.
 * </p>
 *
 * <p>
 * Subclasses provide transport-specific implementations of
 * {@link #sendBinaryFrame} and {@link #getBinaryFrame}, and start the reader
 * loop by calling {@link #runLoop()} from a dedicated thread.
 * </p>
 *
 * @see MeshcoreCompanion
 */
public abstract class MeshcoreCompanionBase implements Closeable {

	/**
	 * Callback interface notified when the companion device becomes ready or
	 * unavailable. Callbacks are always invoked on the event executor thread.
	 */
	public interface AvailabilityListener {
		/**
		 * Called when the device has completed initialisation and is ready to accept
		 * commands.
		 *
		 * @param companion the companion that became available
		 */
		void onAvailable(MeshcoreCompanionBase companion);

		/**
		 * Called when the device connection is lost or the companion is closed.
		 *
		 * @param companion the companion that became unavailable
		 */
		void onUnavailable(MeshcoreCompanionBase companion);
	}

	public MeshcoreCompanionBase(String name) {
		this.name = name;
		this.trafficLogDir = defaultTrafficLogDir;
	}

	private static volatile File defaultTrafficLogDir = null;
	private volatile File trafficLogDir;

	/**
	 * Sets the traffic log folder for companions created from now on (see
	 * {@link #setTrafficLogDir(File)}); {@code null} disables. Transport
	 * constructors start talking to the device right away, so this is the way to
	 * capture the initial handshake as well. Existing companions are not affected.
	 *
	 * @param dir target folder, or {@code null}
	 */
	public static void setDefaultTrafficLogDir(File dir) {
		defaultTrafficLogDir = dir;
	}

	/**
	 * @return the traffic log folder for newly created companions, or {@code null}
	 */
	public static File getDefaultTrafficLogDir() {
		return defaultTrafficLogDir;
	}

	/**
	 * Enables traffic logging: every frame sent to / received from the device is
	 * written to {@code dir} as {@code <timestamp>-<device>-<frameType>-req.json}
	 * (commands), {@code ...-resp.json} (responses) or {@code ...-evt.json}
	 * (pushes), {@code device} being {@link #getDeviceId()}. The folder is created
	 * on demand. {@code null} disables. Initialized from
	 * {@link #setDefaultTrafficLogDir(File)}.
	 *
	 * @param dir target folder, or {@code null}
	 */
	public void setTrafficLogDir(File dir) {
		this.trafficLogDir = dir;
	}

	/**
	 * @return the current traffic log folder, or {@code null} when disabled
	 */
	public File getTrafficLogDir() {
		return trafficLogDir;
	}

	/**
	 * Transport-level identification of the device (serial port, {@code host:port},
	 * BLE address), used e.g. in traffic log file names.
	 *
	 * @return device identifier, defaults to {@link #getName()}
	 */
	public String getDeviceId() {
		return getName();
	}

	private void logSentFrame(Frame payload, byte[] bytes) {
		File dir = trafficLogDir;
		if (dir == null)
			return;
		String type = payload instanceof CommandFrame ? String.valueOf(((CommandFrame) payload).getFrameType())
				: payload.getClass().getSimpleName();
		FrameTrafficLog.write(dir, getDeviceId(), type, "req", payload, bytes);
	}

	private void logReceivedFrame(ResponseFrame frame, byte[] data) {
		File dir = trafficLogDir;
		if (dir == null || data == null || data.length == 0)
			return;
		ResponseFrameType ft = frame == null ? null : frame.getFrameType();
		String type = ft != null ? ft.name() : "UNKNOWN_" + MeshcoreUtils.hex(data[0]);
		FrameTrafficLog.write(dir, getDeviceId(), type, (data[0] & 0xFF) < 128 ? "resp" : "evt", frame, data);
	}

	private static Logger log = Logger.getLogger(MeshcoreCompanionBase.class.getName());
	protected static Logger frameLog = Logger.getLogger(MeshcoreCompanion.class.getName() + ".DEV");

	abstract void checkConnection() throws IOException;

	abstract boolean isConnected();

	protected volatile Thread readerThread = null;

	/**
	 * Write a complete serial frame to the transport (synchronized by the caller).
	 *
	 * @param payload serialized frame bytes
	 * @throws IOException if the transport reports an error
	 */
	abstract protected void sendBinaryFrame(byte[] payload) throws IOException;

	/**
	 * frame reader Radio -&gt; App. Reader thread only. Expects blocking read of a
	 * complete frame.
	 *
	 * @return raw frame bytes, or {@code null} to signal end-of-stream
	 * @throws IOException on transport error
	 */
	abstract protected byte[] getBinaryFrame() throws IOException;

	protected final ExecutorService eventExecutor = Executors
			.newSingleThreadExecutor(new NamedDaemonThreadFactory("MeshcoreEvents"));

	private final ReentrantLock requestLock = new ReentrantLock(true);
	private final Condition availableCondition = requestLock.newCondition();
	private final List<AvailabilityListener> availabilityListeners = new CopyOnWriteArrayList<>();

	/**
	 * Registers a listener to be notified when the device becomes available or
	 * unavailable.
	 *
	 * @param l the listener to register
	 */
	public void addAvailabilityListener(AvailabilityListener l) {
		availabilityListeners.add(l);
	}

	/**
	 * Removes a previously registered availability listener.
	 *
	 * @param l the listener to remove
	 */
	public void removeAvailabilityListener(AvailabilityListener l) {
		availabilityListeners.remove(l);
	}

	/**
	 * termination flag, set by close(), allows exiting of loops.
	 */
	protected volatile boolean terminate = false;

	/**
	 * indicator of running reader loop
	 */
	protected volatile AtomicBoolean running = new AtomicBoolean();

	/**
	 * indicator of a device being ready to process commands
	 */
	protected volatile AtomicBoolean available = new AtomicBoolean();

	/**
	 * Returns the logical name of this companion instance (used in log messages).
	 *
	 * @return the companion name
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the logical name of this companion instance.
	 *
	 * @param name the new name
	 */
	public void setName(String name) {
		this.name = name;
	}

	protected String name = "meshcore";

	// single pending blocking request (serialized by requestLock)
	private volatile CompletableFuture<ResponseFrame> pendingFuture;
	private volatile byte[] pendingAcceptCodes;

	// ----------------- Core concurrency: send + await response -----------------

	/**
	 * Send a frame without waiting for a response (fire-and-forget).
	 *
	 * @param payload frame to send
	 * @throws IOException if the underlying transport reports an error
	 */
	public void sendFrame(Frame payload) throws IOException {
		if (frameLog.isLoggable(Level.FINE))
			frameLog.fine(payload.toString());
		byte[] bytes = payload.getBytes();
		requestLock.lock();
		try {
			logSentFrame(payload, bytes);
			sendBinaryFrame(bytes);
		} finally {
			requestLock.unlock();
		}
	}

	/**
	 * Send a frame and block until one of the response types declared by the frame
	 * arrives, or the timeout expires.
	 *
	 * @param payload   command frame to send
	 * @param timeoutMs maximum time to wait for the response (milliseconds)
	 * @return the matching response frame
	 * @throws IOException          if the transport reports an error
	 * @throws TimeoutException     if no matching response arrives within the
	 *                              timeout
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public ResponseFrame sendFrameWithResult(CommandFrame payload, long timeoutMs)
			throws IOException, TimeoutException, InterruptedException {
		return sendFrameWithResult(payload, timeoutMs, payload.expectedResponses());
	}

	/**
	 * Send a frame and block until one of the specified response codes arrives.
	 *
	 * @param payload     command frame to send
	 * @param timeoutMs   maximum time to wait for the response (milliseconds)
	 * @param acceptCodes byte codes of acceptable response types
	 * @return the matching response frame
	 * @throws IOException          if the transport reports an error
	 * @throws TimeoutException     if no matching response arrives within the
	 *                              timeout
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public ResponseFrame sendFrameWithResult(CommandFrame payload, long timeoutMs, byte... acceptCodes)
			throws IOException, TimeoutException, InterruptedException {
		checkBlockingThread();
		if (frameLog.isLoggable(Level.FINE))
			frameLog.fine(payload.toString());
		requestLock.lock();
		try {
			CompletableFuture<ResponseFrame> f = new CompletableFuture<>();
			pendingFuture = f;
			if (acceptCodes == null || acceptCodes.length == 0)
				throw new IllegalArgumentException(
						"Frame does not specify any expected ResponseFrameTypes, can't wait for response. Use sendFrame.");

			pendingAcceptCodes = acceptCodes;
			byte[] bytes = payload.getBytes();
			logSentFrame(payload, bytes);
			sendBinaryFrame(bytes);
			return getFuture(f, timeoutMs);
		} finally {
			pendingFuture = null;
			pendingAcceptCodes = null;
			requestLock.unlock();
		}
	}

	protected static ResponseFrame getFuture(CompletableFuture<ResponseFrame> f, long timeoutMs)
			throws TimeoutException, InterruptedException, IOException {
		try {
			return f.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException te) {
			throw te;
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw ie;
		} catch (ExecutionException ee) {
			Throwable c = ee.getCause();
			if (c instanceof IOException)
				throw (IOException) c;
			throw new IOException("Request failed", c);
		}
	}

	/**
	 * Wait for the reader loop thread to signal that it has started (internal use).
	 *
	 * @param timeoutMs maximum wait in milliseconds
	 * @throws TimeoutException     if the loop does not start in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	protected void awaitRunning(long timeoutMs) throws TimeoutException, InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			if (running.get()) {
				return;
			}
			Thread.sleep(50);
		}
		throw new TimeoutException("Meshcore loop not running in time");
	}

	/**
	 * Block until the device has completed initialization after (re)connect. This
	 * is the canonical way to wait for the companion to be ready at startup.
	 *
	 * @param timeoutMs maximum wait in milliseconds
	 * @throws TimeoutException     if the device does not become available in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void awaitAvailable(long timeoutMs) throws TimeoutException, InterruptedException {
		if (available.get())
			return;
		requestLock.lock();
		try {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
			while (!available.get()) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0)
					throw new TimeoutException("Meshcore device not available in time");
				availableCondition.awaitNanos(remaining);
			}
		} finally {
			requestLock.unlock();
		}
	}

	/**
	 * Returns {@code true} if the companion device is connected and has completed
	 * initialization (handshake + {@link #deviceInit}) and is ready to accept
	 * commands.
	 *
	 * @return true if the device is available
	 */
	public boolean isAvailable() {
		return available.get();
	}

	/**
	 * Main Meshcore input point. Keep running in its own thread, do not block by
	 * waiting for events (they will not trigger if blocked!)
	 *
	 * @throws IOException          on transport error
	 * @throws InterruptedException if the calling thread is interrupted
	 * @throws TimeoutException     if device handshake does not complete in time
	 */
	protected void runLoop() throws IOException, TimeoutException, InterruptedException {
		eventExecutor.execute(() -> {
			try {
				running.set(true);
				deviceHandshake();
			} catch (IOException | TimeoutException | InterruptedException e) {
				running.set(false);
				log.severe(String.format("Failed to initialize Mesh companion device: %s", e));
			}
		});
		try {
			// wait for processing of device handshake
			awaitRunning(2000l);
			eventExecutor.execute(() -> {
				try {
					deviceInit();
					requestLock.lock();
					try {
						available.set(true);
						availableCondition.signalAll();
					} finally {
						requestLock.unlock();
					}
					fireAvailabilityListeners(true);
				} catch (IOException e) {
					available.set(false);
					log.severe(String.format("Failed to initialize Mesh companion device: %s", e));
				}
			});
			while (!terminate && isConnected()) {
				byte[] p = getBinaryFrame();
				if (p == null)
					return;
				dispatchFrame(p);
			}
		} catch (TimeoutException to) {
			log.severe("Mesh device not initialized in time.");
		} finally {
			running.set(false);
		}
	}

	private DeviceInfo deviceInfo = null;
	private SelfInfo selfInfo = null;

	/**
	 * Returns the {@link cz.bliksoft.meshcore.frames.resp.DeviceInfo} fetched
	 * during the device handshake, or {@code null} if the handshake has not
	 * completed yet.
	 *
	 * @return device firmware info, or {@code null}
	 */
	public DeviceInfo getDeviceInfo() {
		return deviceInfo;
	}

	/**
	 * Returns the {@link cz.bliksoft.meshcore.frames.resp.SelfInfo} fetched during
	 * the device handshake, or {@code null} if the handshake has not completed yet.
	 *
	 * @return device self-info, or {@code null}
	 */
	public SelfInfo getSelfInfo() {
		return selfInfo;
	}

	/**
	 * Re-queries the device for its firmware info and updates the cached value.
	 *
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void refreshDeviceInfo() throws IOException, TimeoutException, InterruptedException {
		deviceInfo = (DeviceInfo) sendFrameWithResult(new CmdDeviceQuery(), 1000);
	}

	/**
	 * Re-queries the device for its self-info and updates the cached value.
	 *
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void refreshSelfInfo() throws IOException, TimeoutException, InterruptedException {
		selfInfo = (SelfInfo) sendFrameWithResult(new CmdAppStart("BSMeshcore"), 1000);
	}

	/**
	 * initial protocol handshake, loop not yet running
	 *
	 * @throws IOException          on transport error
	 * @throws InterruptedException if the calling thread is interrupted
	 * @throws TimeoutException     if no response from device
	 */
	protected void deviceHandshake() throws IOException, TimeoutException, InterruptedException {
		refreshDeviceInfo();
		refreshSelfInfo();
	}

	/**
	 * additional initialization in online mode (e.g. set time), this is the place
	 * to initialize after connection.
	 *
	 * @throws IOException on transport error
	 */
	protected void deviceInit() throws IOException {

	}

	/**
	 * call when a source device is available
	 */
	protected void onDeviceConnected() {
	}

	/**
	 * Called when the source device becomes unavailable or disconnects. Subclass
	 * overrides must call {@code super.onDeviceDisconnected(cause)} for cleanup.
	 *
	 * @param cause the exception that triggered the disconnect
	 */
	protected void onDeviceDisconnected(Exception cause) {
		available.set(false);
		failAllWaiters(new IOException("Disconnected", cause));
		fireAvailabilityListeners(false);
	}

	// ----------------- Utils -----------------

	protected static final class NamedDaemonThreadFactory implements ThreadFactory {
		private final String baseName;
		private int idx = 1;

		NamedDaemonThreadFactory(String baseName) {
			this.baseName = baseName;
		}

		@Override
		public synchronized Thread newThread(Runnable r) {
			Thread t = new Thread(r);
			t.setDaemon(true);
			t.setName(baseName + "-" + (idx++));
			return t;
		}
	}

	/** Close and stop background threads. */
	public void close() {
		log.info("Meshcore close");
		terminate = true;
		// fail pending request
		CompletableFuture<ResponseFrame> f = pendingFuture;
		if (f != null && !f.isDone()) {
			f.completeExceptionally(new IOException("Closed"));
		}
		pendingFuture = null;
		pendingAcceptCodes = null;

		// stop events
		eventExecutor.shutdownNow();
	}

	protected void failAllWaiters(IOException ex) {
		// pending request/response
		CompletableFuture<ResponseFrame> pf = pendingFuture;
		if (pf != null)
			pf.completeExceptionally(ex);
		pendingFuture = null;
		pendingAcceptCodes = null;

	}

	protected void checkBlockingThread() {
		if (terminate)
			throw new IllegalStateException("Companion is closed");
		Thread rt = readerThread;
		if (rt == null || !rt.isAlive())
			throw new IllegalStateException("Reader thread not started!");
		if (Thread.currentThread() == rt)
			throw new IllegalStateException(
					"Blocking API cannot be called from reader thread. Dispatch work to eventExecutor.");
	}

	private void fireAvailabilityListeners(boolean isAvailable) {
		for (AvailabilityListener l : availabilityListeners) {
			try {
				if (isAvailable)
					l.onAvailable(this);
				else
					l.onUnavailable(this);
			} catch (Exception e) {
				log.warning("AvailabilityListener threw: " + e);
			}
		}
	}

	/**
	 * Parse raw bytes from the device and dispatch the resulting frame.
	 *
	 * @param data raw frame bytes (first byte is the type code)
	 * @throws IOException if dispatch fails
	 */
	protected void dispatchFrame(byte[] data) throws IOException {
		ResponseFrame f;
		try {
			f = (ResponseFrame) Frame.fromData((MeshcoreCompanion) this, data);
		} catch (RuntimeException e) {
			logReceivedFrame(null, data);
			throw e;
		}
		logReceivedFrame(f, data);
		dispatchFrame(f);
	}

	/**
	 * Dispatch a parsed frame to any registered pending request.
	 *
	 * @param frame parsed frame to dispatch
	 * @return {@code true} if the frame was consumed by a pending request
	 * @throws IOException if dispatch fails
	 */
	protected boolean dispatchFrame(ResponseFrame frame) throws IOException {
		if (frame == null)
			return true;

		if (frameLog.isLoggable(Level.FINE)) {
			try {
				frameLog.fine(frame.toString());
			} catch (Exception e) {
				frameLog.log(Level.SEVERE, String.format("Failed to log frame %s", frame.getFrameType()), e);
			}
		}

		// 1) complete pending blocking request if it matches
		CompletableFuture<ResponseFrame> f = pendingFuture;
		byte[] accept = pendingAcceptCodes;
		if (f != null && accept != null && accept.length > 0) {
			for (byte ac : accept) {
				if (frame.is(ac)) {
					f.complete(frame);
					return true; // consumed by request
				}
			}
		}

		return false;
	}
}
