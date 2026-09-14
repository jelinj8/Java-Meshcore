This is an implementation of the [MeshCore companion serial interface](https://github.com/meshcore-dev/MeshCore/tree/main/examples/companion_radio). It allows full control of the companion (all communication frame types are implemented) currently via USB.
Implements companion protocol version `FIRMWARE_VER_CODE=13`, unchanged from firmware v1.16.0 through v1.17.1 (including multibyte routing).

Initial version was written by me (reverse-engineering of the C++ firmware source), later with help from AI (mainly sanity checking, decryption, cleanup and mechanical tasks). Now I'm using it to keep in sync with firmware updates. Fortunately the AI still needs someone who knows what they want and directs it, but it is gaining traction.

Everything should be compatible with JDK1.8. It contradicts the "think embedded" suggestion at Meshcore's GitHub as it is a higher level language and the main idea is to keep it maintainable and in sync with the firmware (that is why there is the Frame type hierarchy).

The serial (com.fazecast jSerialComm) and BLE (cz.bliksoft.java:common-java-utils-ble) transports are kept as separate `provided`-scope dependencies, so you add only what you need. Plus Log4J.
That should enable this to be used even in Android apps (to finally make an open-source companion app). I'm not an Android developer, I'm going to make a PC (JavaFX based) companion eventually + use this as an API to integrate the companion in a server APP.

BLE is implemented via `BleMeshcoreCompanion`, which tunnels the same byte-stream protocol over the Nordic UART Service (NUS). Both transports are `provided`-scope dependencies, so you add only what you need.

## Dependencies

Both transport dependencies are `provided` — add only the one(s) you use to your project:

**Serial (USB):**
```xml
<dependency>
    <groupId>com.fazecast</groupId>
    <artifactId>jSerialComm</artifactId>
    <version>2.11.4</version>
</dependency>
```

**BLE:**
```xml
<dependency>
    <groupId>cz.bliksoft.java</groupId>
    <artifactId>common-java-utils-ble</artifactId>
    <version>0.5.0</version>
</dependency>
```

[`common-java-utils-ble`](https://github.com/jelinj8/Java-BSToolbox-BLE) (BSToolbox-BLE) is a
standalone cross-platform BLE library. Unlike the previous `simplejavable`/SimpleBLE dependency,
it doesn't run BLE in-process via JNI — it drives a bundled Rust sidecar process instead, so a
native-side BLE fault can't crash the JVM, and there's no separate native library to install or
put on `java.library.path`; the sidecar binaries for Windows/Linux are bundled in the jar.

## BLE usage

First scan to find the device address:
```java
BleMeshcoreCompanion.scanForNusDevices(5000).forEach(System.out::println);
// prints: "AA:BB:CC:DD:EE:FF (MeshCore)"
```

Then connect the same way as serial:
```java
BleMeshcoreCompanion companion = new BleMeshcoreCompanion("myNode", "AA:BB:CC:DD:EE:FF");
companion.awaitAvailable(10_000);
// use companion exactly like SerialMeshcoreCompanion from here
companion.close();
```

`BleMeshcoreCompanion` expects the radio firmware to expose the [Nordic UART Service](https://developer.nordicsemi.com/nRF_Connect_SDK/doc/latest/nrf/libraries/bluetooth_services/services/nus.html) (NUS). The UUIDs used are the standard ones (`6E400001...` / TX `6E400002...` / RX `6E400003...`) — confirmed against the [firmware's own BLE companion protocol docs](https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol).

**Pairing:** companion_radio's BLE GATT is configured with MITM protection and mandatory bonding on both ESP32 and NRF52 builds, using a static PIN (`123456` by default). Pair the device via your OS's Bluetooth settings *before* connecting — this library has no way to drive the OS pairing flow itself. Connecting to an unpaired device that requires bonding fails with a clear authentication error rather than hanging silently.

**Known issue on Windows:** BLE pairing and GATT operations against MeshCore devices have been unreliable on Windows in testing (hangs after pairing, and pairing two MeshCore devices to the same PC at once can break BLE for both) — not reproducible on Linux/BlueZ so far. If BLE is flaky, try Linux before assuming a bug in this library.

All communication is logged (Log4J2 to cz.bliksoft.meshcore.companion.MeshcoreCompanion.DEV FINE).

More detailed logging of air frames can be enabled by setting

```java
// default, just read RSSI, SNR, routing and message types.
LogRXDataPush.isDecodeRaw(true);

// parse also transmitted frames content - that provides also routing hops and deciphering of Group and Unicast text frames where possible (using configured channels for Groups and own key for unicast messages where the recipient is the companion).
LogRXDataPush.isDecodePayload(true);

// detailed logging of path hops with contacts identified by prefixes, with multiple values where not unique.
LogRXDataPush.setTranslatePath(true);
```

This is a simple usage example:

```java
public class SimpleMeshcoreCompanion extends SerialMeshcoreCompanion {
	Logger log = LogManager.getLogger();

	public SimpleMeshcoreCompanion(FileObject definition) throws IOException {
		super(definition.getAttribute("name", definition.getName()),
				Objects.requireNonNull(definition.getAttribute("com"), "com"), definition.getInt("baud", 115200));

		drainMessages();
		installAutosyncMessages();

		FrameListener<ResponseFrame> msgReader = new FrameListener<>() {
			@Override
			public void onFrame(ResponseFrame frame) {
				switch (frame.getFrameType()) {
				case RESP_CHANNEL_MSG_RECV:
				case RESP_CHANNEL_MSG_RECV_V3: {
					ChannelMsgRecv m = (ChannelMsgRecv) frame;
					log.info(String.format("Group message %s: %s", getChannel(m.getChannelIdx()).getName(),
							m.getText()));
				}
					break;
				case RESP_CONTACT_MSG_RECV:
				case RESP_CONTACT_MSG_RECV_V3: {
					ContactMsgRecv m = (ContactMsgRecv) frame;
					log.info("Message: " + m.getText());
					try {
						sendFrameWithResultAndResponse(
								new CmdSendTxtMsg(m.getTextType(), m.getFrom6(), 0, null, "ECHO: " + m.getText()),

								1000l, 1000l);
						log.info("Message confirmed.");
					} catch (IOException e) {
						e.printStackTrace();
					} catch (TimeoutException e) {
						log.info("Message NOT confirmed in time.");
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
					break;

				default:
					break;
				}
			}
		};

		registerFrameListener(MessageFrameGroup.class, msgReader);
	}

	@Override
	protected void deviceInit() throws IOException {
		super.deviceInit();
		try {
			sendFrameWithResult(new CmdGetDeviceTime(), 1000l);
			sendFrameWithResult(new CmdSetDeviceTime(java.time.Instant.now().getEpochSecond()), 1000l);
			sendFrameWithResult(new CmdGetAutoaddConfig(), 1000);
			drainMessages();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
```

A BruteForceDecryptor can be registered as a listener to discover and decrypt hash channels:

```java
		GroupTextDecryptor decryptor = new GroupTextDecryptor(1, 6);

		decryptor.install(mesh, new GroupTextDecryptor.Listener() {
			public void onDecrypted(String ch, byte[] key, long ts, String text, float snr, int rssi) {
				log.info(String.format("[%s] %s  (snr=%.1f rssi=%d)%n", ch, text, snr, rssi));
			}

			public void onChannelDiscovered(String ch, byte[] key) {
				log.info(String.format("Discovered channel: %s%n", ch));
			}

			@Override
			public void onBruteForceComplete(byte channelHash, long matched, boolean stopped) {
				log.info(String.format("BruteForce finished (matched %d)", matched));
			}
		});
```

Maximum reasonable length to decrypt is 7 characters (~2 minutes on a 24C CPU).

Contacts and channels are synchronized on connect and then kept synchronized. All communication with the Companion triggers "events" that can have registered "listeners" (by frame class or superclass like MessageFrameGroup or ContactFrameGroup).

NewAdvertPush event adds the contact only in local memory, so it can be later/in a listener added to Companion's storage (.getCmdAddUpdateContact() and send the resulting frame to companion) or just used to identify a node in air traffic.

Suggestions are welcome, more examples might come.
