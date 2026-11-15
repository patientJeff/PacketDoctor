package com.packetdoctor.server;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.diagnose.Diagnosis;
import com.packetdoctor.diagnose.Reports;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps every file a server error leaves behind, in {@code packetdoctor/errors}. Nothing
 * here is ever deleted automatically (unlike the explanations in {@code reports}).
 *
 * <ul>
 *   <li>{@code console/errors-<day>.log}: every console error, and every warning that came
 *       with an exception, with its full stack trace and the mod it came from.</li>
 *   <li>{@code incidents/<time>-<cause>/}: one folder per crash, watchdog freeze, start-up
 *       failure or unexpected stop, holding the explanation, Minecraft's crash report, the
 *       server log, Java's {@code hs_err_pid} crash file and the mod list.</li>
 * </ul>
 *
 * <p>A crash's folder is filled in two steps: at the crash (explanation, crash report, the
 * log so far) and on the next start (the finished log, and files written after the hook ran,
 * such as the watchdog's crash report or Java's own crash file).
 *
 * <p>Console errors arrive inside the logging system, so nothing in that path may log or throw.
 */
public final class ErrorArchive {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	/** Names the incident folder of a crash, so the next start can add the files that came after. */
	private static final String PENDING = "pending-incident.txt";
	private static final Object LOG_LOCK = new Object();

	private static Supplier<ServerConfig> config = ServerConfig::new;
	private static @Nullable String logDay;
	private static long logBytes;
	private static boolean logFull;
	/** When this run began; files from before it belong to the previous run. */
	private static long thisRunStart = System.currentTimeMillis();
	/** The previous run's crash folder, read before this run can crash and name its own. */
	private static @Nullable Path previousIncident;

	private ErrorArchive() {
	}

	/** Called as the mod loads, before anything this run does can crash. */
	public static void init(Supplier<ServerConfig> settings) {
		config = settings;
		thisRunStart = System.currentTimeMillis();
		try {
			Path pending = folder().resolve(PENDING);
			if (Files.exists(pending)) {
				Path named = Path.of(Files.readString(pending).trim());
				if (Files.isDirectory(named)) previousIncident = named;
			}
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.debug("Couldn't read {}", PENDING, e);
		}
	}

	public static Path folder() {
		return Reports.folder().resolve("errors");
	}

	private static boolean enabled() {
		return config.get().keepErrorFiles;
	}

	// --- Console errors --------------------------------------------------------------------

	/** One console error, written straight away so it survives a crash right after. */
	public static void consoleError(long time, String level, String logger, String thread, String message,
									@Nullable Throwable thrown, @Nullable String mod) {
		try {
			if (!enabled()) return;
			StringBuilder sb = new StringBuilder("[").append(LINE_TIME.format(Instant.ofEpochMilli(time))).append("] [")
					.append(thread).append('/').append(level).append("] [").append(logger).append("] ").append(message).append('\n');
			if (mod != null) sb.append("    From: ").append(mod).append('\n');
			if (thrown != null) {
				StringWriter trace = new StringWriter();
				thrown.printStackTrace(new PrintWriter(trace));
				sb.append(trace.toString().indent(4));
			}
			sb.append('\n');
			byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

			synchronized (LOG_LOCK) {
				String day = DAY.format(Instant.ofEpochMilli(time));
				Path file = folder().resolve("console").resolve("errors-" + day + ".log");
				if (!day.equals(logDay)) {
					logDay = day;
					logBytes = Files.exists(file) ? Files.size(file) : 0;
					logFull = false;
				}
				if (logFull) return;
				long max = config.get().errorLogMaxMbPerDay * 1024L * 1024L;
				if (logBytes + bytes.length > max) {
					// An error printed in a loop mustn't fill the disk.
					logFull = true;
					bytes = ("[" + LINE_TIME.format(Instant.ofEpochMilli(time)) + "] Packet Doctor: this file reached "
							+ config.get().errorLogMaxMbPerDay + " MB (errorLogMaxMbPerDay); further errors today are only in the server log.\n")
							.getBytes(StandardCharsets.UTF_8);
				}
				Files.createDirectories(file.getParent());
				Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				logBytes += bytes.length;
			}
		} catch (Throwable ignored) {
			// Never break logging.
		}
	}

	// --- Incidents ----------------------------------------------------------------------------

	/**
	 * The server crashed, froze (watchdog) or failed to start. It is about to stop, so
	 * everything available now is saved at once.
	 */
	public static void serverCrash(@Nullable Diagnosis d, @Nullable CrashReport report) {
		try {
			if (!enabled()) return;
			Path dir = newIncident(d, "crash");
			if (d != null) Files.writeString(dir.resolve("explanation.txt"), Reports.render(d));
			if (report != null) {
				try {
					Files.writeString(dir.resolve("crash-report.txt"), report.getFriendlyReport(ReportType.CRASH));
				} catch (Throwable t) {
					PacketDoctor.LOGGER.warn("Couldn't keep the crash report", t);
				}
			}
			gzipCopy(gameDir().resolve("logs").resolve("latest.log"), dir.resolve("latest-at-crash.log.gz"));
			writeModList(dir);
			Files.writeString(folder().resolve(PENDING), dir.toAbsolutePath().toString());
			PacketDoctor.LOGGER.error("  Error files kept in: {}", dir.toAbsolutePath());
		} catch (Throwable t) {
			try {
				PacketDoctor.LOGGER.warn("Couldn't keep the crash's files", t);
			} catch (Throwable ignored) {
			}
		}
	}

