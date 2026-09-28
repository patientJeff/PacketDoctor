package com.packetdoctor.api;

import java.util.UUID;

/**
 * A snapshot of one online player's connection. "From player" is what their game sends
 * to the server; "to player" is what the server sends them.
 *
 * @param hasClientMod    whether the player's game runs Packet Doctor (and so gets exact explanations)
 * @param worstSeverity   the most serious warning this session ({@code NONE}, {@code INFO}, {@code WARNING}, {@code DANGER})
 * @param joined          epoch milliseconds when Packet Doctor first saw the connection
 */
public record PlayerReport(
		UUID uuid,
		String name,
		boolean hasClientMod,
		double packetsFromPlayerPerSecond,
		double packetsToPlayerPerSecond,
		long packetsFromPlayer,
		long packetsToPlayer,
		long bytesFromPlayer,
		long bytesToPlayer,
		int warningCount,
		String worstSeverity,
		long joined) {
}
