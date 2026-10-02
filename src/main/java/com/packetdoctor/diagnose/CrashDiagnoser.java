package com.packetdoctor.diagnose;

import com.packetdoctor.api.ConsoleLine;
import com.packetdoctor.api.ConsoleProblem;
import com.packetdoctor.api.LagCause;
import com.packetdoctor.net.Severity;
import com.packetdoctor.server.console.ConsoleCapture;
import com.packetdoctor.server.perf.LagAnalysis;
import com.packetdoctor.server.perf.LagMonitor;
import net.minecraft.CrashReport;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.packetdoctor.Tr.t;

/**
 * Explains a crash from its {@link CrashReport}. The exception type and message decide
 * what kind of crash it was; the stack trace (via {@link ModBlame}) decides which mod most
 * likely caused it. Works for the player's game and for a dedicated server; only a few
 * tips differ.
 */
public final class CrashDiagnoser {
	private static final Pattern MIXIN_MOD = Pattern.compile("from mod ([a-z0-9_.\\-]+)");
	private static final Pattern MISSING_CLASS = Pattern.compile("([a-zA-Z_$][\\w$]*(?:[./][a-zA-Z_$][\\w$]*)+)");
	private static final List<String> DETAIL_KEYS = List.of("Entity Type:", "Entity's Exact location:", "Block:", "Block location:",
			"Block entity type:", "Level name:", "Screen name:", "Resource packs:", "Loaded shaderpack:", "Current Language:");

	private CrashDiagnoser() {
	}

	/** Explains a crash of the player's game. */
	public static Diagnosis diagnose(CrashReport report) {
		return diagnose(report, false);
	}

