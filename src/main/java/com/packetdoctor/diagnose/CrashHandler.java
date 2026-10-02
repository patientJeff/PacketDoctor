package com.packetdoctor.diagnose;

import com.packetdoctor.PacketDoctor;
import net.minecraft.CrashReport;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs as a crash report is saved. The game or server is about to stop, so the
 * explanation is written to disk and shown on the next start (title screen for players,
 * console and API for servers).
 *
 * <p>This must never make a crash worse: everything is caught, and each side runs once.
 */
public final class CrashHandler {
	private static final AtomicBoolean CLIENT_HANDLED = new AtomicBoolean();
	private static final AtomicBoolean SERVER_HANDLED = new AtomicBoolean();
	/** How many report files to keep; set by whichever side initialized. */
	public static volatile int keepReports = 30;
	/** Told about a server crash explanation (the API fires its event from here). */
	public static volatile @Nullable Consumer<Diagnosis> serverCrashListener;

	private CrashHandler() {
	}

	/** The player's game crashed. */
	public static void onClientCrash(CrashReport report) {
		if (!CLIENT_HANDLED.compareAndSet(false, true)) return;
		handle(report, false, Reports.CLIENT_CRASH);
	}

	/** A dedicated server crashed. */
	public static void onServerCrash(CrashReport report) {
		if (!SERVER_HANDLED.compareAndSet(false, true)) return;
		Diagnosis d = handle(report, true, Reports.SERVER_CRASH);
		Consumer<Diagnosis> listener = serverCrashListener;
		if (d != null && listener != null) {
			try {
				listener.accept(d);
			} catch (Throwable ignored) {
			}
		}
	}

	/**
	 * A dedicated server failed to start. With an exception it is explained like a crash;
	 * without one, from the console lines just before.
	 */
	public static void onServerStartupFailure(String message, @Nullable Throwable thrown) {
		if (!SERVER_HANDLED.compareAndSet(false, true)) return;
		Diagnosis d;
		try {
			d = thrown != null
					? CrashDiagnoser.diagnose(CrashReport.forThrowable(thrown, CrashDiagnoser.STARTUP_TITLE), true)
					: CrashDiagnoser.startupFromConsole(message);
		} catch (Throwable t) {
			PacketDoctor.LOGGER.warn("Couldn't explain why the server didn't start", t);
			return;
		}
		try {
			d = finish(d, true, Reports.SERVER_CRASH);
		} catch (Throwable t) {
			PacketDoctor.LOGGER.warn("Couldn't save the start-up failure explanation", t);
		}
		Consumer<Diagnosis> listener = serverCrashListener;
		if (listener != null) {
			try {
				listener.accept(d);
			} catch (Throwable ignored) {
			}
		}
	}

	private static Diagnosis finish(Diagnosis d, boolean server, String pendingFile) {
		d = Reports.save(d, keepReports);
		Reports.savePendingCrash(pendingFile, d);
		PacketDoctor.LOGGER.error("Packet Doctor: {} -> {}", d.headline(), d.sourceLabel());
		if (server) {
			PacketDoctor.LOGGER.error("  {}", d.summary());
			for (int i = 0; i < d.tips().size(); i++) PacketDoctor.LOGGER.error("  {}. {}", i + 1, d.tips().get(i));
			if (d.reportFile() != null) PacketDoctor.LOGGER.error("  Full report: {}", d.reportFile());
		}
		return d;
	}

	private static @Nullable Diagnosis handle(CrashReport report, boolean server, String pendingFile) {
		try {
			return finish(CrashDiagnoser.diagnose(report, server), server, pendingFile);
		} catch (Throwable t) {
			try {
				PacketDoctor.LOGGER.warn("Couldn't explain the crash", t);
			} catch (Throwable ignored) {
			}
			return null;
		}
	}
}
