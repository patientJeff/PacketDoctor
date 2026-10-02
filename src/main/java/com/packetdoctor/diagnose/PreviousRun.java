package com.packetdoctor.diagnose;

import com.google.gson.Gson;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.api.LagCause;
import com.packetdoctor.net.Severity;
import com.packetdoctor.server.perf.LagAnalysis;
import com.packetdoctor.server.perf.LagMonitor;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static com.packetdoctor.Tr.t;

/**
 * Works out, when a dedicated server starts, whether the previous run ended badly, using
 * what it left behind: Packet Doctor's run marker, Minecraft's crash reports, Java's own
 * crash files ({@code hs_err_pid*.log}), a stuck-tick note from the lag monitor, and the
 * previous log file. This catches endings no crash handler sees: the process killed by a
 * host panel or the system, Java itself crashing, or the machine going down.
 */
public final class PreviousRun {
	private static final Gson GSON = new Gson();
	private static final String STATE_FILE = "run-state.json";
	private static final Pattern FRAME = Pattern.compile("^\\s*at (?:[\\w.$/@-]+//)?([\\w.$]+)\\.([\\w$<>]+)\\(([^:)]*)(?::(\\d+))?\\)");
	private static final Pattern CLOCK = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2})");
	private static final Pattern LOG_LEVEL = Pattern.compile("/(WARN|ERROR|FATAL)\\]");
	private static final Pattern JAVA_FRAME = Pattern.compile("# [Jj] \\d+.*? ([\\w$]+(?:\\.[\\w$]+)+)\\.[\\w$<>]+\\(");

	/** Written at start-up and marked clean at a normal stop. */
	public record State(long start, boolean cleanStop, String version) {
	}

	private PreviousRun() {
	}

	// --- The run marker -----------------------------------------------------------------

	/** Starts this run's marker and returns the previous run's, if there was one. */
	public static @Nullable State begin() {
		State previous = read();
		write(new State(System.currentTimeMillis(), false, PacketDoctor.version()));
		return previous;
	}

	/** The server stopped normally. */
	public static void markStopped() {
		State s = read();
		if (s != null) write(new State(s.start(), true, s.version()));
	}

	private static @Nullable State read() {
		Path file = Reports.folder().resolve(STATE_FILE);
		if (!Files.exists(file)) return null;
		try {
			return GSON.fromJson(Files.readString(file), State.class);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static void write(State s) {
		try {
			Files.createDirectories(Reports.folder());
			Files.writeString(Reports.folder().resolve(STATE_FILE), GSON.toJson(s));
		} catch (IOException e) {
			PacketDoctor.LOGGER.debug("Couldn't write the run marker", e);
		}
	}

	// --- Explaining an unclean end ---------------------------------------------------------

	/**
	 * Explains a previous run that didn't stop cleanly and left no Packet Doctor crash
	 * explanation. Returns null when there is nothing to say.
	 */
	public static @Nullable Diagnosis explain(State previous, LagMonitor.@Nullable StuckTick stuck) {
		Path dir = FabricLoader.getInstance().getGameDir();
		long since = previous.start() - 5_000;
		LogTail log = previousLog(dir.resolve("logs"), since);

		Diagnosis.Builder b = Diagnosis.builder("crash").severity(Severity.DANGER);
		Path hsErr = newest(dir, "hs_err_pid", ".log", since);
		Path report = newest(dir.resolve("crash-reports"), "crash-", "-server.txt", since);
		StringBuilder technical = new StringBuilder("The previous run (started ").append(java.time.Instant.ofEpochMilli(previous.start()))
				.append(") did not stop normally.\n");

		if (hsErr != null) {
			javaCrash(b, hsErr, technical);
		} else if (report != null) {
			crashReport(b, report, technical);
		} else if (stuck != null) {
			stuckTick(b, stuck, technical);
		} else if (log.file != null && !log.started) {
			b.cause("STARTUP_FAILED").headline(t("packetdoctor.crash.startup.headline"))
					.summary(t("packetdoctor.prev.startup.summary", log.lastError != null ? log.lastError : "?"))
					.source(SourceKind.SERVER, t("packetdoctor.who.server_files"), t("packetdoctor.prev.startup.note"));
			b.tip(t("packetdoctor.tip.startup.read_console"));
		} else if (log.outOfMemory) {
			b.cause("OUT_OF_MEMORY").headline(t("packetdoctor.prev.log_oom.headline")).summary(t("packetdoctor.prev.log_oom.summary"))
					.source(SourceKind.COMPUTER, t("packetdoctor.who.memory"), t("packetdoctor.prev.log_oom.note"));
			b.tip(t("packetdoctor.tip.crash.more_memory_server")).tip(t("packetdoctor.tip.crash.lower_view_distance"));
		} else {
			String when = log.lastTime != null ? " " + t("packetdoctor.prev.killed.when", log.lastTime) : "";
			b.cause("STOPPED_UNEXPECTEDLY").severity(Severity.WARNING).headline(t("packetdoctor.prev.killed.headline"))
					.summary(t("packetdoctor.prev.killed.summary", when))
					.source(SourceKind.COMPUTER, t("packetdoctor.who.process"), t("packetdoctor.prev.killed.note"));
			b.tip(t("packetdoctor.tip.prev.host_timeout")).tip(t("packetdoctor.tip.prev.system_memory"))
					.tip(t("packetdoctor.tip.prev.power")).tip(t("packetdoctor.tip.prev.use_stop"));
		}

		if (log.cantKeepUp > 0) b.summary(b.summary() + " " + t("packetdoctor.prev.lagging", log.cantKeepUp));
		if (log.watchdog) b.summary(b.summary() + " " + t("packetdoctor.prev.watchdog_line"));
		b.warnings(log.warnings);
		if (log.file != null) {
			technical.append("\nPrevious log: ").append(log.file).append("\nIts last lines:\n");
			for (String line : log.lastLines(25)) technical.append("  ").append(line).append('\n');
		} else {
			technical.append("\nThe previous log file wasn't found in logs/.\n");
		}
		b.tip(t("packetdoctor.tip.crash.share_server"));
		b.technical(technical.toString());
		return b.build();
	}

	/** Java itself crashed: its hs_err file names the code that was running. */
	private static void javaCrash(Diagnosis.Builder b, Path file, StringBuilder technical) {
		List<String> head = readHead(file, 80);
		String all = String.join("\n", head);
		technical.append("Java crash file: ").append(file.toAbsolutePath()).append('\n');
		for (String line : head) if (line.startsWith("#")) technical.append("  ").append(line).append('\n');

		if (all.contains("insufficient memory for the Java Runtime Environment") || all.contains("Out of Memory Error")) {
			b.cause("OUT_OF_MEMORY").headline(t("packetdoctor.prev.native_oom.headline")).summary(t("packetdoctor.prev.native_oom.summary"))
					.source(SourceKind.COMPUTER, t("packetdoctor.who.memory"), t("packetdoctor.prev.native_oom.note"));
			b.tip(t("packetdoctor.tip.prev.lower_xmx")).tip(t("packetdoctor.tip.prev.system_memory"));
			return;
		}
		String frame = null;
		for (int i = 0; i < head.size() - 1; i++) {
			if (head.get(i).startsWith("# Problematic frame:")) {
				frame = head.get(i + 1).replaceFirst("^#\\s*", "").trim();
				break;
			}
		}
		String mod = null;
		if (frame != null) {
			Matcher m = JAVA_FRAME.matcher("# " + frame);
			if (m.find()) {
				String id = ModBlame.modForClass(m.group(1));
				if (id != null && !ModBlame.isPlatform(id)) mod = ModBlame.name(id) + " (" + id + ")";
			}
		}
		b.cause("JVM_CRASH").headline(t("packetdoctor.prev.jvm.headline"))
				.summary(t("packetdoctor.prev.jvm.summary", frame == null ? "?" : frame));
		if (mod != null) {
			b.source(SourceKind.MOD, mod, t("packetdoctor.prev.jvm.note_mod"));
			b.tip(t("packetdoctor.tip.update_mod", mod));
		} else {
			b.source(SourceKind.COMPUTER, t("packetdoctor.who.java"), t("packetdoctor.prev.jvm.note"));
		}
		b.tip(t("packetdoctor.tip.prev.update_java")).tip(t("packetdoctor.tip.prev.hardware")).tip(t("packetdoctor.tip.prev.send_hs_err", file.getFileName()));
	}

	/** A crash report Packet Doctor didn't get to explain at the time: read it as text. */
	private static void crashReport(Diagnosis.Builder b, Path file, StringBuilder technical) {
		List<String> lines = readHead(file, 400);
		String description = "";
		String exception = "";
		List<StackTraceElement> frames = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.startsWith("Description: ")) {
				description = line.substring(13).trim();
				for (int j = i + 1; j < lines.size(); j++) {
					String next = lines.get(j).trim();
					if (next.isEmpty()) continue;
					exception = next;
					for (int k = j + 1; k < lines.size() && frames.size() < 80; k++) {
						Matcher m = FRAME.matcher(lines.get(k));
						if (!m.find()) break;
						frames.add(new StackTraceElement(m.group(1), m.group(2), m.group(3), m.group(4) == null ? -1 : Integer.parseInt(m.group(4))));
					}
					break;
				}
				break;
			}
		}
		technical.append("Crash report: ").append(file.toAbsolutePath()).append("\n  ").append(description).append("\n  ").append(exception).append('\n');
		StackTraceElement[] stack = frames.toArray(StackTraceElement[]::new);

		if (description.contains("Watching Server")) {
			List<LagCause> causes = stack.length == 0 ? List.of()
					: LagAnalysis.causes(List.of(LagAnalysis.classify(stack, Thread.State.RUNNABLE)), 0);
			stuckText(b, causes, null);
			return;
		}
		ModBlame.Culprit mod = ModBlame.prime(ModBlame.blame(stack, true));
		String lower = exception.toLowerCase(Locale.ROOT);
		if (lower.contains("outofmemoryerror")) {
			b.cause("OUT_OF_MEMORY").headline(t("packetdoctor.crash.oom.headline")).summary(t("packetdoctor.crash.oom.summary"))
					.source(SourceKind.COMPUTER, t("packetdoctor.who.memory"), t("packetdoctor.crash.oom.note"));
			b.tip(t("packetdoctor.tip.crash.more_memory_server")).tip(t("packetdoctor.tip.crash.lower_view_distance"));
			return;
		}
		b.cause("CRASH").headline(mod != null ? t("packetdoctor.prev.report.headline_mod", mod.name()) : t("packetdoctor.prev.report.headline"))
				.summary(t("packetdoctor.prev.report.summary", description, exception));
		if (mod != null) {
			b.source(SourceKind.MOD, mod.label(), t("packetdoctor.note.closest_mod"));
			b.tip(t("packetdoctor.tip.update_mod", mod.name())).tip(t("packetdoctor.tip.remove_mod_report", mod.name()));
		} else {
			b.source(SourceKind.MINECRAFT, null, t("packetdoctor.note.no_mod_in_error"));
			b.tip(t("packetdoctor.tip.crash.update_fabric_server"));
		}
		b.tip(t("packetdoctor.tip.prev.read_report", file.getFileName()));
	}

	/** The lag monitor saw a tick that never finished before the server went down. */
	private static void stuckTick(Diagnosis.Builder b, LagMonitor.StuckTick stuck, StringBuilder technical) {
		technical.append("The last tick had been running for ").append(LagAnalysis.seconds(stuck.elapsedMs())).append(". Server thread:\n");
		for (String f : stuck.frames()) technical.append("    at ").append(f).append('\n');
		stuckText(b, stuck.causes(), LagAnalysis.seconds(stuck.elapsedMs()));
	}

	private static void stuckText(Diagnosis.Builder b, List<LagCause> causes, @Nullable String elapsed) {
		LagCause top = causes.isEmpty() ? null : causes.getFirst();
		String on = top == null ? "" : " " + t("packetdoctor.crash.watchdog.stuck", LagAnalysis.line(top));
		b.cause("WATCHDOG").headline(t("packetdoctor.prev.stuck.headline"))
				.summary(elapsed != null ? t("packetdoctor.prev.stuck.summary", elapsed, on) : t("packetdoctor.crash.watchdog.summary", on));
		if (top == null) {
			b.source(SourceKind.UNKNOWN, null, t("packetdoctor.crash.watchdog.note"));
		} else {
			b.source(top.mod() != null ? SourceKind.MOD : CrashDiagnoser.sourceKindFor(top.kind()), top.mod() != null ? top.mod() : top.label(),
					t("packetdoctor.crash.watchdog.note"));
			b.tip(top.advice());
		}
		for (LagCause c : causes) b.warnings(List.of(t("packetdoctor.crash.watchdog.cause", LagAnalysis.line(c))));
		b.tip(t("packetdoctor.tip.crash.watchdog_time")).tip(t("packetdoctor.tip.crash.watchdog_lag_command"));
	}

	// --- Files -----------------------------------------------------------------------------

	/** The newest file named {@code prefix...suffix} in {@code dir}, changed after {@code since}. */
	private static @Nullable Path newest(Path dir, String prefix, String suffix, long since) {
		if (!Files.isDirectory(dir)) return null;
		try (Stream<Path> list = Files.list(dir)) {
			return list.filter(p -> {
				String n = p.getFileName().toString();
				return n.startsWith(prefix) && n.endsWith(suffix) && !n.startsWith("debug") && modified(p) >= since;
			}).max(Comparator.comparingLong(PreviousRun::modified)).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	private static List<String> readHead(Path file, int max) {
		List<String> out = new ArrayList<>();
		try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = r.readLine()) != null && out.size() < max) out.add(line);
		} catch (IOException | RuntimeException e) {
			try {
				// hs_err files are in the system's encoding.
				return Files.readAllLines(file, java.nio.charset.Charset.defaultCharset()).stream().limit(max).toList();
			} catch (IOException | RuntimeException ignored) {
			}
		}
		return out;
	}

	/** What the end of the previous run's log shows. */
	private static final class LogTail {
		@Nullable Path file;
		final Deque<String> tail = new ArrayDeque<>();
		final List<String> warnings = new ArrayList<>();
		@Nullable String lastTime;
		boolean outOfMemory;
		boolean watchdog;
		/** The log shows "Done (...)! For help", so the server finished starting. */
		boolean started;
		/** The last error line, for a server that never finished starting. */
		@Nullable String lastError;
		int cantKeepUp;

		List<String> lastLines(int n) {
			List<String> all = new ArrayList<>(tail);
			return all.subList(Math.max(0, all.size() - n), all.size());
		}
	}

	/**
	 * The previous run's log. Minecraft packs {@code latest.log} into a dated {@code .log.gz}
	 * when the next run starts, so it is the newest of those changed since the previous start.
	 */
	private static LogTail previousLog(Path logs, long since) {
		LogTail tail = new LogTail();
		Path file = newest(logs, "", ".log.gz", since);
		if (file == null) return tail;
		tail.file = file;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				tail.tail.addLast(line.length() > 400 ? line.substring(0, 400) : line);
				if (tail.tail.size() > 400) tail.tail.removeFirst();
				if (line.contains("Can't keep up!")) tail.cantKeepUp++;
				if (line.contains("OutOfMemoryError") || line.contains("Out of memory")) tail.outOfMemory = true;
				if (line.contains("A single server tick took")) tail.watchdog = true;
				if (line.contains("Done (") && line.contains("For help")) tail.started = true;
				if (line.contains("/ERROR]") || line.contains("/FATAL]")) tail.lastError = line.length() > 300 ? line.substring(0, 300) : line;
			}
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.debug("Couldn't read {}", file, e);
		}
		List<String> lines = new ArrayList<>(tail.tail);
		for (int i = lines.size() - 1; i >= 0 && tail.lastTime == null; i--) {
			Matcher m = CLOCK.matcher(lines.get(i));
			if (m.find()) tail.lastTime = m.group(1);
		}
		List<String> warn = new ArrayList<>();
		for (int i = lines.size() - 1; i >= 0 && warn.size() < 8; i--) {
			if (LOG_LEVEL.matcher(lines.get(i)).find()) warn.addFirst(lines.get(i).length() > 300 ? lines.get(i).substring(0, 300) : lines.get(i));
		}
		tail.warnings.addAll(warn);
		return tail;
	}
}
