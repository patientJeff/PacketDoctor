package com.packetdoctor.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.UUID;

/**
 * Server-side events. All listeners except the crash one run on the server thread, so they
 * may touch the world and players directly. The crash listener runs on the crashing
 * thread while the server goes down: keep it short and never throw.
 *
 * <pre>{@code
 * PacketDoctorEvents.PLAYER_WARNING.register((uuid, name, warning) -> {
 *     if (warning.severity().equals("DANGER")) myGui.flag(uuid, warning.title());
 * });
 * }</pre>
 */
public final class PacketDoctorEvents {
	/** Packet Doctor found a problem with a player's connection (repeats are rate-limited). */
	public static final Event<WarningListener> PLAYER_WARNING = EventFactory.createArrayBacked(WarningListener.class,
			listeners -> (player, name, warning) -> {
				for (WarningListener l : listeners) l.onWarning(player, name, warning);
			});

	/** A player's connection ended, or a login was refused; with the reason worked out. */
	public static final Event<DisconnectListener> PLAYER_DISCONNECT = EventFactory.createArrayBacked(DisconnectListener.class,
			listeners -> info -> {
				for (DisconnectListener l : listeners) l.onDisconnect(info);
			});

	/** The dedicated server crashed; the explanation has been saved. */
	public static final Event<CrashListener> SERVER_CRASH = EventFactory.createArrayBacked(CrashListener.class,
			listeners -> info -> {
				for (CrashListener l : listeners) {
					try {
						l.onServerCrash(info);
					} catch (Throwable ignored) {
						// one broken listener must not stop the others during a crash
					}
				}
			});

	/** Dedicated servers: a lag spike ended, with what it was spent on. Server thread. */
	public static final Event<LagSpikeListener> LAG_SPIKE = EventFactory.createArrayBacked(LagSpikeListener.class,
			listeners -> spike -> {
				for (LagSpikeListener l : listeners) l.onLagSpike(spike);
			});

	/** Dedicated servers: TPS has stayed low for a while. Server thread. */
	public static final Event<PerformanceListener> SERVER_OVERLOADED = EventFactory.createArrayBacked(PerformanceListener.class,
			listeners -> info -> {
				for (PerformanceListener l : listeners) l.onPerformanceChange(info);
			});

	/** Dedicated servers: after being overloaded, the server is keeping up again. Server thread. */
	public static final Event<PerformanceListener> SERVER_RECOVERED = EventFactory.createArrayBacked(PerformanceListener.class,
			listeners -> info -> {
				for (PerformanceListener l : listeners) l.onPerformanceChange(info);
			});

	/**
	 * Dedicated servers: a new kind of warning or error appeared in the console, or an
	 * existing one reached 10, 100 or 1000 repeats ({@link ConsoleProblem#count()}). Server thread.
	 */
	public static final Event<ConsoleProblemListener> CONSOLE_PROBLEM = EventFactory.createArrayBacked(ConsoleProblemListener.class,
			listeners -> problem -> {
				for (ConsoleProblemListener l : listeners) l.onConsoleProblem(problem);
			});

	private PacketDoctorEvents() {
	}

	@FunctionalInterface
	public interface WarningListener {
		void onWarning(UUID player, String playerName, WarningInfo warning);
	}

	@FunctionalInterface
	public interface DisconnectListener {
		void onDisconnect(DisconnectInfo info);
	}

	@FunctionalInterface
	public interface CrashListener {
		void onServerCrash(CrashInfo info);
	}

	@FunctionalInterface
	public interface LagSpikeListener {
		void onLagSpike(LagSpikeInfo spike);
	}

	@FunctionalInterface
	public interface PerformanceListener {
		void onPerformanceChange(PerformanceInfo info);
	}

	@FunctionalInterface
	public interface ConsoleProblemListener {
		void onConsoleProblem(ConsoleProblem problem);
	}
}
