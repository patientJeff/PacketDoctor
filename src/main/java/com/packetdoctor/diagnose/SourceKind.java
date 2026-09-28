package com.packetdoctor.diagnose;

import com.packetdoctor.Tr;

/** Where a problem came from, in words a player understands. */
public enum SourceKind {
	YOU("packetdoctor.source_kind.you", 0xFF9BE39B),
	SERVER("packetdoctor.source_kind.server", 0xFFFFB86B),
	NETWORK("packetdoctor.source_kind.network", 0xFF8AB4F8),
	MOD("packetdoctor.source_kind.mod", 0xFFFF8AD8),
	MINECRAFT("packetdoctor.source_kind.minecraft", 0xFFD0D0D0),
	COMPUTER("packetdoctor.source_kind.computer", 0xFFC9A2FF),
	WORLD("packetdoctor.source_kind.world", 0xFFE3D39B),
	UNKNOWN("packetdoctor.source_kind.unknown", 0xFFB0B0B0);

	private final String key;
	public final int color;

	SourceKind(String key, int color) {
		this.key = key;
		this.color = color;
	}

	public String label() {
		return Tr.t(key);
	}
}
