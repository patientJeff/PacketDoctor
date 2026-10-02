package com.packetdoctor.server.console;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.api.ConsoleLine;
import com.packetdoctor.api.ConsoleProblem;
import com.packetdoctor.diagnose.ModBlame;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads everything the server prints to its console, by adding one more Log4j appender
 * next to the console and log file. It keeps the latest lines, groups warnings and errors
 * into {@link ConsoleProblems}, and passes "Can't keep up!" on to the lag monitor.
 *
 * <p>Installed only on dedicated servers, as early as possible, so errors while mods load
 * are caught too. Nothing here may throw into the logging system.
 */
public final class ConsoleCapture {
	private static final String APPENDER_NAME = "PacketDoctorConsole";
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final Pattern CANT_KEEP_UP = Pattern.compile("Running (\\d+)ms or (\\d+) ticks behind");
	private static volatile @Nullable ConsoleCapture instance;

	/** Told about "Can't keep up!" lines: milliseconds and ticks behind. */
	public interface LagListener {
		void onCantKeepUp(long msBehind, long ticksBehind);
	}

	/** Told when the server fails to start, with the exception if the line had one. */
	public interface StartupListener {
		void onStartupFailure(String message, @Nullable Throwable thrown);
	}

	/** Minecraft's own messages for a server that gives up while starting. */
	private static final List<String> STARTUP_FAILURES = List.of("Failed to start the minecraft server",
			"Failed to load datapacks, can't proceed", "Failed to load world data", "Failed to initialize server");

	private final ArrayDeque<ConsoleLine> lines = new ArrayDeque<>();
	private final ConsoleProblems problems;
	private final Map<String, String> modByLogger = new HashMap<>();
	private volatile int maxLines;
	private volatile @Nullable LagListener lagListener;
	private volatile @Nullable StartupListener startupListener;