	/**
	 * The previous run didn't stop normally. Adds what it left behind to its crash's folder,
	 * or to a new one when no crash was seen (the process was killed, or Java itself crashed).
	 */
	public static void afterUncleanRun(@Nullable Diagnosis d, long previousStart) {
		if (!enabled()) return;
		try {
			Path dir = previousIncident;
			previousIncident = null;
			if (dir != null) {
				// Only if it still names the previous run's folder: this run may have crashed since.
				Path pending = folder().resolve(PENDING);
				if (Files.exists(pending) && Path.of(Files.readString(pending).trim()).equals(dir)) Files.deleteIfExists(pending);
			} else {
				dir = newIncident(d, "stop");
				if (d != null) Files.writeString(dir.resolve("explanation.txt"), Reports.render(d));
				writeModList(dir);
			}
			long since = previousStart - 5_000;
			int copied = 0;
			copied += copyBetween(gameDir().resolve("crash-reports"), "crash-", ".txt", since, dir);
			copied += copyBetween(gameDir(), "hs_err_pid", ".log", since, dir);
			copied += copyBetween(gameDir(), "replay_pid", ".log", since, dir);
			copied += copyBetween(gameDir().resolve("logs"), "", ".log.gz", since, dir);
			PacketDoctor.LOGGER.warn("  Error files from the last run ({} added) kept in: {}", copied, dir.toAbsolutePath());
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.warn("Couldn't keep the last run's error files", e);
		}
	}

	private static Path newIncident(@Nullable Diagnosis d, String fallback) throws IOException {
		long time = d != null ? d.time() : System.currentTimeMillis();
		String cause = d != null && d.cause() != null ? d.cause() : fallback;
		String base = FILE_TIME.format(Instant.ofEpochMilli(time)) + "-" + cause.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
		Path root = folder().resolve("incidents");
		Path dir = root.resolve(base);
		for (int i = 2; Files.exists(dir); i++) dir = root.resolve(base + "-" + i);
		Files.createDirectories(dir);
		return dir;
	}

	/**
	 * Copies the files named {@code prefix...suffix} changed during the previous run, keeping
	 * their names. Its log is packed into a {@code .log.gz} as logging starts, before this
	 * run's start time is taken, so it counts as the previous run's.
	 */
	private static int copyBetween(Path from, String prefix, String suffix, long since, Path to) {
		if (!Files.isDirectory(from)) return 0;
		List<Path> files;
		try (Stream<Path> list = Files.list(from)) {
			files = new ArrayList<>(list.filter(p -> {
				String n = p.getFileName().toString();
				long m = modified(p);
				return Files.isRegularFile(p) && n.startsWith(prefix) && n.endsWith(suffix) && !n.startsWith("debug") && m >= since && m <= thisRunStart;
			}).toList());
		} catch (IOException e) {
			return 0;
		}
		files.sort(Comparator.comparingLong(ErrorArchive::modified));
		int copied = 0;
		for (Path f : files) {
			Path target = to.resolve(f.getFileName().toString());
			if (Files.exists(target)) continue;
			try {
				Files.copy(f, target);
				copied++;
			} catch (IOException e) {
				PacketDoctor.LOGGER.debug("Couldn't copy {}", f, e);
			}
		}
		return copied;
	}

	/** The log is still being written, and can be large, so it is packed while copying. */
	private static void gzipCopy(Path from, Path to) {
		if (!Files.isRegularFile(from)) return;
		try (InputStream in = Files.newInputStream(from); OutputStream out = new GZIPOutputStream(Files.newOutputStream(to))) {
			in.transferTo(out);
		} catch (IOException e) {
			PacketDoctor.LOGGER.debug("Couldn't copy {}", from, e);
		}
	}

	private static void writeModList(Path dir) throws IOException {
		StringBuilder sb = new StringBuilder();
		Runtime rt = Runtime.getRuntime();
		sb.append("Java ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
		sb.append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
		sb.append("Max memory: ").append(rt.maxMemory() / (1024 * 1024)).append(" MB, processors: ").append(rt.availableProcessors()).append("\n\n");
		sb.append("Mods:\n");
		List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
		mods.sort(Comparator.comparing(m -> m.getMetadata().getId()));
		for (ModContainer m : mods) {
			if (m.getContainingMod().isPresent()) continue; // Bundled libraries are listed under their mod's jar anyway.
			sb.append("  ").append(m.getMetadata().getId()).append(' ').append(m.getMetadata().getVersion().getFriendlyString())
					.append("  (").append(m.getMetadata().getName()).append(")\n");
		}
		Files.writeString(dir.resolve("mods.txt"), sb);
	}

	private static Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}
}
