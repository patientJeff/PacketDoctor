package com.packetdoctor.net;

import org.jspecify.annotations.Nullable;

/**
 * A {@link Warning} as plain data (keys and arguments, no rendered text), so it can be
 * sent to the player's game as JSON and shown there in the player's own language.
 */
public record WarningData(
		String key,
		String id,
		String ns,
		String severity,
		@Nullable String direction,
		@Nullable String packetId,
		String adviceRel,
		String[] titleArgs,
		String[] detailArgs,
		String[] adviceArgs,
		@Nullable String sourceKey,
		@Nullable String mod,
		long firstSeen,
		long lastSeen,
		int count) {

	public WarningData withNs(String newNs) {
		return new WarningData(key, id, newNs, severity, direction, packetId, adviceRel, titleArgs, detailArgs, adviceArgs,
				sourceKey, mod, firstSeen, lastSeen, count);
	}

	public Warning toWarning() {
		return Warning.fromData(this);
	}
}