	/**
	 * @param server true for a dedicated server crash (changes some advice, e.g. memory
	 *               is set in the start script, not a launcher)
	 */
	public static Diagnosis diagnose(CrashReport report, boolean server) {
		Throwable error = report.getException();
		String title = report.getTitle() == null ? "" : report.getTitle();
		String details = safeDetails(report);
		List<Throwable> chain = ModBlame.causes(error);
		String text = (title + " " + chainText(chain)).toLowerCase(Locale.ROOT);
		String lowerTitle = title.toLowerCase(Locale.ROOT);
		List<ModBlame.Culprit> culprits = ModBlame.blame(error, false);
		ModBlame.Culprit mod = ModBlame.prime(culprits);

		// The server gave up while starting (port in use, world locked...). Minecraft throws this
		// with no detail; the real reason is in the console just before.
		if (server && error.getMessage() != null && error.getMessage().startsWith("Failed to initialize server")) {
			return startupFromConsole(error.getMessage());
		}

		Diagnosis.Builder b = Diagnosis.builder("crash").severity(Severity.DANGER)
				.original(title + ": " + error.getClass().getSimpleName() + (error.getMessage() != null ? ": " + error.getMessage() : ""));

		b.cause("CRASH");
		if (server && title.contains("Watching Server")) {
			watchdog(b, error);
		} else if (title.contains("Manually triggered debug crash")) {
			explain(b, "packetdoctor.crash.debug", SourceKind.YOU, null);
			b.severity(Severity.INFO);
			tip(b, "packetdoctor.tip.crash.debug");
		} else if (has(chain, OutOfMemoryError.class) || text.contains("outofmemory")) {
			b.cause("OUT_OF_MEMORY");
			explain(b, "packetdoctor.crash.oom", SourceKind.COMPUTER, t("packetdoctor.who.memory"));
			if (mod != null) b.source(SourceKind.COMPUTER, t("packetdoctor.who.memory"), t("packetdoctor.note.oom_mod", mod.name()));
			tip(b, server ? "packetdoctor.tip.crash.more_memory_server" : "packetdoctor.tip.crash.more_memory");
			tip(b, server ? "packetdoctor.tip.crash.lower_view_distance" : "packetdoctor.tip.crash.lower_settings");
			tip(b, "packetdoctor.tip.crash.half_ram");
		} else if (containsAny(text, "mixinapplyerror", "mixintransformererror", "invalidinjectionexception", "injectionerror",
				"mixinexception", "mixin apply", "mixinprocessor")) {
			String id = mixinMod(chain);
			String name = id != null ? ModBlame.name(id) : mod != null ? mod.name() : t("packetdoctor.who.a_mod");
			b.headline(t("packetdoctor.crash.mixin.headline")).summary(t("packetdoctor.crash.mixin.summary", name))
					.source(SourceKind.MOD, id != null ? name + " (" + id + ")" : mod != null ? mod.label() : null, t("packetdoctor.crash.mixin.note"));
			b.tip(t("packetdoctor.tip.crash.update_for_version", name)).tip(t("packetdoctor.tip.crash.conflict", name));
		} else if (hasAny(chain, NoSuchMethodError.class, NoSuchFieldError.class, NoClassDefFoundError.class, AbstractMethodError.class,
				IncompatibleClassChangeError.class, ClassNotFoundException.class)) {
			String missing = missingThing(chain);
			String owner = missing == null ? null : ModBlame.modForClass(missing);
			String what = missing != null ? " (" + missing + ")" : "";
			String extra = owner == null && missing != null && !missing.startsWith("net.minecraft") ? " " + t("packetdoctor.crash.linkage.missing_mod") : "";
			b.headline(t("packetdoctor.crash.linkage.headline")).summary(t("packetdoctor.crash.linkage.summary", what, extra));
			b.source(SourceKind.MOD, mod != null ? mod.label() : null, t(mod != null ? "packetdoctor.crash.linkage.note" : "packetdoctor.note.no_single_mod"));
			b.tip(t("packetdoctor.tip.crash.update_mods", mod != null ? mod.name() : t("packetdoctor.who.your_mods")));
			tip(b, "packetdoctor.tip.crash.dependencies");
		} else if (has(chain, StackOverflowError.class)) {
			explain(b, "packetdoctor.crash.loop", SourceKind.MOD, null);
			sourceFromMod(b, mod, culprits);
			tip(b, "packetdoctor.tip.crash.bisect");
		} else if (containsAny(text, "glfw", "opengl", "pixel format", "wgl", "vulkan", "gl_out_of_memory", "driver", "gpu device",
				"atio6axx", "nvoglv", "ig9icd", "ig75icd", "ig7icd", "renderpearl") || graphicsStack(chain)) {
			explain(b, "packetdoctor.crash.graphics", SourceKind.COMPUTER, t("packetdoctor.who.graphics_driver"));
			if (mod != null) b.source(SourceKind.MOD, mod.label(), t("packetdoctor.note.graphics_mod"));
			tip(b, "packetdoctor.tip.crash.update_driver");
			tip(b, "packetdoctor.tip.crash.no_shaders");
			tip(b, "packetdoctor.tip.crash.dedicated_gpu");
		} else if (has(chain, java.util.ConcurrentModificationException.class)) {
			explain(b, "packetdoctor.crash.cme", SourceKind.MOD, null);
			sourceFromMod(b, mod, culprits);
			tip(b, "packetdoctor.tip.crash.report_author");
		} else if (containsAny(lowerTitle, "ticking entity", "ticking block entity", "ticking player")
				|| containsAny(lowerTitle, "exception in world tick", "exception ticking world") && mod == null
				&& containsAny(details, "Entity Type:", "Block entity type:", "Block location:")) {
			// Only when a specific object in the world failed. "Exception in server tick loop" is
			// the title of every crash during a tick, so on its own it says nothing about the world.
			boolean serverTick = lowerTitle.contains("server tick");
			String specific = lowerTitle.contains("entity") ? " " + t("packetdoctor.crash.world.entity") : "";
			b.headline(t(serverTick ? (server ? "packetdoctor.crash.world.headline_dedicated" : "packetdoctor.crash.world.headline_server")
					: "packetdoctor.crash.world.headline")).summary(t("packetdoctor.crash.world.summary", specific));
			if (mod != null) b.source(SourceKind.MOD, mod.label(), t("packetdoctor.note.world_mod"));
			else b.source(SourceKind.WORLD, null, t("packetdoctor.crash.world.note"));
			tip(b, "packetdoctor.tip.crash.backup_world");
			tip(b, "packetdoctor.tip.crash.remove_object");
			if (mod != null) b.tip(t("packetdoctor.tip.crash.update_owner", mod.name()));
		} else if (!server && containsAny(text, "resource", "reloading", "texture", "atlas", "model") && mod == null) {
			explain(b, "packetdoctor.crash.resources", SourceKind.MINECRAFT, t("packetdoctor.who.resource_loading"));
			tip(b, "packetdoctor.tip.crash.remove_packs");
		} else if (mod != null) {
			b.headline(t(server ? "packetdoctor.crash.mod.headline_server" : "packetdoctor.crash.mod.headline", mod.name())).summary(t("packetdoctor.crash.mod.summary", mod.name(), plainError(error)));
			sourceFromMod(b, mod, culprits);
			b.tip(t("packetdoctor.tip.update_mod", mod.name())).tip(t("packetdoctor.tip.remove_mod_report", mod.name()));
		} else {
			b.headline(t(server ? "packetdoctor.crash.minecraft.headline_server" : "packetdoctor.crash.minecraft.headline"))
					.summary(t("packetdoctor.crash.minecraft.summary", plainError(error)))
					.source(SourceKind.MINECRAFT, null, t("packetdoctor.note.no_mod_in_error"));
			tip(b, "packetdoctor.tip.crash.note_moment");
			tip(b, server ? "packetdoctor.tip.crash.update_fabric_server" : "packetdoctor.tip.crash.no_packs_update_fabric");
		}

		if (server && title.equals(STARTUP_TITLE)) {
			// Same explanation of the error, framed as "the server couldn't start".
			b.cause("STARTUP_FAILED");
			String what = b.headline();
			b.headline(t("packetdoctor.crash.startup.headline")).summary(t("packetdoctor.crash.startup.summary", what) + " " + b.summary());
		}
		if (server) addConsole(b, mod);
		tip(b, server ? "packetdoctor.tip.crash.share_server" : "packetdoctor.tip.crash.share");
		Path saved = report.getSaveFile();
		b.technical(technical(title, error, culprits, details, saved) + (server ? consoleTechnical() : ""));
		return b.build();
	}

