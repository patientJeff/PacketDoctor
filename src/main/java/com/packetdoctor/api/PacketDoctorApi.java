package com.packetdoctor.api;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.diagnose.ModBlame;
import com.packetdoctor.net.PacketExport;
import com.packetdoctor.net.ServerMonitor;
import com.packetdoctor.net.Warning;
import com.packetdoctor.server.PacketDoctorServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side API for other mods (such as an admin GUI). Everything here reads snapshots:
 * the returned records are plain, immutable data, safe to keep and pass between threads.
 *
 * <p>Use it only when Packet Doctor is installed, so it stays an optional dependency:
 * <pre>{@code
 * if (FabricLoader.getInstance().isModLoaded("packetdoctor") && PacketDoctorApi.VERSION >= 1) {
 *     for (PlayerReport p : PacketDoctorApi.players()) ...
 * }
 * }</pre>
 * Calls are safe from any thread, but {@link #kick} should be called on the server thread.
 * On a player's own game (no server running) the methods return empty results.
 * Events are in {@link PacketDoctorEvents}.
 */
public final class PacketDoctorApi {
	/** Raised when the API gains features; check it before using newer methods. */
	public static final int VERSION = 1;

	private PacketDoctorApi() {
	}

	/** True while a server (dedicated or singleplayer) is running with Packet Doctor watching it. */
	public static boolean isRunning() {
		return PacketDoctorServer.server() != null && ServerMonitor.get() != null;
	}

	// --- Players ----------------------------------------------------------------------

	/** Every online player's connection, busiest first. */
	public static List<PlayerReport> players() {
		ServerMonitor m = ServerMonitor.get();
		if (m == null) return List.of();
		List<PlayerReport> out = new ArrayList<>();
		for (ServerMonitor.PlayerSession s : m.onlineSessions()) out.add(s.report());
		out.sort(Comparator.comparingDouble(PlayerReport::packetsFromPlayerPerSecond).reversed());
		return out;
	}

	/** One online player's connection. */
	public static Optional<PlayerReport> player(UUID player) {
		ServerMonitor m = ServerMonitor.get();
		ServerMonitor.PlayerSession s = m == null ? null : m.online(player);
		return s == null ? Optional.empty() : Optional.of(s.report());
	}

	/** Whether the player's game runs Packet Doctor (and so is told exactly why it gets kicked). */
	public static boolean hasClientMod(UUID player) {
		ServerMonitor m = ServerMonitor.get();
		ServerMonitor.PlayerSession s = m == null ? null : m.online(player);
		return s != null && s.hasClientMod();
	}

	/** Warnings about a player this session (or their last session, if they left), newest first. */
	public static List<WarningInfo> warnings(UUID player) {
		ServerMonitor.PlayerSession s = session(player);
		if (s == null) return List.of();
		return s.warnings().all().stream().map(ServerMonitor::info).toList();
	}

	/** The latest warnings across all online players, newest first. */
	public static List<PlayerWarning> recentWarnings(int max) {
		ServerMonitor m = ServerMonitor.get();
		if (m == null) return List.of();
		List<PlayerWarning> out = new ArrayList<>();
		for (ServerMonitor.PlayerSession s : m.onlineSessions()) {
			for (Warning w : s.warnings().all()) out.add(new PlayerWarning(s.uuid, s.name, ServerMonitor.info(w)));
		}
		out.sort(Comparator.comparingLong((PlayerWarning p) -> p.warning().lastSeen()).reversed());
		return out.size() > max ? List.copyOf(out.subList(0, Math.max(0, max))) : out;
	}

	public static void clearWarnings(UUID player) {
		ServerMonitor.PlayerSession s = session(player);
		if (s != null) s.warnings().clear();
	}

	/**
	 * Writes the player's recent packets, warnings and totals to a text file in
	 * {@code <server>/packetdoctor/reports} and returns its path.
	 */
	public static Optional<Path> exportPacketLog(UUID player) {
		ServerMonitor.PlayerSession s = session(player);
		MinecraftServer server = PacketDoctorServer.server();
		if (s == null) return Optional.empty();
		try {
			String where = server == null ? null : server.isDedicatedServer() ? "dedicated server" : "singleplayer";
			return Optional.of(PacketExport.write(s.name, where, s.log(), s.warnings().all(), PacketDoctorServer.config().keepReports));
		} catch (IOException e) {
			PacketDoctor.LOGGER.warn("Couldn't export {}'s packet log", s.name, e);
			return Optional.empty();
		}
	}

	private static ServerMonitor.@Nullable PlayerSession session(UUID player) {
		ServerMonitor m = ServerMonitor.get();
		return m == null ? null : m.any(player);
	}

	// --- Disconnects and kicks --------------------------------------------------------

	/** The latest disconnects and refused logins, newest first. */
	public static List<DisconnectInfo> recentDisconnects(int max) {
		ServerMonitor m = ServerMonitor.get();
		return m == null ? List.of() : m.disconnects(max);
	}

	/** The most recent disconnect of this player, if still remembered. */
	public static Optional<DisconnectInfo> lastDisconnect(UUID player) {
		for (DisconnectInfo d : recentDisconnects(Integer.MAX_VALUE)) {
			if (player.equals(d.uuid())) return Optional.of(d);
		}
		return Optional.empty();
	}

	/**
	 * Kicks a player, credited to your mod. A player whose game has Packet Doctor is told
	 * that your mod removed them, and why; everyone else just sees {@code message}.
	 *
	 * @param modId  your mod's id, e.g. {@code mymod}
	 * @param reason the plain reason to show in the explanation (may be null)
	 */
	public static void kick(ServerPlayer player, Component message, String modId, @Nullable String reason) {
		setKickReason(player.getUUID(), modId, reason);
		player.connection.disconnect(message);
	}

	/**
	 * Credits the player's next kick (within 15 seconds) to your mod, for when you kick
	 * them some other way. Without this, Packet Doctor still finds your mod on the call stack.
	 */
	public static void setKickReason(UUID player, String modId, @Nullable String reason) {
		ServerMonitor m = ServerMonitor.get();
		if (m != null) m.setKickReason(player, ModBlame.name(modId) + " (" + modId + ")", reason);
	}

	// --- Server crashes and settings ----------------------------------------------------

	/** The last dedicated-server crash explanation (from this run, or the previous one). */
	public static Optional<CrashInfo> lastServerCrash() {
		return Optional.ofNullable(PacketDoctorServer.lastCrash());
	}

	/** All server settings by name (see {@code config/packetdoctor-server.json}). */
	public static Map<String, Object> settings() {
		return PacketDoctorServer.config().asMap();
	}

	/**
	 * Changes one server setting and saves it. Returns false for an unknown name or a value
	 * of the wrong type (booleans for switches, numbers for sizes, a severity name for
	 * {@code alertMinSeverity}).
	 */
	public static boolean set(String name, Object value) {
		return PacketDoctorServer.config().set(name, value);
	}
}
