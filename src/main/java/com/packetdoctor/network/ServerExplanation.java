package com.packetdoctor.network;

import com.google.gson.Gson;
import com.packetdoctor.net.WarningData;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * What a server running Packet Doctor tells a player's game (running Packet Doctor too)
 * just before disconnecting it. Sent as JSON, so either side can be a newer version.
 *
 * @param version     format version, currently 1
 * @param kickerKind  who decided: {@code MOD}, {@code OPERATOR} (the /kick command), or {@code SERVER} (the game itself)
 * @param kicker      the mod that kicked, as "Name (id)", when {@code kickerKind} is {@code MOD}
 * @param reason      extra reason text given by the kicking mod through the API, if any
 * @param serverError a short description of an error the server hit on this player's data, if any
 * @param warnings    what the server noticed about this player's packets recently
 * @param modVersion  the server's Packet Doctor version
 * @param crashKind   set when the server is going down because it crashed: CRASH, WATCHDOG or OUT_OF_MEMORY
 * @param crashHeadline the server's own explanation of its crash, in the server's language
 * @param crashSource what the server thinks caused it, e.g. "A mod: Lithium (lithium)"
 */
public record ServerExplanation(
		int version,
		String kickerKind,
		@Nullable String kicker,
		@Nullable String reason,
		@Nullable String serverError,
		List<WarningData> warnings,
		String modVersion,
		@Nullable String crashKind,
		@Nullable String crashHeadline,
		@Nullable String crashSource) {

	public static final int CURRENT = 1;
	private static final Gson GSON = new Gson();

	public String toJson() {
		return GSON.toJson(this);
	}

	public static @Nullable ServerExplanation fromJson(String json) {
		try {
			ServerExplanation e = GSON.fromJson(json, ServerExplanation.class);
			return e == null || e.kickerKind() == null || e.warnings() == null ? null : e;
		} catch (RuntimeException ex) {
			return null; // a newer or broken format: fall back to the player's own explanation
		}
	}
}
