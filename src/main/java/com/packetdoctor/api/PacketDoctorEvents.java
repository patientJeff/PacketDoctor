package com.packetdoctor.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

import java.util.UUID;

/**
 * Server-side events. Warning and disconnect listeners run on the server thread, so they
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
}
