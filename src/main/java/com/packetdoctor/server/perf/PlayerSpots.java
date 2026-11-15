package com.packetdoctor.server.perf;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Where online players are, refreshed by the server thread every second, so the sampler
 * thread can say which player a laggy place is near without touching the world.
 */
public final class PlayerSpots {
	/** Places further than this from every player aren't "near" anyone. */
	public static final double NEAR_BLOCKS = 160;

	public record Spot(String name, String dim, double x, double z) {
	}

	private static volatile List<Spot> spots = List.of();

	private PlayerSpots() {
	}

	public static void update(List<Spot> now) {
		spots = List.copyOf(now);
	}

	public static @Nullable String nearest(String dim, double x, double z) {
		String best = null;
		double bestDist = NEAR_BLOCKS * NEAR_BLOCKS;
		for (Spot s : spots) {
			if (!s.dim().equals(dim)) continue;
			double d = (s.x() - x) * (s.x() - x) + (s.z() - z) * (s.z() - z);
			if (d <= bestDist) {
				bestDist = d;
				best = s.name();
			}
		}
		return best;
	}
}
