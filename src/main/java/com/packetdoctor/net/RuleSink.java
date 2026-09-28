package com.packetdoctor.net;

import org.jspecify.annotations.Nullable;

/**
 * Where {@link PacketRules} report to. The player's game ({@link PacketMonitor}) and the
 * server's view of each player ({@link ServerMonitor.PlayerSession}) run the same checks
 * on what a player's game sends; they differ only in wording and in who can be blamed.
 */
interface RuleSink {
	/** Starts a warning in this side's wording. */
	Warning.Builder w(String id, Severity severity);

	Warning warn(Warning.Builder builder, @Nullable PacketRecord record);

	/** The mod on the current call stack, for a warning not seen before; null on the server. */
	@Nullable String blameHere(String warningKey);

	/** The mod that sends this packet type, if known; null on the server. */
	@Nullable String blameForType(String path);

	/** Distance since the last position the player sent, or -1 for the first one. */
	double moved(double x, double y, double z);

	void resetMovement();
}
