package com.packetdoctor.net;

import com.packetdoctor.Tr;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns protocol ids like {@code minecraft:level_chunk_with_light} into words a player
 * understands ("chunk data"), via {@code packetdoctor.packet.<path>} translations.
 * Unknown ids fall back to their path with spaces.
 */
public final class PacketNames {
	/** One shared string per packet type, so a long log doesn't hold a copy per packet. */
	private static final Map<Identifier, String> IDS = new ConcurrentHashMap<>();

	private PacketNames() {
	}

	public static String id(Packet<?> packet) {
		return IDS.computeIfAbsent(packet.type().id(), Identifier::toString);
	}

	/** Just the path of the id, e.g. {@code level_chunk_with_light}. */
	public static String path(String id) {
		int colon = id.indexOf(':');
		return colon >= 0 ? id.substring(colon + 1) : id;
	}

	/** Plain-language name, e.g. "chunk data". */
	public static String friendly(String id) {
		String path = path(id);
		String key = "packetdoctor.packet." + path.replace('/', '.');
		return Tr.has(key) ? Tr.t(key) : path.replace('_', ' ').replace('/', ' ');
	}

	/** Friendly name followed by the real id, e.g. "chunk data (level_chunk_with_light)". */
	public static String describe(String id) {
		String friendly = friendly(id);
		String path = path(id);
		return friendly.equals(path.replace('_', ' ')) ? path : friendly + " (" + path + ")";
	}

	public static String bytes(long bytes) {
		if (bytes < 0) return "-";
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
		return String.format(Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
	}

	/** A count with thousands separators, e.g. "150,000". */
	public static String count(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}
}