	private ConsoleCapture(int maxLines, Consumer<ConsoleProblem> problemListener) {
		this.maxLines = maxLines;
		this.problems = new ConsoleProblems(problemListener);
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (ModBlame.isPlatform(id)) continue;
			modByLogger.put(id.toLowerCase(Locale.ROOT), id);
			modByLogger.put(mod.getMetadata().getName().toLowerCase(Locale.ROOT), id);
		}
	}

	/** Starts capturing. Safe to call once; later calls only change the settings. */
	public static synchronized void install(int maxLines, Consumer<ConsoleProblem> problemListener) {
		if (instance != null) {
			instance.maxLines = maxLines;
			return;
		}
		ConsoleCapture capture = new ConsoleCapture(maxLines, problemListener);
		try {
			// The context of Log4j's own class loader is the one Minecraft configured.
			LoggerContext ctx = (LoggerContext) LogManager.getContext(LogManager.class.getClassLoader(), false);
			Configuration config = ctx.getConfiguration();
			Appender appender = new Appender(capture);
			appender.start();
			config.addAppender(appender);
			config.getRootLogger().addAppender(appender, Level.INFO, null);
			ctx.updateLoggers();
			instance = capture;
		} catch (Throwable t) {
			PacketDoctor.LOGGER.warn("Couldn't watch the server console; console problems won't be explained", t);
		}
	}

	public static @Nullable ConsoleCapture get() {
		return instance;
	}

	public void setLagListener(@Nullable LagListener listener) {
		this.lagListener = listener;
	}

	public void setStartupListener(@Nullable StartupListener listener) {
		this.startupListener = listener;
	}

	private static final class Appender extends AbstractAppender {
		private final ConsoleCapture capture;

		Appender(ConsoleCapture capture) {
			super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
			this.capture = capture;
		}

		@Override
		public void append(LogEvent event) {
			try {
				capture.accept(event);
			} catch (Throwable ignored) {
				// Never break logging.
			}
		}
	}

	private void accept(LogEvent event) {
		Level level = event.getLevel();
		String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
		String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
		if (message == null) message = "";
		Throwable thrown = event.getThrown();
		boolean warnOrWorse = level.isMoreSpecificThan(Level.WARN);

		String errorText = null;
		String mod = null;
		if (thrown != null) {
			errorText = ConsoleProblems.shorten(thrown.getClass().getName() + ": " + thrown.getMessage(), 300);
			ModBlame.Culprit c = ModBlame.prime(ModBlame.blame(thrown, true));
			if (c != null) mod = c.label();
		}
		if (mod == null) mod = modForLogger(logger);

		ConsoleLine line = new ConsoleLine(event.getTimeMillis(), level.name(), logger, event.getThreadName() == null ? "" : event.getThreadName(),
				ConsoleProblems.shorten(message, 2000), errorText, mod);
		synchronized (lines) {
			lines.addLast(line);
			while (lines.size() > maxLines) lines.removeFirst();
		}

		if (message.startsWith("Can't keep up!")) {
			Matcher m = CANT_KEEP_UP.matcher(message);
			LagListener l = lagListener;
			if (m.find() && l != null) l.onCantKeepUp(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)));
		}
		if (warnOrWorse) {
			StartupListener s = startupListener;
			if (s != null) {
				for (String f : STARTUP_FAILURES) {
					if (message.startsWith(f)) {
						s.onStartupFailure(message, thrown);
						break;
					}
				}
			}
		}
		// Our own lines (lag spike notes, explanations) are not problems in themselves.
		if (warnOrWorse && !logger.equals(PacketDoctor.LOGGER.getName())) {
			problems.add(event.getTimeMillis(), level.isMoreSpecificThan(Level.ERROR), shortLogger(logger), message, errorText, mod);
		}
	}

	/** The mod a logger belongs to: loggers are usually named after the mod or one of its classes. */
	private @Nullable String modForLogger(String logger) {
		if (logger.isEmpty()) return null;
		String id = modByLogger.get(logger.toLowerCase(Locale.ROOT));
		if (id == null && logger.indexOf('.') > 0) {
			String byClass = ModBlame.modForClass(logger);
			if (byClass != null && !ModBlame.isPlatform(byClass) && !byClass.equals(PacketDoctor.MOD_ID)) id = byClass;
		}
		if (id == null) return null;
		String name = ModBlame.name(id);
		return name.equals(id) ? name : name + " (" + id + ")";
	}

	private static String shortLogger(String logger) {
		int dot = logger.lastIndexOf('.');
		return dot >= 0 && dot < logger.length() - 1 ? logger.substring(dot + 1) : logger;
	}

	// --- Reading -----------------------------------------------------------------------

	/** The latest lines at {@code minLevel} or more serious, oldest first. */
	public List<ConsoleLine> lines(int max, Level minLevel) {
		List<ConsoleLine> out = new ArrayList<>();
		synchronized (lines) {
			var it = lines.descendingIterator();
			while (it.hasNext() && out.size() < max) {
				ConsoleLine l = it.next();
				Level lv = Level.toLevel(l.level(), Level.INFO);
				if (lv.isMoreSpecificThan(minLevel)) out.add(l);
			}
		}
		return out.reversed();
	}

	/** Warnings and errors between two times, oldest first (for lag spikes and crashes). */
	public List<ConsoleLine> warningsBetween(long from, long to, int max) {
		List<ConsoleLine> out = new ArrayList<>();
		synchronized (lines) {
			var it = lines.descendingIterator();
			while (it.hasNext() && out.size() < max) {
				ConsoleLine l = it.next();
				if (l.time() < from) break;
				if (l.time() > to || l.logger().equals(PacketDoctor.LOGGER.getName())) continue;
				if (Level.toLevel(l.level(), Level.INFO).isMoreSpecificThan(Level.WARN)) out.add(l);
			}
		}
		return out.reversed();
	}

	public List<ConsoleProblem> problems() {
		return problems.all();
	}

	public List<ConsoleProblem> recentProblemsWithMod(long since) {
		return problems.recentWithMod(since);
	}

	public void clearProblems() {
		problems.clear();
	}

	/** "[12:03:01 ERROR] Logger: message (Mod)" for reports. */
	public static String format(ConsoleLine l) {
		StringBuilder sb = new StringBuilder("[").append(CLOCK.format(Instant.ofEpochMilli(l.time()))).append(' ').append(l.level()).append("] ");
		sb.append(shortLogger(l.logger())).append(": ").append(ConsoleProblems.shorten(l.message(), 300));
		if (l.error() != null) sb.append(" | ").append(l.error());
		if (l.mod() != null) sb.append(" (").append(l.mod()).append(')');
		return sb.toString();
	}
}
