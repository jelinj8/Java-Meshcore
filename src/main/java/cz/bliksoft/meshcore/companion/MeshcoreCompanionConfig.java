package cz.bliksoft.meshcore.companion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import cz.bliksoft.meshcore.CompanionErrorException;
import cz.bliksoft.meshcore.FrameListener;
import cz.bliksoft.meshcore.frames.FrameConstants;
import cz.bliksoft.meshcore.frames.FrameConstants.AdvertLocPolicy;
import cz.bliksoft.meshcore.frames.FrameConstants.AutoAddConfigFlags;
import cz.bliksoft.meshcore.frames.FrameConstants.StatsCommandFrameSubtype;
import cz.bliksoft.meshcore.frames.ResponseFrame;
import cz.bliksoft.meshcore.frames.cmd.CmdExportContact;
import cz.bliksoft.meshcore.frames.cmd.CmdExportPrivateKey;
import cz.bliksoft.meshcore.frames.cmd.CmdGetAdvertPath;
import cz.bliksoft.meshcore.frames.cmd.CmdGetAutoaddConfig;
import cz.bliksoft.meshcore.frames.cmd.CmdGetBattAndStorage;
import cz.bliksoft.meshcore.frames.cmd.CmdGetChannel;
import cz.bliksoft.meshcore.frames.cmd.CmdGetContactByKey;
import cz.bliksoft.meshcore.frames.cmd.CmdGetContacts;
import cz.bliksoft.meshcore.frames.cmd.CmdGetCustomVars;
import cz.bliksoft.meshcore.frames.cmd.CmdGetDefaultFloodScope;
import cz.bliksoft.meshcore.frames.cmd.CmdGetDeviceTime;
import cz.bliksoft.meshcore.frames.cmd.CmdGetStats;
import cz.bliksoft.meshcore.frames.cmd.CmdGetTuningParams;
import cz.bliksoft.meshcore.frames.cmd.CmdImportContact;
import cz.bliksoft.meshcore.frames.cmd.CmdImportPrivateKey;
import cz.bliksoft.meshcore.frames.cmd.CmdRemoveContact;
import cz.bliksoft.meshcore.frames.cmd.CmdResetPath;
import cz.bliksoft.meshcore.frames.cmd.CmdSetAdvertLatLon;
import cz.bliksoft.meshcore.frames.cmd.CmdSetAdvertName;
import cz.bliksoft.meshcore.frames.cmd.CmdSetAutoaddConfig;
import cz.bliksoft.meshcore.frames.cmd.CmdSetChannel;
import cz.bliksoft.meshcore.frames.cmd.CmdSetCustomVar;
import cz.bliksoft.meshcore.frames.cmd.CmdSetDefaultFloodScope;
import cz.bliksoft.meshcore.frames.cmd.CmdSetDevicePin;
import cz.bliksoft.meshcore.frames.cmd.CmdSetDeviceTime;
import cz.bliksoft.meshcore.frames.cmd.CmdSetFloodScope;
import cz.bliksoft.meshcore.frames.cmd.CmdSetOtherParams;
import cz.bliksoft.meshcore.frames.cmd.CmdSetPathHashMode;
import cz.bliksoft.meshcore.frames.cmd.CmdSetRadioParams;
import cz.bliksoft.meshcore.frames.cmd.CmdSetRadioTXPower;
import cz.bliksoft.meshcore.frames.cmd.CmdSetTuningParams;
import cz.bliksoft.meshcore.frames.cmd.CmdShareContact;
import cz.bliksoft.meshcore.frames.group.ContactFrameGroup;
import cz.bliksoft.meshcore.frames.push.AdvertPush;
import cz.bliksoft.meshcore.frames.push.ContactDeletedPush;
import cz.bliksoft.meshcore.frames.push.PathUpdatedPush;
import cz.bliksoft.meshcore.frames.resp.AdvertPath;
import cz.bliksoft.meshcore.frames.resp.AutoaddConfig;
import cz.bliksoft.meshcore.frames.resp.BattAndStorage;
import cz.bliksoft.meshcore.frames.resp.ChannelInfo;
import cz.bliksoft.meshcore.frames.resp.Contact;
import cz.bliksoft.meshcore.frames.resp.ContactsStart;
import cz.bliksoft.meshcore.frames.resp.CurrTime;
import cz.bliksoft.meshcore.frames.resp.CustomVars;
import cz.bliksoft.meshcore.frames.resp.DefaultFloodScope;
import cz.bliksoft.meshcore.frames.resp.DeviceInfo;
import cz.bliksoft.meshcore.frames.resp.EndOfContacts;
import cz.bliksoft.meshcore.frames.resp.Error;
import cz.bliksoft.meshcore.frames.resp.ExportContact;
import cz.bliksoft.meshcore.frames.resp.Ok;
import cz.bliksoft.meshcore.frames.resp.PrivateKey;
import cz.bliksoft.meshcore.frames.resp.SelfInfo;
import cz.bliksoft.meshcore.frames.resp.Stats;
import cz.bliksoft.meshcore.frames.resp.TuningParams;
import cz.bliksoft.meshcore.utils.MeshcoreUtils;

/**
 * Configuration facade over {@link MeshcoreCompanion} for device setup, contact
 * and channel management, backup/restore, and radio configuration.
 *
 * <p>
 * An instance of this class is automatically created by
 * {@link MeshcoreCompanion} and accessed via
 * {@link MeshcoreCompanion#getConfig()}. It maintains an in-memory cache of
 * contacts and channels that is kept in sync with the device via push-frame
 * listeners.
 * </p>
 */
public class MeshcoreCompanionConfig {
	private static final Logger log = Logger.getLogger(MeshcoreCompanionConfig.class.getName());

	private final MeshcoreCompanion companion;

	public MeshcoreCompanionConfig(MeshcoreCompanion companion) {
		this.companion = companion;
	}

	/**
	 * Device clock drift (seconds) beyond which {@link #init()} sets the device
	 * clock to system time - 2 h covers DST.
	 */
	public static final long DEVICE_TIME_TOLERANCE_S = 7200;

	/**
	 * Performs initial device synchronisation: fetches battery/storage info,
	 * private key, and channels, sets the device clock if it is off by more than
	 * {@link #DEVICE_TIME_TOLERANCE_S}, then starts the contacts sync. Called
	 * automatically by the companion during device initialisation.
	 *
	 * @throws IOException on transport or synchronisation error
	 */
	public void init() throws IOException {
		try {
			companion.sendFrameWithResult(new CmdGetBattAndStorage(), 10000);
			getPrivateKey();
			syncChannels();
			syncDeviceTime();
		} catch (TimeoutException | InterruptedException e) {
			throw new IOException(e);
		}
		syncContacts(true);
	}

