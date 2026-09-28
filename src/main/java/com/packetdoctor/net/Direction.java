package com.packetdoctor.net;

import com.packetdoctor.Tr;

/** Which way a packet travelled, from the player's point of view. */
public enum Direction {
	IN("packetdoctor.direction.in", "⬇", 0xFF7FD1A0),
	OUT("packetdoctor.direction.out", "⬆", 0xFF7FB2F0);

	private final String key;
	public final String arrow;
	public final int color;

	Direction(String key, String arrow, int color) {
		this.key = key;
		this.arrow = arrow;
		this.color = color;
	}

	/** "from server" / "to server" in the current language. */
	public String description() {
		return Tr.t(key);
	}
}
