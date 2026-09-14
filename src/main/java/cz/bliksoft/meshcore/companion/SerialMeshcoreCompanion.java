package cz.bliksoft.meshcore.companion;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fazecast.jSerialComm.SerialPort;

import cz.bliksoft.meshcore.frames.FrameConstants;

public class SerialMeshcoreCompanion extends MeshcoreCompanion {
	private static final Logger log = Logger.getLogger(SerialMeshcoreCompanion.class.getName());

	private final String portName;
	private final int baud;

	private volatile InputStream in;
	private volatile OutputStream out;

	private volatile SerialPort port;

	/**
	 * Lists available serial ports as {@code "SYSTEM_NAME – Descriptive Name"}
	 * strings, so a caller can present a picker without depending on jSerialComm
	 * ({@link SerialPort}) directly - matches {@link BleMeshcoreCompanion}'s own
	 * {@code "address (name)"} scan-result format for the same reason: this
	 * Meshcore library is meant to stay usable (e.g. TCP-only) without forcing a
	 * transport-specific dependency on every consumer. Split on {@code " – "} (an
	 * en dash, not a hyphen) to recover the system port name to pass to the
	 * constructor.
	 *
	 * @return list of "SYSTEM_NAME – Descriptive Name" strings, one per port
	 */
	public static List<String> listPorts() {
		SerialPort[] ports = SerialPort.getCommPorts();
		List<String> result = new ArrayList<>(ports.length);
		for (SerialPort p : ports) {
			result.add(p.getSystemPortName() + " – " + p.getDescriptivePortName());
		}
		return result;
	}

	public SerialMeshcoreCompanion(String name, String portName, int baud) throws IOException {
		super(name);
		this.portName = Objects.requireNonNull(portName, "portName");
		this.baud = baud;

		startLoop();
	}

	@Override
	public boolean isConnected() {
		SerialPort p = this.port;
		return p != null && p.isOpen();
	}

	private void openPort() throws IOException {
		SerialPort p = SerialPort.getCommPort(portName);
		p.setBaudRate(baud);
		p.setNumDataBits(8);
		p.setNumStopBits(SerialPort.ONE_STOP_BIT);
		p.setParity(SerialPort.NO_PARITY);
		p.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
		if (!p.openPort())
			throw new IOException("Cannot open " + portName);

		this.port = p;
		this.in = p.getInputStream();
		this.out = p.getOutputStream();
	}

	private void closePortQuietly() {
		try {
			OutputStream o = out;
			if (o != null)
				o.close();
		} catch (Exception ignore) {
		}
		try {
			InputStream i = in;
			if (i != null)
				i.close();
		} catch (Exception ignore) {
		}
		try {
			SerialPort p = port;
			if (p != null)
				p.closePort();
		} catch (Exception ignore) {
		}
		out = null;
		in = null;
		port = null;
	}

	@Override
	protected void onDeviceConnected() {
		super.onDeviceConnected();
		log.info(String.format("Connected to %s", portName));
	}

	@Override
	protected void onDeviceDisconnected(Exception cause) {
		super.onDeviceDisconnected(cause);
		if (cause instanceof IOException)
			log.warning(String.format("Disconnected from %s: %s", portName, cause.toString()));
		else
			log.log(Level.SEVERE, "communiaction error", cause);
	}

	@Override
	public void close() {
		super.close();
		Thread t = readerThread;
		if (t != null) {
			t.interrupt();
		}
		closePortQuietly();
	}

	@Override
	void checkConnection() throws IOException {
		if (!isConnected())
			throw new IOException("Not connected!");
	}

	protected void startLoop() {
		this.readerThread = new Thread(() -> {
			long backoffMs = 500;
			while (!terminate) {
				try {
					openPort();
					backoffMs = 500;
					onDeviceConnected();
					runLoop();
					// runLoop ended cleanly (EOF)
					throw new EOFException("Disconnected");
				} catch (Exception e) {
					if (terminate)
						return;
					onDeviceDisconnected(e);
					closePortQuietly();

					try {
						Thread.sleep(backoffMs);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return;
					}
					backoffMs = Math.min(10_000, backoffMs * 2);
				}
			}
		});
		this.readerThread.setDaemon(true);
		this.readerThread.setName("MeshcoreMessageReader");
		this.readerThread.start();
	}

	/**
	 * Write a serial frame: {@code '<'} marker + u16le length + payload.
	 *
	 * @param payload serialized frame bytes
	 * @throws IOException if the port write fails
	 */
	@Override
	protected synchronized void sendBinaryFrame(byte[] payload) throws IOException {
//		log.fine(String.format("send payload: %s", MeshcoreUtils.hex(payload)));
		checkConnection();
		OutputStream o = out;
		if (o == null)
			throw new IOException("Not connected");
		int len = payload.length;
		o.write(FrameConstants.SERIAL_FRAME_TO_RADIO);
		o.write(len & 0xFF);
		o.write((len >>> 8) & 0xFF);
		o.write(payload);
		o.flush();
	}

	/**
	 * Read exactly {@code len} bytes from {@code in}, blocking until all bytes are
	 * available. Replacement for {@code InputStream.readNBytes} which is not
	 * available on Java 8.
	 *
	 * @param in  source stream
	 * @param len exact number of bytes to read
	 * @return byte array of the requested length (may be shorter on EOF)
	 * @throws IOException if the stream reports an error
	 */
	private static byte[] readNBytes(InputStream in, int len) throws IOException {
		byte[] buffer = new byte[len];
		int off = 0;

		while (off < len) {
			int r = in.read(buffer, off, len - off);
			if (r == -1)
				break;
			off += r;
		}

		if (off == len) {
			return buffer;
		}
		return Arrays.copyOf(buffer, off);
	}

	/**
	 * internal frame reader Radio -> App frame: '>' + u16le len + payload. Reader
	 * thread only.
	 */
	@Override
	protected byte[] getBinaryFrame() throws IOException {
		checkConnection();
		InputStream i = in;
		if (i == null)
			return null;

		int b;
		do {
			b = i.read();
		} while (b != -1 && b != FrameConstants.SERIAL_FRAME_FROM_RADIO);
		if (b == -1)
			return null;

		int lo = i.read();
		int hi = i.read();
		if (lo < 0 || hi < 0)
			throw new EOFException();
		int len = lo | (hi << 8);

		byte[] payload = readNBytes(i, len); // Java 9+ i.readNBytes(len);
		if (payload.length != len)
			throw new EOFException();
		return payload;
	}
}