	/**
	 * Minecraft's watchdog stopped a server whose tick took too long. The lag monitor has
	 * been sampling that tick the whole time; failing that, the report's stack of the server
	 * thread (which the watchdog puts in the error) says where it was stuck.
	 */
	private static void watchdog(Diagnosis.Builder b, Throwable error) {
		b.cause("WATCHDOG");
		LagMonitor monitor = LagMonitor.get();
		List<LagCause> causes = monitor != null && monitor.currentTickMs() > 1000 ? monitor.currentTickCauses() : List.of();
		if (causes.isEmpty() && error.getStackTrace().length > 0) {
			causes = LagAnalysis.causes(List.of(LagAnalysis.classify(error.getStackTrace(), Thread.State.RUNNABLE)), 0);
		}
		LagCause top = causes.isEmpty() ? null : causes.getFirst();
		String stuck = top == null ? "" : " " + t("packetdoctor.crash.watchdog.stuck", LagAnalysis.line(top));
		b.headline(t("packetdoctor.crash.watchdog.headline")).summary(t("packetdoctor.crash.watchdog.summary", stuck));
		if (top == null) {
			b.source(SourceKind.UNKNOWN, null, t("packetdoctor.crash.watchdog.note"));
		} else {
			b.source(top.mod() != null ? SourceKind.MOD : sourceKindFor(top.kind()), top.mod() != null ? top.mod() : top.label(),
					t("packetdoctor.crash.watchdog.note"));
			b.tip(top.advice());
		}
		tip(b, "packetdoctor.tip.crash.watchdog_time");
		tip(b, "packetdoctor.tip.crash.watchdog_lag_command");
		List<String> lines = new ArrayList<>();
		for (LagCause c : causes) lines.add(t("packetdoctor.crash.watchdog.cause", LagAnalysis.line(c)));
		b.warnings(lines);
	}

	/** Crash report title used for a server that failed while starting. */
	public static final String STARTUP_TITLE = "Starting the server";