	/**
	 * Sets the device clock to system time if it is off by more than
	 * {@link #DEVICE_TIME_TOLERANCE_S}. Done before the contacts sync, never during
	 * it: a MeshCore nRF52 radio hung (stopped answering until a hard reset) twice
	 * within 30 ms of a time set arriving while it was streaming contacts. Not
	 * fatal - a failure is logged.
	 */
	private void syncDeviceTime() throws InterruptedException {
		try {
			long diff = Math.abs(System.currentTimeMillis() / 1000 - getDeviceTime().getTimestamp());
			if (diff > DEVICE_TIME_TOLERANCE_S) {
				setDeviceTime(null);
				log.info("Device time set (was off by " + diff + " s)");
			}
		} catch (IOException | TimeoutException | CompanionErrorException e) {
			log.log(Level.WARNING, "Failed to check/set device time", e);
		}
	}

	/**
	 * Clears all cached configuration state (private key, autoadd config, custom
	 * vars, tuning params). Called after a factory reset.
	 */
	public void reset() {
		privateKey = null;
		autoaddConfig = null;
		customVars = null;
		tuningParams = null;
	}

	private volatile Map<String, Contact> contacts = null;
	private volatile Map<String, Contact> pendingContacts = null;
	private Map<String, Contact> contactsArchive = new ConcurrentHashMap<>();

	/**
	 * Returns contacts that are not saved on the device: contacts removed from the
	 * device ({@code PUSH_CONTACT_DELETED}) and contacts seen over the air but not
	 * added ({@code PUSH_NEW_ADVERT}).
	 *
	 * @return live map of archived contacts keyed by hex public key
	 */
	public Map<String, Contact> getContactsArchive() {
		return contactsArchive;
	}

	/**
	 * Returns a snapshot of all currently saved contacts, or an empty list if the
	 * initial sync has not completed yet.
	 *
	 * @return immutable-safe copy of the saved contact list
	 */
	public List<Contact> getSavedContacts() {
		if (contacts == null)
			return Collections.emptyList();
		return new ArrayList<>(contacts.values());
	}

	/**
	 * Returns the timestamp of the last completed contacts sync, or {@code null} if
	 * no sync has finished yet. A non-null value means the saved contacts map is
	 * fully populated and new listeners will not receive the initial batch of
	 * {@link ContactListener#onContactAdded} calls.
	 *
	 * @return Unix epoch seconds of the last sync, or {@code null}
	 */
	public Long getLastContactsSync() {
		return lastContactsSync;
	}

	private final List<ContactListener> contactListeners = new CopyOnWriteArrayList<>();

	/**
	 * Registers a listener to be notified when contacts are added, updated, or
	 * removed.
	 *
	 * @param listener the listener to register
	 */
	public void addContactListener(ContactListener listener) {
		contactListeners.add(listener);
	}

	/**
	 * Removes a previously registered contact listener.
	 *
	 * @param listener the listener to remove
	 */
	public void removeContactListener(ContactListener listener) {
		contactListeners.remove(listener);
	}

	private void fireContactAdded(Contact c) {
		for (ContactListener l : contactListeners) {
			try {
				l.onContactAdded(c);
			} catch (Exception ex) {
				log.log(Level.WARNING, "ContactListener.onContactAdded threw", ex);
			}
		}
	}

	private void fireContactUpdated(Contact c) {
		for (ContactListener l : contactListeners) {
			try {
				l.onContactUpdated(c);
			} catch (Exception ex) {
				log.log(Level.WARNING, "ContactListener.onContactUpdated threw", ex);
			}
		}
	}

	private void fireContactRemoved(Contact c) {
		for (ContactListener l : contactListeners) {
			try {
				l.onContactRemoved(c);
			} catch (Exception ex) {
				log.log(Level.WARNING, "ContactListener.onContactRemoved threw", ex);
			}
		}
	}

	/**
	 * Future completed when the current contacts sync finishes
	 * (RESP_END_OF_CONTACTS).
	 */
	private volatile CompletableFuture<Void> contactsSyncFuture = null;

