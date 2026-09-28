package com.packetdoctor.api;

import org.jspecify.annotations.Nullable;

/**
 * A problem Packet Doctor noticed about a player's connection, already worded for admins.
 *
 * @param id        stable identifier of the kind of problem, e.g. {@code out.move.invalid};
 *                  safe to switch on
 * @param severity  {@code INFO}, {@code WARNING} or {@code DANGER}
 * @param title     short headline
 * @param detail    what was seen (numbers, packet names)
 * @param advice    what it means and what to do about it
 * @param source    who is behind it, if known
 * @param packet    friendly name of the packet type involved, if any
 * @param direction {@code TO_PLAYER} or {@code FROM_PLAYER}, if about packets
 * @param firstSeen epoch milliseconds
 * @param lastSeen  epoch milliseconds
 * @param count     how many times it happened (repeats are merged)
 */
public record WarningInfo(
		String id,
		String severity,
		String title,
		String detail,
		String advice,
		@Nullable String source,
		@Nullable String packet,
		@Nullable String direction,
		long firstSeen,
		long lastSeen,
		int count) {
}
