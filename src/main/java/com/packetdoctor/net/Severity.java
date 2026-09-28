package com.packetdoctor.net;

import com.packetdoctor.Tr;

/** How worried the player should be. Colours are ARGB, as the GUI expects. */
public enum Severity {
	INFO("packetdoctor.severity.info", 0xFF8AB4F8),
	WARNING("packetdoctor.severity.warning", 0xFFFFC857),
	DANGER("packetdoctor.severity.danger", 0xFFFF5C5C);

	private final String key;
	public final int color;

	Severity(String key, int color) {
		this.key = key;
		this.color = color;
	}

	/** "Info" / "Warning" / "Danger" in the current language. */
	public String label() {
		return Tr.t(key);
	}

	public boolean atLeast(Severity other) {
		return ordinal() >= other.ordinal();
	}
}
