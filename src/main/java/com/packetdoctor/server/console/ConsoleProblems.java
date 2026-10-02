package com.packetdoctor.server.console;

import com.packetdoctor.Tr;
import com.packetdoctor.api.ConsoleProblem;
import com.packetdoctor.net.Severity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Groups console warnings and errors into problems and explains the ones it recognises.
 * The same error printed every tick becomes one problem with a count.
 *
 * <p>Each kind's text is {@code packetdoctor.log.<id>.title/.explain/.advice}; {@code %s} in
 * them is the mod (or logger) the lines came from.
 */
public final class ConsoleProblems {
	private static final int MAX_GROUPS = 300;
	private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

	/** A known kind of console message. A phrase with "&" needs all its parts. */
	private record Kind(String id, String base, Severity severity, List<String> phrases) {
		boolean matches(String lower) {
			for (String p : phrases) {
				if (p.indexOf('&') < 0) {
					if (lower.contains(p)) return true;
					continue;
				}
				boolean all = true;
				for (String part : p.split("&")) all &= lower.contains(part);
				if (all) return true;
			}
			return false;
		}
	}

	/** First match wins, so specific kinds come first. */
	private static final List<Kind> KINDS = List.of(
			new Kind("overloaded", "packetdoctor.log.overloaded", Severity.WARNING, List.of("can't keep up!")),
			new Kind("watchdog", "packetdoctor.log.watchdog", Severity.DANGER, List.of("a single server tick took", "considering it to be crashed")),
			new Kind("memory", "packetdoctor.log.memory", Severity.DANGER, List.of("outofmemoryerror", "out of memory", "gc overhead limit")),
			new Kind("save_failed", "packetdoctor.log.save_failed", Severity.DANGER, List.of("failed to save", "couldn't save", "could not save",
					"error saving", "failed to store", "no space left on device", "disk quota", "access is denied", "permission denied")),
			new Kind("big_chunk", "packetdoctor.log.big_chunk", Severity.WARNING, List.of("oversized chunk", "chunk&too large", "chunk&too big",
					"region file&too large")),
			new Kind("chunk_corrupt", "packetdoctor.log.chunk_corrupt", Severity.DANGER, List.of("couldn't load chunk", "failed to load chunk",
					"error reading chunk", "couldn't read chunk", "failed to read chunk", "is in the wrong location", "chunk&corrupt", "region&corrupt", "invalid chunk",
					"chunk file at", "regionfile", "region file")),
			new Kind("duplicate_entity", "packetdoctor.log.duplicate_entity", Severity.WARNING, List.of("that already exists",
					"uuid of added entity already exists", "duplicate entity")),
			new Kind("moved_too_quickly", "packetdoctor.log.moved_too_quickly", Severity.INFO, List.of("moved too quickly", "moved wrongly")),
			new Kind("port_in_use", "packetdoctor.log.port_in_use", Severity.DANGER, List.of("failed to bind", "address already in use")),
			new Kind("clock", "packetdoctor.log.clock", Severity.WARNING, List.of("time ran backwards", "did the system time change")),
			new Kind("mixin", "packetdoctor.log.mixin", Severity.WARNING, List.of("mixin")),
			new Kind("missing_content", "packetdoctor.log.missing_content", Severity.WARNING, List.of("unknown registry key", "unknown block",
					"unknown item", "unknown entity", "not found in registry", "missing registry", "unregistered", "unknown key", "missing entry")),
			new Kind("datapack", "packetdoctor.log.datapack", Severity.WARNING, List.of("datapack", "data pack", "couldn't parse", "failed to parse",
					"parsing error", "couldn't load recipe", "failed to load recipe", "couldn't load tag", "missing references", "unbound values",
					"couldn't load function", "failed to load function", "loot table")),
			new Kind("tick_error", "packetdoctor.log.tick_error", Severity.DANGER, List.of("exception ticking", "error ticking",
					"exception while ticking", "caught previously unhandled exception", "exception in server tick")),
			new Kind("packet_error", "packetdoctor.log.packet_error", Severity.WARNING, List.of("failed to handle packet", "error sending packet",
					"error receiving packet", "exception handling packet", "packet handling")),
			new Kind("offline_mode", "packetdoctor.log.offline_mode", Severity.INFO, List.of("offline/insecure mode", "make no attempt to authenticate",
					"connect with any username", "set \"online-mode\"")),
			new Kind("command_ambiguity", "packetdoctor.log.command_ambiguity", Severity.INFO, List.of("ambiguity between arguments")),
			new Kind("removed_entity", "packetdoctor.log.removed_entity", Severity.INFO, List.of("fetching packet for removed entity")),
			new Kind("deprecated", "packetdoctor.log.deprecated", Severity.INFO, List.of("deprecated")));