	/**
	 * The server gave up while starting without an exception to read (a port in use, broken
	 * data packs or world data): explained from what the console said just before.
	 */
	public static Diagnosis startupFromConsole(String message) {
		Diagnosis.Builder b = Diagnosis.builder("crash").severity(Severity.DANGER).cause("STARTUP_FAILED").original(message);
		ConsoleCapture capture = ConsoleCapture.get();
		long now = System.currentTimeMillis();
		ConsoleProblem problem = null;
		if (capture != null) {
			for (ConsoleProblem p : capture.problems()) {
				if (now - p.lastSeen() > 120_000 || p.id().equals("offline_mode") || p.id().equals("command_ambiguity")) continue;
				// A recognised problem (port in use, broken data pack...) beats a generic error, which is
				// often just Minecraft reporting the failure itself; then the more serious one wins.
				if (problem == null || startupScore(p) > startupScore(problem)) problem = p;
			}
		}
		String lower = message.toLowerCase(Locale.ROOT);
		String summary = t("packetdoctor.crash.startup.summary_console", message);
		if (problem != null) summary += " " + t("packetdoctor.crash.startup.problem", problem.title(), problem.explanation());
		b.headline(t("packetdoctor.crash.startup.headline")).summary(summary);
		if (problem != null && problem.mod() != null) {
			b.source(SourceKind.MOD, problem.mod(), t("packetdoctor.crash.startup.note"));
		} else {
			b.source(SourceKind.SERVER, t("packetdoctor.who.server_files"), t("packetdoctor.crash.startup.note"));
		}
		if (problem != null) b.tip(problem.advice());
		if (lower.contains("datapacks")) b.tip(t("packetdoctor.tip.startup.safe_mode"));
		if (lower.contains("world data")) b.tip(t("packetdoctor.tip.startup.world_backup"));
		b.tip(t("packetdoctor.tip.startup.read_console"));
		b.tip(t("packetdoctor.tip.crash.share_server"));
		addConsole(b, null);
		b.technical(consoleTechnical());
		return b.build();
	}

	private static int startupScore(ConsoleProblem p) {
		boolean generic = p.id().equals("error") || p.id().equals("warning");
		return (generic ? 0 : 10) + (p.severity().equals("DANGER") ? 2 : p.severity().equals("WARNING") ? 1 : 0);
	}

	/** The broad kind of source for a lag cause, for the "where it came from" line. */
	public static SourceKind sourceKindFor(String lagKind) {
		return switch (lagKind) {
			case "ENTITIES", "MOB_AI", "PATHFINDING", "SPAWNING", "BLOCK_ENTITIES", "HOPPERS", "REDSTONE", "SCHEDULED_TICKS",
					"CHUNK_TICKS", "EXPLOSIONS", "ENTITY_TRACKING" -> SourceKind.WORLD;
			case "GC", "WAITING" -> SourceKind.COMPUTER;
			case "MOD" -> SourceKind.MOD;
			case "PLAYER_PACKETS", "PLAYERS", "COMMANDS" -> SourceKind.SERVER;
			default -> SourceKind.MINECRAFT;
		};
	}

	/** A dedicated server's console just before the crash: its warnings, and a mod that kept erroring. */
	private static void addConsole(Diagnosis.Builder b, ModBlame.@Nullable Culprit mod) {
		ConsoleCapture capture = ConsoleCapture.get();
		if (capture == null) return;
		long now = System.currentTimeMillis();
		List<String> lines = new ArrayList<>();
		for (ConsoleLine l : capture.warningsBetween(now - 120_000, now, 10)) lines.add(ConsoleCapture.format(l));
		b.warnings(lines);
		if (mod == null) {
			List<ConsoleProblem> problems = capture.recentProblemsWithMod(now - 120_000);
			if (!problems.isEmpty() && problems.getFirst().count() >= 3) {
				ConsoleProblem p = problems.getFirst();
				b.tip(t("packetdoctor.tip.crash.console_mod", p.mod(), p.count()));
			}
		}
	}

	private static String consoleTechnical() {
		ConsoleCapture capture = ConsoleCapture.get();
		if (capture == null) return "";
		List<ConsoleLine> tail = capture.lines(30, org.apache.logging.log4j.Level.INFO);
		if (tail.isEmpty()) return "";
		StringBuilder sb = new StringBuilder("\nConsole before the crash (last ").append(tail.size()).append(" lines):\n");
		for (ConsoleLine l : tail) sb.append("  ").append(ConsoleCapture.format(l)).append('\n');
		return sb.toString();
	}

	private static void explain(Diagnosis.Builder b, String base, SourceKind kind, @Nullable String source) {
		b.headline(t(base + ".headline")).summary(t(base + ".summary")).source(kind, source, t(base + ".note"));
	}

	private static void tip(Diagnosis.Builder b, String key) {
		b.tip(t(key));
	}

