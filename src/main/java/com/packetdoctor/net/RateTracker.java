package com.packetdoctor.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts packets per type in one-second buckets. Each finished second is queued and
 * handed out exactly once by {@link #drain}, so the rules judge whole seconds no matter
 * which thread happened to close the bucket.
 */
final class RateTracker {
	/** One finished second: total packets, per-type counts, and a free-form extra counter. */
	record Second(long epochSecond, int total, Map<String, Integer> counts, long extra) {
		int count(String path) {
			return counts.getOrDefault(path, 0);
		}

		int countAll(List<String> paths) {
			int sum = 0;
			for (String p : paths) sum += count(p);
			return sum;
		}

		/** The busiest types, e.g. "movement x240, arm swing x80". */
		String top(int n) {
			StringBuilder sb = new StringBuilder();
			counts.entrySet().stream()
					.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
					.limit(n)
					.forEach(e -> {
						if (!sb.isEmpty()) sb.append(", ");
						sb.append(PacketNames.friendly(e.getKey())).append(" x").append(e.getValue());
					});
			return sb.toString();
		}

		/** The single busiest type's path, or null for an empty second. */
		String busiest() {
			return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		}
	}

	private long second = -1;
	private int total;
	private long extra;
	private Map<String, Integer> counts = new HashMap<>();
	private final Deque<Second> history = new ArrayDeque<>();
	private final List<Second> pending = new ArrayList<>();

	/** Counts one packet, keyed by the id's path. */
	synchronized void record(String path, long now) {
		roll(now);
		total++;
		counts.merge(path, 1, Integer::sum);
	}

	/** Adds to the free-form counter for the current second (used for particle totals). */
	synchronized void addExtra(long amount, long now) {
		roll(now);
		extra += amount;
	}

	private void roll(long now) {
		long s = now / 1000;
		if (second == s) return;
		if (second >= 0 && total > 0) {
			Second finished = new Second(second, total, counts, extra);
			history.addFirst(finished);
			while (history.size() > 10) history.removeLast();
			if (pending.size() < 10) pending.add(finished);
		}
		second = s;
		total = 0;
		extra = 0;
		counts = new HashMap<>();
	}

	/** Every second finished since the last call, oldest first. */
	synchronized List<Second> drain(long now) {
		roll(now);
		if (pending.isEmpty()) return List.of();
		List<Second> out = List.copyOf(pending);
		pending.clear();
		return out;
	}

	/** Packets per second over the last {@code seconds} finished seconds. */
	synchronized double average(int seconds, long now) {
		long current = now / 1000;
		int sum = 0;
		for (Second s : history) {
			if (current - s.epochSecond() <= seconds) sum += s.total();
		}
		return sum / (double) Math.max(1, seconds);
	}

	/** The busiest second within the last {@code seconds}, or null. */
	synchronized Second peak(int seconds, long now) {
		long current = now / 1000;
		Second best = null;
		for (Second s : history) {
			if (current - s.epochSecond() <= seconds && (best == null || s.total() > best.total())) best = s;
		}
		return best;
	}

	synchronized void reset() {
		second = -1;
		total = 0;
		extra = 0;
		counts = new HashMap<>();
		history.clear();
		pending.clear();
	}
}