	/**
	 * Block until the current contacts sync completes. Must NOT be called from the
	 * eventExecutor thread (e.g. from inside a frame listener) — doing so will
	 * deadlock. Typically called right after {@link #syncContacts} from application
	 * code.
	 *
	 * @param timeoutMs maximum wait in milliseconds
	 * @throws TimeoutException     if sync did not complete in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void awaitContactsSync(long timeoutMs) throws TimeoutException, InterruptedException {
		CompletableFuture<Void> f = contactsSyncFuture;
		if (f == null || f.isDone())
			return;
		try {
			f.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (ExecutionException ignored) {
		} catch (java.util.concurrent.TimeoutException e) {
			throw new TimeoutException("Contacts sync timed out after " + timeoutMs + "ms");
		}
	}

	Long lastContactsSync = null;
	boolean contactsSyncInstalled = false;

	private void installSyncContacts() {
		if (contactsSyncInstalled)
			return;
		contactsSyncInstalled = true;
		FrameListener<ContactFrameGroup> contactsListener = new FrameListener<ContactFrameGroup>() {

			long expectedCount = 0;
			boolean pendingFullResync = false;

			@Override
			public void onFrame(ContactFrameGroup frame) {
				switch (frame.getFrameType()) {
				case RESP_CONTACT: {
					Contact c = (Contact) frame;
					String pubkey = MeshcoreUtils.hex(c.getPubkey());
					Map<String, Contact> target = pendingContacts != null ? pendingContacts : contacts;
					Contact prev = target.put(pubkey, c);
					contactsArchive.remove(pubkey);
					if (prev == null)
						fireContactAdded(c);
					else
						fireContactUpdated(c);
				}
					break;
				case PUSH_CONTACT_DELETED: {
					ContactDeletedPush d = (ContactDeletedPush) frame;
					String pubkey = MeshcoreUtils.hex(d.getPubkey());
					if (pendingContacts != null)
						pendingContacts.remove(pubkey);
					Contact c = contacts != null ? contacts.remove(pubkey) : null;
					if (c != null) {
						c.saved = false;
						contactsArchive.put(pubkey, c);
						fireContactRemoved(c);
					} else {
						log.warning(String.format("Removed contact %s not found!", pubkey));
					}
				}
					break;
				case PUSH_CONTACTS_FULL:
					// Defer resync to after the current sync ends; if we are not inside
					// a sync there is no upcoming RESP_END_OF_CONTACTS so trigger now.
					pendingFullResync = true;
					if (contactsSyncFuture == null || contactsSyncFuture.isDone()) {
						pendingFullResync = false;
						try {
							syncContacts(false);
						} catch (IOException e) {
							log.log(Level.SEVERE, "Failed to resync after PUSH_CONTACTS_FULL", e);
						}
					}
					break;
				case RESP_END_OF_CONTACTS: {
					EndOfContacts eoc = (EndOfContacts) frame;

					Map<String, Contact> synced = pendingContacts != null ? pendingContacts : contacts;
					int syncedSize = synced != null ? synced.size() : 0;
					boolean mismatch = syncedSize != expectedCount;
					if (mismatch)
						log.warning(
								String.format("Contacts count mismatch (expected %d, got %d) — triggering full resync",
										expectedCount, syncedSize));

					if (mismatch || pendingFullResync) {
						pendingFullResync = false;
						lastContactsSync = null;
						pendingContacts = null;
						// syncContacts(true) creates a new contactsSyncFuture; let that
						// complete it so awaitContactsSync callers keep waiting correctly.
						try {
							syncContacts(true);
						} catch (IOException e) {
							log.log(Level.SEVERE, "Failed to resync after contacts count mismatch", e);
							CompletableFuture<Void> f = contactsSyncFuture;
							if (f != null)
								f.complete(null);
						}
					} else {
						// Atomic commit: replace the authoritative map and timestamp together
						if (pendingContacts != null) {
							contacts = pendingContacts;
							pendingContacts = null;
						}
						if (eoc.getLastUpdated() > 0)
							lastContactsSync = eoc.getLastUpdated();
						CompletableFuture<Void> f = contactsSyncFuture;
						if (f != null)
							f.complete(null);
					}
				}
					break;
				case RESP_CONTACTS_START: {
					expectedCount = ((ContactsStart) frame).getContactsCount();
				}
					break;
				case PUSH_PATH_UPDATED:
					refetchContact(((PathUpdatedPush) frame).getPubkey());
					break;
				case PUSH_ADVERT:
					refetchContact(((AdvertPush) frame).getPubkey());
					break;
				case PUSH_NEW_ADVERT: {
					// Contact discovered over the air; stored in archive for packet analysis only
					Contact newC = new Contact(companion, frame.getBytes().clone());
					contactsArchive.put(MeshcoreUtils.hex(newC.getPubkey()), newC);
				}
					break;
				default:
					break;
				}
			}
		};

		companion.registerFrameListener(ContactFrameGroup.class, contactsListener);
	}

	private void refetchContact(byte[] pubkey) {
		try {
			ResponseFrame resp = companion.sendFrameWithResult(new CmdGetContactByKey(pubkey), 2000L);
			if (resp instanceof Contact) {
				Contact c = (Contact) resp;
				String key = MeshcoreUtils.hex(c.getPubkey());
				Map<String, Contact> target = pendingContacts != null ? pendingContacts : contacts;
				if (target != null) {
					Contact prev = target.put(key, c);
					contactsArchive.remove(key);
					if (prev == null)
						fireContactAdded(c);
					else
						fireContactUpdated(c);
				}
			} else if (resp instanceof Error) {
				log.severe(String.format("Contact refetch error for %s: %s", MeshcoreUtils.hex(pubkey),
						((Error) resp).getCode()));
			}
		} catch (IOException | TimeoutException | InterruptedException e) {
			log.log(Level.SEVERE, "Contact refetch exception", e);
		}
	}

	/**
	 * Requests a contact list synchronisation from the device.
	 *
	 * <p>
	 * When {@code full} is {@code true} or no previous sync has completed, fetches
	 * all contacts from scratch. Otherwise performs an incremental sync for
	 * contacts changed since the last sync timestamp. Use
	 * {@link #awaitContactsSync(long)} to block until the sync completes.
	 * </p>
	 *
	 * @param full {@code true} to force a full resync, {@code false} for
	 *             incremental
	 * @throws IOException on transport error
	 */
	public void syncContacts(boolean full) throws IOException {
		installSyncContacts();
		if (full || lastContactsSync == null) {
			lastContactsSync = null;
			pendingContacts = new ConcurrentHashMap<>();
		}
		contactsSyncFuture = new CompletableFuture<>();
		companion.sendFrame(new CmdGetContacts(lastContactsSync));
	}

	/**
	 * Find a saved contact by exact name match.
	 *
	 * @param name contact name to look up
	 * @return matching contact, or {@code null} if not found
	 */
	public Contact getContact(String name) {
		if (name == null || name.length() == 0 || contacts == null)
			return null;
		for (Contact c : contacts.values()) {
			if (name.equals(c.getName()))
				return c;
		}
		return null;
	}

	/**
	 * Find a contact by public key prefix. Searches saved contacts first, then the
	 * archive.
	 *
	 * @param pubkey public key prefix (1–32 bytes) to match
	 * @return the unique matching contact, or {@code null} if not found or if more
	 *         than one contact matches
	 */
	public Contact getContact(byte[] pubkey) {
		if (pubkey == null || pubkey.length == 0 || contacts == null)
			return null;
		Contact first = null;
		for (Contact c : contacts.values()) {
			if (MeshcoreUtils.isPrefix(pubkey, c.getPubkey())) {
				if (first == null) {
					first = c;
				} else {
					return null;
				}
			}
		}
		if (first != null)
			return first;
		for (Contact c : contactsArchive.values()) {
			if (MeshcoreUtils.isPrefix(pubkey, c.getPubkey())) {
				if (first == null) {
					first = c;
				} else {
					return null;
				}
			}
		}
		if (first != null)
			return first;
		return null;
	}

	/**
	 * Find all contacts whose public key starts with {@code pubkey}, optionally
	 * filtered by advert type. Searches both saved contacts and the archive.
	 *
	 * @param pubkey public key prefix to match (1–32 bytes)
	 * @param type   required advert type, or {@code null} to match all types
	 * @return list of matching contacts (never {@code null}, but may be empty)
	 */
	public List<Contact> findContacts(byte[] pubkey, FrameConstants.AdvertType type) {
		if (pubkey == null || pubkey.length == 0)
			return null;
		List<Contact> result = new ArrayList<>();
		if (contacts != null)
			for (Contact c : contacts.values()) {
				if (MeshcoreUtils.isPrefix(pubkey, c.getPubkey()) && (type == null || c.getType() == type))
					result.add(c);
			}
		for (Contact c : contactsArchive.values()) {
			if (MeshcoreUtils.isPrefix(pubkey, c.getPubkey()) && (type == null || c.getType() == type))
				result.add(c);
		}
		return result;
	}

	private Map<Integer, ChannelInfo> channels = new HashMap<>();

