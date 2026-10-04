package cz.bliksoft.meshcore.companion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.meshcore.utils.MeshcoreUtils;

/**
 * Writes companion frames as one JSON file each:
 * {@code <timestamp>-<device>-<frameType>-<kind>.json}, {@code kind} being
 * {@code req} (sent command), {@code resp} (response) or {@code evt} (push).
 * Failures are logged (once) and swallowed - traffic logging must never break
 * the protocol itself.
 */
final class FrameTrafficLog {

	private static final Logger log = Logger.getLogger(FrameTrafficLog.class.getName());
	/** Process-wide, keeps files written within the same millisecond ordered. */
	private static final AtomicLong SEQ = new AtomicLong();

	private static volatile boolean failureReported = false;

	private FrameTrafficLog() {
	}

	static void write(File dir, String device, String frameType, String kind, Object frame, byte[] data) {
		if (dir == null)
			return;
		try {
			Date now = new Date();
			String fileName = String.format("%s-%06d-%s-%s-%s.json",
					new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(now), SEQ.incrementAndGet() % 1000000,
					sanitize(device), sanitize(frameType), kind);

			String text;
			try {
				text = String.valueOf(frame);
			} catch (Exception e) {
				text = "toString() failed: " + e;
			}

			StringBuilder sb = new StringBuilder();
			sb.append("{\n");
			field(sb, "timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(now)).append(",\n");
			field(sb, "direction", "req".equals(kind) ? "out" : "in").append(",\n");
			field(sb, "kind", kind).append(",\n");
			field(sb, "device", device).append(",\n");
			field(sb, "frameType", frameType).append(",\n");
			field(sb, "frameClass", frame == null ? null : frame.getClass().getName()).append(",\n");
			field(sb, "hex", data == null ? null : MeshcoreUtils.hex(data)).append(",\n");
			field(sb, "text", text).append("\n}\n");

			if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
				throw new IOException("Cannot create " + dir);
			try (OutputStream os = new FileOutputStream(new File(dir, fileName))) {
				os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException | RuntimeException e) {
			if (!failureReported) {
				failureReported = true;
				log.log(Level.WARNING,
						String.format("Frame traffic log write to %s failed (further failures not reported)", dir), e);
			}
		}
	}

	static String sanitize(String s) {
		if (s == null || s.isEmpty())
			return "unknown";
		return s.replaceAll("[\\\\/:*?\"<>|\\s]", "-");
	}

	private static StringBuilder field(StringBuilder sb, String name, String value) {
		sb.append("  \"").append(name).append("\": ");
		if (value == null)
			return sb.append("null");
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '"':
				sb.append("\\\"");
				break;
			case '\\':
				sb.append("\\\\");
				break;
			case '\n':
				sb.append("\\n");
				break;
			case '\r':
				sb.append("\\r");
				break;
			case '\t':
				sb.append("\\t");
				break;
			default:
				if (c < 0x20)
					sb.append(String.format("\\u%04x", (int) c));
				else
					sb.append(c);
			}
		}
		return sb.append('"');
	}
}
