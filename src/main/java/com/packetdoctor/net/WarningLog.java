package com.packetdoctor.net;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Warnings for the current connection, merged by key. */
public final class WarningLog {
	private static final int MAX = 300;

	private final Map<String, Warning> byKey = new LinkedHashMap<>();
	private final Map<String, Long> lastNotified = new LinkedHashMap<>();
	private final Consumer<Warning> notifier;

	WarningLog(Consumer<Warning> notifier) {
		this.notifier = notifier;
	}

	/**
	 * Records a warning. Returns the stored entry, which is the existing one when this
	 * is a repeat. The notifier (toast) fires for new warnings and for repeats once the
	 * cooldown has passed.
	 */
	Warning add(Warning warning, long cooldownMs) {
		Warning stored;
		boolean notify;
		synchronized (this) {
			Warning existing = byKey.get(warning.key);
			if (existing != null) {
				existing.repeat(warning);
				stored = existing;
			} else {
				if (byKey.size() >= MAX) {
					String oldest = byKey.keySet().iterator().next();
					byKey.remove(oldest);
					lastNotified.remove(oldest);
				}
				byKey.put(warning.key, warning);
				stored = warning;
			}
			Long last = lastNotified.get(warning.key);
			long now = System.currentTimeMillis();
			notify = last == null || now - last >= cooldownMs;
			if (notify) lastNotified.put(warning.key, now);
		}
		if (notify) notifier.accept(stored);
		return stored;
	}

	synchronized boolean contains(String key) {
		return byKey.containsKey(key);
	}

	synchronized Warning get(String key) {
		return byKey.get(key);
	}

	/** All warnings, most recent first. */
	public synchronized List<Warning> all() {
		List<Warning> out = new ArrayList<>(byKey.values());
		out.sort(Comparator.comparingLong(Warning::lastSeen).reversed());
		return out;
	}

	/** Warnings seen in the last {@code ms} milliseconds, most severe first. */
	public synchronized List<Warning> since(long ms) {
		long cutoff = System.currentTimeMillis() - ms;
		List<Warning> out = new ArrayList<>();
		for (Warning w : byKey.values()) {
			if (w.lastSeen() >= cutoff) out.add(w);
		}
		out.sort(Comparator.<Warning, Integer>comparing(w -> w.severity.ordinal()).reversed()
				.thenComparing(Comparator.comparingLong(Warning::lastSeen).reversed()));
		return out;
	}

	public synchronized void clear() {
		byKey.clear();
		lastNotified.clear();
	}
}