	/**
	 * Fetches all channel slots from the device and updates the local channel
	 * cache. Only slots with a non-empty name are retained.
	 *
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void syncChannels() throws IOException, TimeoutException, InterruptedException {
		channels = new HashMap<>();
		for (int i = 0; i < getMaxGroupChannels(); i++) {
			ResponseFrame resp = companion.sendFrameWithResult(new CmdGetChannel(i), defaultGetTimeout);
			if (resp instanceof ChannelInfo) {
				ChannelInfo chI = (ChannelInfo) resp;
				if (chI.getName().length() > 0) {
					channels.put(i, chI);
				}
			} else {
				throw new CompanionErrorException(resp.toString());
			}
		}
	}

	/**
	 * Re-fetches a single channel slot from the device and updates the local cache.
	 *
	 * @param id the channel slot index to refresh
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void syncChannel(int id) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdGetChannel(id), defaultGetTimeout);
		if (resp instanceof ChannelInfo) {
			ChannelInfo chI = (ChannelInfo) resp;
			if (chI.getName().length() > 0) {
				channels.put(id, chI);
			}
		} else {
			throw new CompanionErrorException(resp.toString());
		}
	}

	/**
	 * Set (or clear) a channel slot on the device.
	 *
	 * @param id   slot index (0-based)
	 * @param name channel name, or {@code null} to remove the channel (zeroes the
	 *             key)
	 * @param key  16-byte AES channel key
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void setChannel(int id, String name, byte[] key) throws IOException, TimeoutException, InterruptedException {
		if (name != null) {
			ChannelInfo existing = getChannel(name);
			if ((existing != null) && (id != existing.getId()))
				throw new CompanionErrorException(
						String.format("Creating a channel with already existing name %d:%s, but different ID (%d)!",
								existing.getId(), name, id));
		}
		CmdSetChannel cmd = new CmdSetChannel(id, name, (name != null ? key : new byte[16]));
		// the device persists the channel to flash before answering - over BLE that has
		// been observed to take ~1.2 s
		ResponseFrame cmdResp = companion.sendFrameWithResult(cmd, 5000);
		if (cmdResp instanceof Ok) {
			ResponseFrame resp = companion.sendFrameWithResult(new CmdGetChannel(id), defaultGetTimeout);
			if (resp instanceof ChannelInfo) {
				ChannelInfo chI = (ChannelInfo) resp;
				if (chI.getName().length() > 0) {
					channels.put(id, chI);
				} else {
					channels.remove(id);
				}
			}
		} else {
			throw new CompanionErrorException(
					String.format("Failed to set group channel %d=%s: %s", id, name, ((Error) cmdResp).getCode()));
		}
	}

	/**
	 * Add a channel to a free slot, or update an existing slot with the same name
	 * if the key differs.
	 *
	 * @param name channel name
	 * @param key  16-byte AES channel key
	 * @return the slot index that was written
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public int setChannel(String name, byte[] key) throws IOException, TimeoutException, InterruptedException {
		syncChannels();
		Integer idxToSet = null;
		ChannelInfo chI = getChannel(name);

		if (chI != null)
			idxToSet = chI.getId();
		else {
			int i = 1;
			while (channels.containsKey(i))
				i++;
			if (i < getMaxGroupChannels())
				idxToSet = i;
		}

		if (idxToSet != null) {
			if (chI == null || !Arrays.equals(chI.getPubkey(), key)) {
				setChannel(idxToSet, name, key);
				syncChannel(idxToSet);
			}
			return idxToSet;
		} else {
			throw new CompanionErrorException("No slot available");
		}
	}

	/**
	 * Convenience overload of {@link #setChannel(int, String, byte[])} that accepts
	 * the key as a hex string.
	 *
	 * @param id     slot index (0-based)
	 * @param name   channel name, or {@code null} to remove the channel
	 * @param keyHex 16-byte AES channel key as a 32-character hex string
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setChannel(int id, String name, String keyHex)
			throws IOException, TimeoutException, InterruptedException {
		setChannel(id, name, MeshcoreUtils.fromHex(keyHex));
	}

	/**
	 * Convenience overload of {@link #setChannel(String, byte[])} that accepts the
	 * key as a hex string.
	 *
	 * @param name   channel name
	 * @param keyHex 16-byte AES channel key as a 32-character hex string
	 * @return the slot index that was written
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public int setChannel(String name, String keyHex) throws IOException, TimeoutException, InterruptedException {
		return setChannel(name, MeshcoreUtils.fromHex(keyHex));
	}

	/**
	 * Returns the cached channel for the given slot index, or {@code null} if the
	 * slot is empty or has not been synced yet.
	 *
	 * @param id slot index (0-based)
	 * @return the {@link ChannelInfo}, or {@code null}
	 */
	public ChannelInfo getChannel(int id) {
		return channels.get(id);
	}

	/**
	 * Returns an unmodifiable view of all currently cached (non-empty) channels.
	 *
	 * @return collection of channel info objects
	 */
	public Collection<ChannelInfo> getChannels() {
		return Collections.unmodifiableCollection(channels.values());
	}

	/**
	 * Returns the cached channel with the given name, or {@code null} if not found.
	 *
	 * @param name channel name to look up
	 * @return the matching {@link ChannelInfo}, or {@code null}
	 */
	public ChannelInfo getChannel(String name) {
		if (name == null || name.length() == 0)
			return null;
		for (ChannelInfo c : channels.values()) {
			if (name.equals(c.getName()))
				return c;
		}
		return null;
	}

	/**
	 * Returns the {@link DeviceInfo} fetched during device handshake.
	 *
	 * @return the device info, or {@code null} if the handshake has not completed
	 */
	public DeviceInfo getDeviceInfo() {
		return companion.getDeviceInfo();
	}

	/**
	 * Returns the {@link SelfInfo} fetched during device handshake.
	 *
	 * @return the self info, or {@code null} if the handshake has not completed
	 */
	public SelfInfo getSelfInfo() {
		return companion.getSelfInfo();
	}

	/**
	 * Returns the companion protocol version reported by the device.
	 *
	 * @return protocol version number
	 */
	public int getProtocolVersion() {
		return getDeviceInfo().getProtocolVersion();
	}

	/**
	 * Returns the maximum number of group channels supported by the device.
	 *
	 * @return maximum group channel count
	 */
	public int getMaxGroupChannels() {
		return getDeviceInfo().getMaxGroupChannels();
	}

	/**
	 * Returns the maximum number of contacts the device can store.
	 *
	 * @return maximum contact count
	 */
	public int getMaxContacts() {
		return getDeviceInfo().getMaxContacts();
	}

	private final long defaultGetTimeout = 2000;

	private Optional<PrivateKey> privateKey = null;

	/**
	 * Fetch the node's 64-byte Ed25519 private key from the device and cache it.
	 *
	 * @return 64-byte private key
	 * @throws UnsupportedOperationException when private key export is disabled in
	 *                                       device firmware
	 */
	public byte[] getPrivateKey() {
		if (privateKey != null) {
			if (privateKey.isPresent())
				return privateKey.get().getPrivateKey();
			else
				return null;
		}
		ResponseFrame resp;
		try {
			resp = companion.sendFrameWithResult(new CmdExportPrivateKey(), defaultGetTimeout);
			if (resp instanceof PrivateKey) {
				privateKey = Optional.of((PrivateKey) resp);
				return ((PrivateKey) resp).getPrivateKey();
			} else {
				privateKey = Optional.empty();
				throw new UnsupportedOperationException("Fetching of private key is DISABLED!");
			}
		} catch (IOException | TimeoutException | InterruptedException e) {
			log.log(Level.SEVERE, "Failed to get private key", e);
			return null;
		}
	}