	private static final Kind ERROR = new Kind("error", "packetdoctor.log.error", Severity.DANGER, List.of());
	private static final Kind WARNING = new Kind("warning", "packetdoctor.log.warning", Severity.WARNING, List.of());

	private static final class Group {
		final Kind kind;
		final @Nullable String mod;
		final String logger;
		final String example;
		final long firstSeen;
		volatile long lastSeen;
		volatile int count;

		Group(Kind kind, @Nullable String mod, String logger, String example, long time) {
			this.kind = kind;
			this.mod = mod;
			this.logger = logger;
			this.example = example;
			this.firstSeen = time;
			this.lastSeen = time;
		}

		ConsoleProblem info() {
			String who = mod != null ? mod : logger;
			return new ConsoleProblem(kind.id, kind.severity.name(), Tr.t(kind.base + ".title", who), Tr.t(kind.base + ".explain", who),
					Tr.t(kind.base + ".advice", who), mod, logger, example, count, firstSeen, lastSeen);
		}
	}

	private final Map<String, Group> groups = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Group> eldest) {
			return size() > MAX_GROUPS;
		}
	};
	private final Consumer<ConsoleProblem> listener;

	ConsoleProblems(Consumer<ConsoleProblem> listener) {
		this.listener = listener;
	}

	/** Which kind a line is (for lines that aren't warnings too, e.g. to read "Can't keep up!"). */
	static String kindOf(String text, boolean error) {
		Kind k = match(text.toLowerCase(Locale.ROOT), error);
		return k.id;
	}

	private static Kind match(String lower, boolean error) {
		for (Kind k : KINDS) if (k.matches(lower)) return k;
		return error ? ERROR : WARNING;
	}

	/** Records a warning or error line. Calls the listener for a new problem and at 10, 100 and 1000 repeats. */
	void add(long time, boolean error, String logger, String message, @Nullable String errorText, @Nullable String mod) {
		String text = errorText == null ? message : message + " " + errorText;
		Kind kind = match(text.toLowerCase(Locale.ROOT), error);
		// Known kinds group per mod; unknown lines also by their wording, with numbers and ids blanked out.
		String key = kind.id + "|" + (mod != null ? mod : logger);
		if (kind == ERROR || kind == WARNING) key += "|" + normalize(message);

		Group g;
		boolean fresh;
		synchronized (groups) {
			g = groups.get(key);
			fresh = g == null;
			if (fresh) {
				g = new Group(kind, mod, logger, shorten(text, 400), time);
				groups.put(key, g);
			}
			g.count++;
			g.lastSeen = time;
		}
		int n = g.count;
		if (fresh || n == 10 || n == 100 || n == 1000) listener.accept(g.info());
	}

	/** Every problem still remembered: most serious first, then most recent. */
	List<ConsoleProblem> all() {
		List<Group> copy;
		synchronized (groups) {
			copy = new ArrayList<>(groups.values());
		}
		copy.sort(Comparator.comparing((Group g) -> g.kind.severity).reversed().thenComparing(g -> -g.lastSeen));
		return copy.stream().map(Group::info).toList();
	}

	/** Problems seen since {@code since}, with a mod named, most repeated first. */
	List<ConsoleProblem> recentWithMod(long since) {
		List<ConsoleProblem> out = new ArrayList<>();
		for (ConsoleProblem p : all()) if (p.mod() != null && p.lastSeen() >= since) out.add(p);
		out.sort(Comparator.comparingInt(ConsoleProblem::count).reversed());
		return out;
	}

	void clear() {
		synchronized (groups) {
			groups.clear();
		}
	}

	private static String normalize(String message) {
		String s = UUID.matcher(message.toLowerCase(Locale.ROOT)).replaceAll("#");
		s = NUMBER.matcher(s).replaceAll("#");
		return s.length() > 120 ? s.substring(0, 120) : s;
	}

	static String shorten(String s, int max) {
		return s.length() > max ? s.substring(0, max - 3) + "..." : s;
	}
}
