package com.packetdoctor.server.perf;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lag samples added up: how much of the server thread's busy time went to each kind of
 * work, mod, block entity or entity type, place (chunk), single block entity or entity,
 * and the players those places are near. One of these covers a minute of the always-on
 * profile, or one lag spike; they can be merged into longer stretches.
 *
 * <p>Each sample is weighted by how many milliseconds of busy time it stands for, so the
 * numbers are times, not counts. Not thread-safe: the owner locks.
 */
public final class ProfileData {
	/** Most distinct keys one tally keeps; later new keys are only counted in the total. */
	private static final int MAX_KEYS = 4000;
	static final String SEP = "\t";

	/** A count per key. */
	public static final class Tally {
		final Map<String, Long> values = new HashMap<>();

		void add(String key, long amount) {
			if (values.size() >= MAX_KEYS && !values.containsKey(key)) return;
			values.merge(key, amount, Long::sum);
		}

		void merge(Tally other) {
			other.values.forEach(this::add);
		}

		public long get(String key) {
			return values.getOrDefault(key, 0L);
		}

		/** Keys by value, biggest first. */
		public List<Map.Entry<String, Long>> top(int max) {
			return values.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(max).toList();
		}

		/** For keys "prefix\tsub": the subs of one prefix, biggest first. */
		public List<Map.Entry<String, Long>> topWithin(String prefix, int max) {
			String p = prefix + SEP;
			List<Map.Entry<String, Long>> out = new ArrayList<>();
			for (Map.Entry<String, Long> e : values.entrySet()) {
				if (e.getKey().startsWith(p)) out.add(Map.entry(e.getKey().substring(p.length()), e.getValue()));
			}
			out.sort(Map.Entry.<String, Long>comparingByValue().reversed());
			return out.size() > max ? out.subList(0, max) : out;
		}

		public boolean isEmpty() {
			return values.isEmpty();
		}
	}

	/** Busy milliseconds sampled. */
	public long totalMs;
	public long samples;
	/** Ticks that ended in this stretch, and their total length (for milliseconds per tick). */
	public long ticks;
	public long tickNanos;
	/** False for one lag spike: costs are then shown as total milliseconds, not per tick. */
	public boolean perTick = true;

	/** Kind of work (LagKind name). */
	public final Tally kinds = new Tally();
	/** Mod id: time in that mod's code. */
	public final Tally mods = new Tally();
	/** mod \t method. */
	public final Tally modMethods = new Tally();
	/** mod \t how it got run. */
	public final Tally modVias = new Tally();
	/** mod \t LagKind name. */
	public final Tally modKinds = new Tally();
	/** mod \t thing key. */
	public final Tally modThings = new Tally();
	/** mod \t spot key. */
	public final Tally modSpots = new Tally();
	/** Thing key ("B minecraft:hopper"). */
	public final Tally things = new Tally();
	/** thing key \t spot key. */
	public final Tally thingSpots = new Tally();
	/** LagKind name \t thing key. */
	public final Tally kindThings = new Tally();
	/** LagKind name \t spot key. */
	public final Tally kindSpots = new Tally();
	/** Spot key (one chunk). */
	public final Tally spots = new Tally();
	/** spot key \t thing key. */
	public final Tally spotThings = new Tally();
	/** spot key \t mod. */
	public final Tally spotMods = new Tally();
	/** One block entity or entity. */
	public final Tally singles = new Tally();
	/** The nearest player's name. */
	public final Tally players = new Tally();
	/** player \t thing key. */
	public final Tally playerThings = new Tally();
	/** An example location for each spot and single key. */
	public final Map<String, Where> places = new HashMap<>();

	public void add(LagAnalysis.Sample s, long weightMs) {
		totalMs += weightMs;
		samples++;
		String kind = s.kind().name();
		kinds.add(kind, weightMs);
		String mod = s.mod();
		Where w = s.where();
		if (mod != null) {
			mods.add(mod, weightMs);
			if (s.modMethod() != null) modMethods.add(mod + SEP + s.modMethod(), weightMs);
			if (s.via() != null) modVias.add(mod + SEP + s.via(), weightMs);
			modKinds.add(mod + SEP + kind, weightMs);
		}
		if (w == null) return;
		String thing = w.thingKey();
		String spot = w.spotKey();
		String single = w.singleKey();
		things.add(thing, weightMs);
		thingSpots.add(thing + SEP + spot, weightMs);
		kindThings.add(kind + SEP + thing, weightMs);
		kindSpots.add(kind + SEP + spot, weightMs);
		spots.add(spot, weightMs);
		spotThings.add(spot + SEP + thing, weightMs);
		singles.add(single, weightMs);
		if (mod != null) {
			modThings.add(mod + SEP + thing, weightMs);
			modSpots.add(mod + SEP + spot, weightMs);
			spotMods.add(spot + SEP + mod, weightMs);
		}
		if (w.player() != null) {
			players.add(w.player(), weightMs);
			playerThings.add(w.player() + SEP + thing, weightMs);
		}
		if (places.size() < MAX_KEYS * 2) {
			places.put(spot, w);
			places.put(single, w);
		}
	}

	public void merge(ProfileData o) {
		totalMs += o.totalMs;
		samples += o.samples;
		ticks += o.ticks;
		tickNanos += o.tickNanos;
		kinds.merge(o.kinds);
		mods.merge(o.mods);
		modMethods.merge(o.modMethods);
		modVias.merge(o.modVias);
		modKinds.merge(o.modKinds);
		modThings.merge(o.modThings);
		modSpots.merge(o.modSpots);
		things.merge(o.things);
		thingSpots.merge(o.thingSpots);
		kindThings.merge(o.kindThings);
		kindSpots.merge(o.kindSpots);
		spots.merge(o.spots);
		spotThings.merge(o.spotThings);
		spotMods.merge(o.spotMods);
		singles.merge(o.singles);
		players.merge(o.players);
		playerThings.merge(o.playerThings);
		for (Map.Entry<String, Where> e : o.places.entrySet()) {
			if (places.size() < MAX_KEYS * 2) places.put(e.getKey(), e.getValue());
		}
	}

	/** Share of the sampled time, 0-100. */
	public double percent(long ms) {
		return totalMs == 0 ? 0 : ms * 100.0 / totalMs;
	}

	/** Average milliseconds per tick (0 if no ticks ended). */
	public double msPerTick() {
		return ticks == 0 ? 0 : tickNanos / 1e6 / ticks;
	}

	public @Nullable Where place(String key) {
		return places.get(key);
	}

	public boolean isEmpty() {
		return totalMs == 0;
	}

	/** A copy of the samples' totals for one lag spike (no tick timing). */
	public static ProfileData of(List<LagAnalysis.Sample> samples, long durationMs, int ticks) {
		ProfileData d = new ProfileData();
		for (LagAnalysis.Sample s : samples) d.add(s, 1);
		d.ticks = Math.max(1, ticks);
		d.tickNanos = durationMs * 1_000_000L;
		d.perTick = false;
		return d;
	}
}
