package com.packetdoctor.network;

import com.google.gson.Gson;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * How a server running Packet Doctor is doing, sent every two seconds to players whose game
 * runs Packet Doctor too, so their game can tell server lag from its own. JSON, so either
 * side can be a newer version.
 *
 * @param tps10s  ticks per second over the last 10 seconds
 * @param mspt    average milliseconds per tick over the last minute
 * @param causes  what slow ticks in the last minute were spent on, biggest first (labels in
 *                the server's language)
 */
public record ServerStatus(int version, double tps10s, double tps1m, double targetTps, double mspt, double msptMax, boolean overloaded,
		List<Cause> causes) {

	public static final int CURRENT = 1;
	private static final Gson GSON = new Gson();

	/** One cause: its plain label, share of the slow time (0-100), and the mod if one stood out. */
	public record Cause(String label, double percent, @Nullable String mod) {
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static @Nullable ServerStatus fromJson(String json) {
		try {
			ServerStatus s = GSON.fromJson(json, ServerStatus.class);
			return s == null || s.causes() == null ? null : s;
		} catch (RuntimeException e) {
			return null;
		}
	}
}
