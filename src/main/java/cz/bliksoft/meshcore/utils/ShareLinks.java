package cz.bliksoft.meshcore.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import cz.bliksoft.meshcore.frames.FrameConstants.AdvertType;
import cz.bliksoft.meshcore.frames.resp.ChannelInfo;
import cz.bliksoft.meshcore.frames.resp.Contact;
import cz.bliksoft.meshcore.frames.resp.SelfInfo;

/**
 * {@code meshcore://} share links, as the official MeshCore apps show them in QR codes:
 * <ul>
 * <li>{@code meshcore://contact/add?name=<name>&public_key=<hex>&type=<advert type>}</li>
 * <li>{@code meshcore://channel/add?name=<name>&secret=<hex>}</li>
 * </ul>
 * Names are URL-encoded (form encoding: space = {@code +}, {@code #} = {@code %23}).
 */
public final class ShareLinks {

	public static final String CONTACT_ADD = "meshcore://contact/add";
	public static final String CHANNEL_ADD = "meshcore://channel/add";

	private ShareLinks() {
	}

	public static String contact(String name, byte[] publicKey, AdvertType type) {
		return CONTACT_ADD + "?name=" + encode(name) + "&public_key=" + MeshcoreUtils.hex(publicKey) + "&type="
				+ (type != null ? type.code() : AdvertType.ADV_TYPE_CHAT.code());
	}

	public static String contact(Contact c) {
		return contact(c.getName(), c.getPubkey(), c.getType());
	}

	/** This device as a contact. */
	public static String contact(SelfInfo si) {
		return contact(si.getNodeName(), si.getPubkey(), si.getAdvertType());
	}

	public static String channel(String name, byte[] secret) {
		return CHANNEL_ADD + "?name=" + encode(name) + "&secret=" + MeshcoreUtils.hex(secret);
	}

	public static String channel(ChannelInfo ch) {
		return channel(ch.getName(), ch.getPubkey());
	}

	private static String encode(String s) {
		try {
			return URLEncoder.encode(s != null ? s : "", "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e); // UTF-8 is always supported
		}
	}
}
