package cz.bliksoft.meshcore.companion;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.meshcore.frames.FrameConstants;

public class TCPMeshcoreCompanion extends MeshcoreCompanion {
	private static final Logger log = Logger.getLogger(TCPMeshcoreCompanion.class.getName());

	private final String host;
	private final int port;

	private volatile InputStream in;
	private volatile OutputStream out;

	private volatile Socket socket;

	public TCPMeshcoreCompanion(String name, String host, int port) throws IOException {
		super(name);
		this.host = Objects.requireNonNull(host, "host");
		this.port = port;

		startLoop();
	}

	@Override
	public String getDeviceId() {
		return host + ":" + port;
	}

	@Override
	public boolean isConnected() {
		Socket s = this.socket;
		return s != null && !s.isClosed() && s.isConnected();
	}

	private void openSocket() throws IOException {
		Socket s = new Socket(host, port);
		s.setTcpNoDelay(true);

		this.socket = s;
		this.in = s.getInputStream();
		this.out = s.getOutputStream();
	}

	private void closeSocketQuietly() {
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
			Socket s = socket;
			if (s != null)
				s.close();
		} catch (Exception ignore) {
		}
		out = null;
		in = null;
		socket = null;
	}

	@Override
	protected void onDeviceConnected() {
		super.onDeviceConnected();
		log.info(String.format("Connected to %s:%d", host, port));
	}

	@Override
	protected void onDeviceDisconnected(Exception cause) {
		super.onDeviceDisconnected(cause);
		if (cause instanceof IOException)
			log.warning(String.format("Disconnected from %s:%d: %s", host, port, cause.toString()));
		else
			log.log(Level.SEVERE, "communication error", cause);
	}

	@Override
	public void close() {
		super.close();
		Thread t = readerThread;
		if (t != null) {
			t.interrupt();
		}
		closeSocketQuietly();
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
					openSocket();
					backoffMs = 500;
					onDeviceConnected();
					runLoop();
					// runLoop ended cleanly (EOF)
					throw new EOFException("Disconnected");
				} catch (Exception e) {
					if (terminate)
						return;
					onDeviceDisconnected(e);
					closeSocketQuietly();

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

	@Override
	protected synchronized void sendBinaryFrame(byte[] payload) throws IOException {
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

		byte[] payload = readNBytes(i, len);
		if (payload.length != len)
			throw new EOFException();
		return payload;
	}
}