	private static void sourceFromMod(Diagnosis.Builder b, ModBlame.@Nullable Culprit mod, List<ModBlame.Culprit> culprits) {
		if (mod == null) {
			b.source(SourceKind.MINECRAFT, null, t("packetdoctor.note.no_mod_in_error"));
			return;
		}
		long others = culprits.stream().filter(c -> !c.platform() && !c.id().equals(mod.id())).count();
		b.source(SourceKind.MOD, mod.label(), others > 0 ? t("packetdoctor.note.closest_mod_others", others) : t("packetdoctor.note.closest_mod"));
	}

	private static String plainError(Throwable error) {
		Throwable root = ModBlame.causes(error).getFirst();
		String key = switch (root) {
			case NullPointerException ignored -> "packetdoctor.error.npe";
			case IndexOutOfBoundsException ignored -> "packetdoctor.error.index";
			case ClassCastException ignored -> "packetdoctor.error.cast";
			case IllegalStateException ignored -> "packetdoctor.error.state";
			case IllegalArgumentException ignored -> "packetdoctor.error.argument";
			default -> null;
		};
		return key == null ? root.getClass().getSimpleName() : t(key);
	}

	private static @Nullable String mixinMod(List<Throwable> chain) {
		for (Throwable x : chain) {
			if (x.getMessage() == null) continue;
			Matcher m = MIXIN_MOD.matcher(x.getMessage());
			if (m.find()) return m.group(1);
		}
		return null;
	}

	private static @Nullable String missingThing(List<Throwable> chain) {
		for (Throwable x : chain) {
			if ((x instanceof LinkageError || x instanceof ClassNotFoundException) && x.getMessage() != null) {
				Matcher m = MISSING_CLASS.matcher(x.getMessage());
				if (m.find()) return m.group(1).replace('/', '.');
			}
		}
		return null;
	}

	private static boolean graphicsStack(List<Throwable> chain) {
		StackTraceElement[] st = chain.getFirst().getStackTrace();
		for (int i = 0; i < Math.min(4, st.length); i++) {
			String c = st[i].getClassName();
			if (c.startsWith("org.lwjgl.") || c.startsWith("com.mojang.blaze3d.")) return true;
		}
		return false;
	}

	private static boolean has(List<Throwable> chain, Class<? extends Throwable> type) {
		for (Throwable x : chain) if (type.isInstance(x)) return true;
		return false;
	}

	@SafeVarargs
	private static boolean hasAny(List<Throwable> chain, Class<? extends Throwable>... types) {
		for (Class<? extends Throwable> type : types) if (has(chain, type)) return true;
		return false;
	}

	private static boolean containsAny(String text, String... needles) {
		for (String n : needles) if (text.contains(n)) return true;
		return false;
	}

	private static String chainText(List<Throwable> chain) {
		StringBuilder sb = new StringBuilder();
		for (Throwable x : chain) sb.append(x.getClass().getName()).append(": ").append(x.getMessage()).append(' ');
		return sb.toString();
	}

	private static String safeDetails(CrashReport report) {
		try {
			return report.getDetails();
		} catch (RuntimeException e) {
			return "";
		}
	}

	/** Details for modders and admins; deliberately English, like stack traces. */
	private static String technical(String title, Throwable error, List<ModBlame.Culprit> culprits, String details, @Nullable Path saved) {
		StringBuilder sb = new StringBuilder();
		sb.append("Crash: ").append(title).append('\n');
		if (saved != null) sb.append("Minecraft's crash report: ").append(saved.toAbsolutePath()).append('\n');
		if (!culprits.isEmpty()) {
			List<String> parts = new ArrayList<>();
			for (ModBlame.Culprit c : culprits) parts.add(c.label() + " x" + c.frames());
			sb.append("Mods found in the error: ").append(String.join(", ", parts)).append('\n');
		}
		List<String> facts = new ArrayList<>();
		for (String line : details.split("\\R")) {
			String trimmed = line.trim();
			for (String key : DETAIL_KEYS) if (trimmed.startsWith(key) && facts.size() < 12) facts.add(trimmed);
		}
		if (!facts.isEmpty()) {
			sb.append("\nWhere it happened:\n");
			for (String f : facts) sb.append("  ").append(f).append('\n');
		}
		sb.append("\nError:\n");
		DisconnectDiagnoser.appendThrowable(sb, error);
		return sb.toString();
	}
}