	/**
	 * Import a new 64-byte Ed25519 private key to the device. The device reloads
	 * its identity and contacts live — no reboot required.
	 *
	 * @param key 64-byte Ed25519 private key
	 * @throws UnsupportedOperationException when private key import is disabled in
	 *                                       device firmware
	 * @throws IOException                   if the transport fails
	 * @throws TimeoutException              if the device does not respond in time
	 * @throws InterruptedException          if the calling thread is interrupted
	 *                                       while waiting
	 */
	public void importPrivateKey(byte[] key) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdImportPrivateKey(key), defaultGetTimeout);
		if (resp instanceof Error) {
			throw new UnsupportedOperationException("Private key import is disabled on the device.");
		}
		privateKey = null;
	}

	private AutoaddConfig autoaddConfig = null;

	/**
	 * Returns the auto-add configuration, fetching it from the device on first call
	 * and caching thereafter.
	 *
	 * @return the autoadd configuration
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public AutoaddConfig getAutoaddConfig() throws IOException, TimeoutException, InterruptedException {
		if (autoaddConfig != null)
			return autoaddConfig;

		autoaddConfig = (AutoaddConfig) companion.sendFrameWithResult(new CmdGetAutoaddConfig(), defaultGetTimeout);
		return autoaddConfig;
	}

	private CustomVars customVars = null;

	/**
	 * Returns the device custom variables, fetching them from the device on first
	 * call and caching thereafter.
	 *
	 * @return the custom variables map
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public CustomVars getCustomVars() throws IOException, TimeoutException, InterruptedException {
		if (customVars != null)
			return customVars;

		customVars = (CustomVars) companion.sendFrameWithResult(new CmdGetCustomVars(), defaultGetTimeout);
		return customVars;
	}

	private TuningParams tuningParams = null;

	/**
	 * Returns the device tuning parameters, fetching them from the device on first
	 * call and caching thereafter.
	 *
	 * @return the tuning parameters
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public TuningParams getTuningParams() throws IOException, TimeoutException, InterruptedException {
		if (tuningParams != null)
			return tuningParams;

		tuningParams = (TuningParams) companion.sendFrameWithResult(new CmdGetTuningParams(), defaultGetTimeout);
		return tuningParams;
	}

	/**
	 * Imports a contact onto the device from a serialised advert packet.
	 *
	 * @param advertPacket serialised advert packet (minimum 98 bytes), as produced
	 *                     by {@link #exportContact(byte[])}
	 * @throws CompanionErrorException if the device rejects the packet
	 * @throws IOException             on transport error
	 * @throws TimeoutException        if no response arrives in time
	 * @throws InterruptedException    if the calling thread is interrupted
	 */
	public void importContact(byte[] advertPacket) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdImportContact(advertPacket), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
	}

	/**
	 * Exports a contact's serialised advert packet from the device.
	 *
	 * @param pubkey 32-byte Ed25519 public key of the contact to export
	 * @return the serialised advert packet bytes
	 * @throws CompanionErrorException if the contact is not found
	 * @throws IOException             on transport error
	 * @throws TimeoutException        if no response arrives in time
	 * @throws InterruptedException    if the calling thread is interrupted
	 */
	public byte[] exportContact(byte[] pubkey) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdExportContact(pubkey), defaultGetTimeout);
		if (resp instanceof ExportContact)
			return ((ExportContact) resp).getAdvertPacket();
		else
			throw new CompanionErrorException(resp.toString());

	}

	/**
	 * Remove contact from device and main contacts, keep it only in archived
	 * contacts
	 *
	 * @param pubkey 32B
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void removeContact(byte[] pubkey) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdRemoveContact(pubkey), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		String key = MeshcoreUtils.hex(pubkey);
		Contact c = contacts.remove(key);
		if (c != null) {
			contactsArchive.put(key, c);
		}
	}

	/**
	 * Clears the cached outbound path to the given contact, forcing the device to
	 * use flood routing for the next message, then re-fetches the contact record to
	 * reflect the updated path state.
	 *
	 * @param pubkey 32-byte Ed25519 public key of the contact
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void resetPath(byte[] pubkey) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdResetPath(pubkey), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		refetchContact(pubkey);
	}

	/**
	 * Sets the advertised GPS coordinates of the device and refreshes the cached
	 * {@link cz.bliksoft.meshcore.frames.resp.SelfInfo}.
	 *
	 * @param lat latitude in decimal degrees
	 * @param lon longitude in decimal degrees
	 * @param alt altitude in metres, or {@code null} if not supported by firmware
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setAdvertLatLon(double lat, double lon, Integer alt)
			throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetAdvertLatLon(lat, lon, alt), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		companion.refreshSelfInfo();
	}

	/**
	 * Configure auto-add behaviour. The per-type bits in {@code flags} only take
	 * effect when {@code manualAddContacts} is enabled (set via
	 * {@link #setOtherParams}).
	 *
	 * @param flags   bitmask of {@link AutoAddConfigFlags} values
	 * @param maxHops maximum hop count for auto-add (0–64); pass -1 for unlimited
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void setAutoAddConfig(byte flags, int maxHops) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetAutoaddConfig(flags, maxHops), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		autoaddConfig = null;
	}

	/**
	 * Sets or updates a custom variable on the device and invalidates the local
	 * cache.
	 *
	 * @param name  variable name
	 * @param value variable value
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setCustomVar(String name, String value) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetCustomVar(name, value), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		customVars = null;
	}

	/**
	 * Sets the device BLE PIN. Pass {@code null} to remove the PIN. The device info
	 * is refreshed after the change.
	 *
	 * @param pin the desired PIN, or {@code null} to auto-generate / clear
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setDevicePIN(Long pin) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetDevicePin(pin == null ? 0l : pin),
				defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		companion.refreshDeviceInfo();
	}

	/**
	 * Update device other-params (telemetry modes, contact add policy, etc.). Pass
	 * {@code null} for any parameter to keep the current device value.
	 *
	 * @param manualAddContacts require explicit contact approval; {@code false} =
	 *                          auto-add all
	 * @param telemetryBaseAll  allow base telemetry from all contacts
	 * @param telemetryBaseFav  allow base telemetry from favourites only
	 * @param telemetryLocAll   allow location telemetry from all contacts
	 * @param telemetryLocFav   allow location telemetry from favourites only
	 * @param telemetryEnvAll   allow environment telemetry from all contacts
	 * @param telemetryEnvFav   allow environment telemetry from favourites only
	 * @param advertLocPolicy   how/when to include GPS coordinates in adverts
	 * @param multiAcks         enable multi-ACK mode (requires protocol v7+)
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void setOtherParams(Boolean manualAddContacts, Boolean telemetryBaseAll, Boolean telemetryBaseFav,
			Boolean telemetryLocAll, Boolean telemetryLocFav, Boolean telemetryEnvAll, Boolean telemetryEnvFav,
			AdvertLocPolicy advertLocPolicy, Boolean multiAcks)
			throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetOtherParams(
				manualAddContacts == null ? getSelfInfo().isManualAddContacts() : manualAddContacts,
				telemetryBaseAll == null ? getSelfInfo().isTelemetryModeBaseEn() : telemetryBaseAll,
				telemetryBaseFav == null ? getSelfInfo().isTelemetryModeBaseFav() : telemetryBaseFav,
				telemetryLocAll == null ? getSelfInfo().isTelemetryModeLocEn() : telemetryLocAll,
				telemetryLocFav == null ? getSelfInfo().isTelemetryModeLocFav() : telemetryLocFav,
				telemetryEnvAll == null ? getSelfInfo().isTelemetryModeEnvEn() : telemetryEnvAll,
				telemetryEnvFav == null ? getSelfInfo().isTelemetryModeEnvFav() : telemetryEnvFav,
				advertLocPolicy == null ? getSelfInfo().getAdvertLocPolicy() : advertLocPolicy,
				multiAcks == null ? getSelfInfo().getMultiAcks() : multiAcks), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		companion.refreshSelfInfo();
	}

	/**
	 * Sets the flood path-hash mode on the device (firmware v10+) and refreshes the
	 * cached device info.
	 *
	 * @param mode path-hash mode 0–2, corresponding to 1–3 hash bytes per hop (see
	 *             {@link cz.bliksoft.meshcore.frames.cmd.CmdSetPathHashMode})
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setPathHashMode(byte mode) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetPathHashMode(mode), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		companion.refreshDeviceInfo();
	}

	/**
	 * Configures the LoRa radio parameters and refreshes the cached device info.
	 *
	 * @param freq   frequency in Hz (converted to kHz before sending)
	 * @param bw     bandwidth in Hz
	 * @param sf     spreading factor
	 * @param cr     coding rate
	 * @param repeat {@code true} to configure the device as a repeater
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setRadioParams(long freq, long bw, byte sf, byte cr, boolean repeat)
			throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetRadioParams(freq, bw, sf, cr, repeat),
				defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		companion.refreshDeviceInfo();
	}

	/**
	 * Sets the LoRa TX power and refreshes the cached self-info.
	 *
	 * @param power TX power in dBm (valid range: -9 to
	 *              {@link cz.bliksoft.meshcore.frames.resp.SelfInfo#getMaxLoraPowerDbm()})
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setRadioTxPower(byte power) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetRadioTXPower(power), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		companion.refreshSelfInfo();
	}

	/**
	 * Sets the receiver delay base and airtime estimation factor on the device and
	 * invalidates the local tuning-params cache.
	 *
	 * @param rxDelayBase   base RX window delay multiplier
	 * @param airtimeFactor airtime estimation factor
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setTuningParams(double rxDelayBase, double airtimeFactor)
			throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetTuningParams(rxDelayBase, airtimeFactor),
				defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		tuningParams = null;
	}

	/**
	 * Queries the device for current battery voltage and storage statistics.
	 *
	 * @return battery and storage info
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public BattAndStorage getBattAndStorage() throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdGetBattAndStorage(), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		return (BattAndStorage) resp;
	}

	/**
	 * Queries the device's current clock time.
	 *
	 * @return the device's current time as Unix epoch seconds
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public CurrTime getDeviceTime() throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdGetDeviceTime(), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		return (CurrTime) resp;
	}

	/**
	 * Set device time. Firmware rejects timestamps older than the current device
	 * time.
	 *
	 * @param timestamp Unix epoch seconds, or {@code null} to use current system
	 *                  time
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void setDeviceTime(Long timestamp) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetDeviceTime(timestamp), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
	}

	/**
	 * Sets the device's advertised name and refreshes the cached self-info.
	 *
	 * @param name the new device name
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setAdvertName(String name) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetAdvertName(name), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		companion.refreshSelfInfo();
	}

	/**
	 * Sets the active flood-scope transport key for the current session. Pass
	 * {@code null} to clear (revert to global scope).
	 *
	 * @param scope 16-byte AES transport key, or {@code null} to clear
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setFloodScope(byte[] scope) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetFloodScope(scope), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
	}

	/**
	 * Forces flood-routed packets to be sent completely unscoped (protocol v12+),
	 * overriding even the persisted default scope. This is distinct from
	 * {@link #setFloodScope(byte[]) setFloodScope(null)}, which merely clears the
	 * override and falls back to the persisted default scope.
	 *
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setFloodScopeUnscoped() throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(CmdSetFloodScope.unscoped(), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
	}

	/**
	 * Sets the persisted default flood scope on the device. Pass {@code null} for
	 * both arguments to clear the default scope.
	 *
	 * @param scopeName  scope name (max 31 chars), or {@code null} to clear
	 * @param scopeKey16 16-byte AES transport key, or {@code null} to clear
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public void setDefaultFloodScope(String scopeName, byte[] scopeKey16)
			throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdSetDefaultFloodScope(scopeName, scopeKey16),
				defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
	}

	/**
	 * Returns the persisted default flood scope. Both fields of the returned object
	 * are {@code null} when no default scope is configured.
	 *
	 * @return the default flood scope configuration
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public DefaultFloodScope getDefaultFloodScope() throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdGetDefaultFloodScope(), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		return (DefaultFloodScope) resp;
	}

	/**
	 * Queries device statistics for the given subtype.
	 *
	 * @param subtype the statistics subtype to request
	 * @return the statistics response
	 * @throws IOException          on transport error
	 * @throws TimeoutException     if no response arrives in time
	 * @throws InterruptedException if the calling thread is interrupted
	 */
	public Stats getStats(StatsCommandFrameSubtype subtype) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdGetStats(subtype), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		return (Stats) resp;
	}

	/**
	 * Add or update a saved contact on the device.
	 *
	 * @param contact the contact to add or update
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void addUpdateContact(Contact contact) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(contact.getCmdAddUpdateContact(), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
	}

	/**
	 * Broadcast the contact's advert packet zero-hop so nearby nodes learn about
	 * them.
	 *
	 * @param pubkey 32-byte public key of the contact to share
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void shareContact(byte[] pubkey) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdShareContact(pubkey), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
	}

	/**
	 * Fetch the stored outbound advert path for a contact.
	 *
	 * @param pubkey 32-byte public key
	 * @return the stored advert path, or throws if not found
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public AdvertPath getAdvertPath(byte[] pubkey) throws IOException, TimeoutException, InterruptedException {
		ResponseFrame resp = companion.sendFrameWithResult(new CmdGetAdvertPath(pubkey), defaultGetTimeout);
		if (resp instanceof Error)
			throw new CompanionErrorException(resp.toString());
		return (AdvertPath) resp;
	}

	// -------------------- Backup / Restore --------------------

	private static final String DEFAULT_DEVICE_PREFIX = "device";

	/**
	 * Backup full device configuration (excluding channels and contacts) into a
	 * {@link Properties} object. Keys use the {@code device.*} namespace.
	 *
	 * @param props target properties (written to, not cleared first)
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void deviceBackup(Properties props) throws IOException, TimeoutException, InterruptedException {
		deviceBackup(props, DEFAULT_DEVICE_PREFIX);
	}

	/**
	 * Backup full device configuration (excluding channels and contacts) into a
	 * {@link Properties} object. Keys use the {@code <prefix>.*} namespace.
	 *
	 * @param props  target properties (written to, not cleared first)
	 * @param prefix key prefix (e.g. {@code "device"} produces keys like
	 *               {@code device.name})
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void deviceBackup(Properties props, String prefix)
			throws IOException, TimeoutException, InterruptedException {
		SelfInfo si = getSelfInfo();
		DeviceInfo di = getDeviceInfo();
		AutoaddConfig ac = getAutoaddConfig();
		TuningParams tp = getTuningParams();
		CustomVars cv = getCustomVars();

		final String p = prefix + ".";
		final String r = p + "radio.";
		final String a = p + "advert.";
		final String t = p + "telemetry.";

		// advert / identity
		props.setProperty(a + "name", si.getNodeName());
		props.setProperty(a + "locPolicy", si.getAdvertLocPolicy().name());
		props.setProperty(a + "lat", Double.toString(si.getLat()));
		props.setProperty(a + "lon", Double.toString(si.getLon()));

		// radio params
		props.setProperty(r + "freq", Long.toString(si.getFreq()));
		props.setProperty(r + "bw", Long.toString(si.getBw()));
		props.setProperty(r + "sf", Integer.toString(si.getSf()));
		props.setProperty(r + "cr", Integer.toString(si.getCr()));
		props.setProperty(r + "clientRepeat", Boolean.toString(di.isClientRepeat()));
		props.setProperty(r + "txPower", Integer.toString(si.getTxPowerDbm()));
		props.setProperty(r + "pathHashMode", Integer.toString(di.getPathHashMode()));

		// telemetry modes
		props.setProperty(t + "baseEn", Boolean.toString(si.isTelemetryModeBaseEn()));
		props.setProperty(t + "baseFav", Boolean.toString(si.isTelemetryModeBaseFav()));
		props.setProperty(t + "locEn", Boolean.toString(si.isTelemetryModeLocEn()));
		props.setProperty(t + "locFav", Boolean.toString(si.isTelemetryModeLocFav()));
		props.setProperty(t + "envEn", Boolean.toString(si.isTelemetryModeEnvEn()));
		props.setProperty(t + "envFav", Boolean.toString(si.isTelemetryModeEnvFav()));

		// contact / message behaviour
		props.setProperty(p + "manualAddContacts", Boolean.toString(si.isManualAddContacts()));
		props.setProperty(p + "multiAcks", Boolean.toString(si.getMultiAcks()));

		// autoadd
		props.setProperty(p + "autoaddConfig", Byte.toString(ac.getAutoaddConfig()));
		props.setProperty(p + "autoaddMaxHops", Integer.toString(ac.getAutoAddMaxHops()));

		// tuning
		props.setProperty(p + "rxDelayBase", Double.toString(tp.getRxDelayBase()));
		props.setProperty(p + "airtimeFactor", Double.toString(tp.getAirtimeFactor()));

		// PIN (1_000_000 = no PIN in firmware)
		props.setProperty(p + "pin", Long.toString(di.getBlePIN()));

		// private key (skipped silently when export is disabled on device)
		try {
			props.setProperty(p + "privateKey", MeshcoreUtils.hex(getPrivateKey()));
		} catch (UnsupportedOperationException e) {
			log.warning("Private key export is disabled on device — skipping backup of private key");
		}

		// custom vars
		Map<String, String> vars = cv.getVariables();
		props.setProperty(p + "customVars.count", Integer.toString(vars.size()));
		int idx = 0;
		for (Map.Entry<String, String> e : vars.entrySet()) {
			props.setProperty(p + "customVar." + idx + ".name", e.getKey());
			props.setProperty(p + "customVar." + idx + ".value", e.getValue());
			idx++;
		}
	}

	/**
	 * Restore full device configuration from a {@link Properties} object produced
	 * by {@link #deviceBackup(Properties)}.
	 *
	 * @param props source properties
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void deviceRestore(Properties props) throws IOException, TimeoutException, InterruptedException {
		deviceRestore(props, DEFAULT_DEVICE_PREFIX);
	}

	/**
	 * Restore full device configuration from a {@link Properties} object produced
	 * by {@link #deviceBackup(Properties, String)}.
	 *
	 * @param props  source properties
	 * @param prefix key prefix used during backup (e.g. {@code "device"})
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void deviceRestore(Properties props, String prefix)
			throws IOException, TimeoutException, InterruptedException {
		final String p = prefix + ".";
		final String r = p + "radio.";
		final String a = p + "advert.";
		final String t = p + "telemetry.";

		// advert / identity
		String name = props.getProperty(a + "name");
		if (name != null)
			setAdvertName(name);

		String latStr = props.getProperty(a + "lat");
		String lonStr = props.getProperty(a + "lon");
		if (latStr != null && lonStr != null)
			setAdvertLatLon(Double.parseDouble(latStr), Double.parseDouble(lonStr), null);

		// radio params
		String freq = props.getProperty(r + "freq");
		String bw = props.getProperty(r + "bw");
		String sf = props.getProperty(r + "sf");
		String cr = props.getProperty(r + "cr");
		String repeat = props.getProperty(r + "clientRepeat");
		if (freq != null && bw != null && sf != null && cr != null && repeat != null)
			setRadioParams(Long.parseLong(freq), Long.parseLong(bw), Byte.parseByte(sf), Byte.parseByte(cr),
					Boolean.parseBoolean(repeat));

		String txPower = props.getProperty(r + "txPower");
		if (txPower != null)
			setRadioTxPower(Byte.parseByte(txPower));

		String pathHashMode = props.getProperty(r + "pathHashMode");
		if (pathHashMode != null)
			setPathHashMode(Byte.parseByte(pathHashMode));

		// other params (manualAddContacts, telemetry, advertLocPolicy, multiAcks)
		String manualAdd = props.getProperty(p + "manualAddContacts");
		String telBaseEn = props.getProperty(t + "baseEn");
		String telBaseFav = props.getProperty(t + "baseFav");
		String telLocEn = props.getProperty(t + "locEn");
		String telLocFav = props.getProperty(t + "locFav");
		String telEnvEn = props.getProperty(t + "envEn");
		String telEnvFav = props.getProperty(t + "envFav");
		String advertLocPolicyStr = props.getProperty(a + "locPolicy");
		String multiAcksStr = props.getProperty(p + "multiAcks");
		setOtherParams(manualAdd != null ? Boolean.parseBoolean(manualAdd) : null,
				telBaseEn != null ? Boolean.parseBoolean(telBaseEn) : null,
				telBaseFav != null ? Boolean.parseBoolean(telBaseFav) : null,
				telLocEn != null ? Boolean.parseBoolean(telLocEn) : null,
				telLocFav != null ? Boolean.parseBoolean(telLocFav) : null,
				telEnvEn != null ? Boolean.parseBoolean(telEnvEn) : null,
				telEnvFav != null ? Boolean.parseBoolean(telEnvFav) : null,
				advertLocPolicyStr != null ? AdvertLocPolicy.valueOf(advertLocPolicyStr) : null,
				multiAcksStr != null ? Boolean.parseBoolean(multiAcksStr) : null);

		// autoadd
		String autoaddConfigStr = props.getProperty(p + "autoaddConfig");
		String autoaddMaxHopsStr = props.getProperty(p + "autoaddMaxHops");
		if (autoaddConfigStr != null && autoaddMaxHopsStr != null)
			setAutoAddConfig(Byte.parseByte(autoaddConfigStr), Integer.parseInt(autoaddMaxHopsStr));

		// tuning
		String rxDelayBase = props.getProperty(p + "rxDelayBase");
		String airtimeFactor = props.getProperty(p + "airtimeFactor");
		if (rxDelayBase != null && airtimeFactor != null)
			setTuningParams(Double.parseDouble(rxDelayBase), Double.parseDouble(airtimeFactor));

		// private key (skipped silently when import is disabled on device)
		String privateKeyHex = props.getProperty(p + "privateKey");
		if (privateKeyHex != null) {
			try {
				importPrivateKey(MeshcoreUtils.fromHex(privateKeyHex));
			} catch (UnsupportedOperationException e) {
				log.warning("Private key import is disabled on device — skipping restore of private key");
			}
		}

		// PIN
		String pinStr = props.getProperty(p + "pin");
		if (pinStr != null) {
			long pin = Long.parseLong(pinStr);
			// 1_000_000 means no PIN in firmware — pass null to clear
			setDevicePIN(pin >= 1_000_000L ? null : pin);
		}

		// custom vars
		String countStr = props.getProperty(p + "customVars.count");
		if (countStr != null) {
			int count = Integer.parseInt(countStr);
			for (int i = 0; i < count; i++) {
				String varName = props.getProperty(p + "customVar." + i + ".name");
				String varValue = props.getProperty(p + "customVar." + i + ".value");
				if (varName != null && varValue != null)
					setCustomVar(varName, varValue);
			}
		}
	}

	private static final String DEFAULT_CHANNELS_PREFIX = "channel";
	private static final String DEFAULT_CONTACTS_PREFIX = "contact";

	/**
	 * Backup all group channels into a {@link Properties} object. Keys use the
	 * {@code channel.N.*} namespace.
	 *
	 * @param props target properties (written to, not cleared first)
	 */
	public void channelsBackup(Properties props) {
		channelsBackup(props, DEFAULT_CHANNELS_PREFIX);
	}

	/**
	 * Backup all group channels into a {@link Properties} object. Keys use the
	 * {@code <prefix>.N.*} namespace, with {@code <prefix>s.count} as the counter
	 * key.
	 *
	 * @param props  target properties (written to, not cleared first)
	 * @param prefix key prefix (e.g. {@code "channel"} produces keys like
	 *               {@code channel.0.name})
	 */
	public void channelsBackup(Properties props, String prefix) {
		int saved = 0;
		for (ChannelInfo ch : channels.values()) {
			props.setProperty(prefix + "." + saved + ".id", Integer.toString(ch.getId()));
			props.setProperty(prefix + "." + saved + ".name", ch.getName());
			props.setProperty(prefix + "." + saved + ".key", MeshcoreUtils.hex(ch.getPubkey()));
			saved++;
		}
		props.setProperty(prefix + "s.count", Integer.toString(saved));
	}

	/**
	 * Restore group channels from a {@link Properties} object produced by
	 * {@link #channelsBackup(Properties)}.
	 *
	 * @param props source properties
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void channelsRestore(Properties props) throws IOException, TimeoutException, InterruptedException {
		channelsRestore(props, DEFAULT_CHANNELS_PREFIX);
	}

	/**
	 * Restore group channels from a {@link Properties} object produced by
	 * {@link #channelsBackup(Properties, String)}.
	 *
	 * @param props  source properties
	 * @param prefix key prefix used during backup (e.g. {@code "channel"})
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void channelsRestore(Properties props, String prefix)
			throws IOException, TimeoutException, InterruptedException {
		String countStr = props.getProperty(prefix + "s.count");
		if (countStr == null)
			return;
		int count = Integer.parseInt(countStr);
		for (int i = 0; i < count; i++) {
			String idStr = props.getProperty(prefix + "." + i + ".id");
			String chName = props.getProperty(prefix + "." + i + ".name");
			String keyHex = props.getProperty(prefix + "." + i + ".key");
			if (idStr != null && chName != null && keyHex != null)
				setChannel(Integer.parseInt(idStr), chName, MeshcoreUtils.fromHex(keyHex));
		}
	}

	/**
	 * Backup all saved contacts as exported advert-packet blobs into a
	 * {@link Properties} object. Keys use the {@code contact.N.*} namespace.
	 *
	 * @param props target properties (written to, not cleared first)
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void contactsBackup(Properties props) throws IOException, TimeoutException, InterruptedException {
		contactsBackup(props, DEFAULT_CONTACTS_PREFIX);
	}

	/**
	 * Backup all saved contacts as exported advert-packet blobs into a
	 * {@link Properties} object. Keys use the {@code <prefix>.N.*} namespace, with
	 * {@code <prefix>s.count} as the counter key.
	 *
	 * @param props  target properties (written to, not cleared first)
	 * @param prefix key prefix (e.g. {@code "contact"} produces keys like
	 *               {@code contact.0.advertPacket})
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void contactsBackup(Properties props, String prefix)
			throws IOException, TimeoutException, InterruptedException {
		if (contacts == null)
			return;
		int idx = 0;
		for (Contact c : contacts.values()) {
			props.setProperty(prefix + "." + idx + ".contactFrame", MeshcoreUtils.hex(c.getBytes()));
			idx++;
		}
		props.setProperty(prefix + "s.count", Integer.toString(idx));
	}

	/**
	 * Restore contacts from a {@link Properties} object produced by
	 * {@link #contactsBackup(Properties)}. Existing contacts on the device are not
	 * removed before restoring.
	 *
	 * @param props source properties
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void contactsRestore(Properties props) throws IOException, TimeoutException, InterruptedException {
		contactsRestore(props, DEFAULT_CONTACTS_PREFIX);
	}

	/**
	 * Restore contacts from a {@link Properties} object produced by
	 * {@link #contactsBackup(Properties, String)}. Existing contacts on the device
	 * are not removed before restoring.
	 *
	 * @param props  source properties
	 * @param prefix key prefix used during backup (e.g. {@code "contact"})
	 * @throws IOException          if the transport fails
	 * @throws TimeoutException     if the device does not respond in time
	 * @throws InterruptedException if the calling thread is interrupted while
	 *                              waiting
	 */
	public void contactsRestore(Properties props, String prefix)
			throws IOException, TimeoutException, InterruptedException {
		String countStr = props.getProperty(prefix + "s.count");
		if (countStr == null)
			return;
		int count = Integer.parseInt(countStr);
		for (int i = 0; i < count; i++) {
			String contactFrame = props.getProperty(prefix + "." + i + ".contactFrame");
			if (contactFrame != null) {
				addUpdateContact(new Contact(companion, MeshcoreUtils.fromHex(contactFrame)));
			} else {
				String blobHex = props.getProperty(prefix + "." + i + ".advertPacket");
				if (blobHex != null)
					importContact(MeshcoreUtils.fromHex(blobHex));
			}
		}
	}
}
