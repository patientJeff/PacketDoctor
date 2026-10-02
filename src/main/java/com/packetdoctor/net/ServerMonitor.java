package com.packetdoctor.net;

import com.mojang.authlib.GameProfile;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.Tr;
import com.packetdoctor.api.DisconnectCause;
import com.packetdoctor.api.DisconnectInfo;
import com.packetdoctor.api.PlayerReport;
import com.packetdoctor.api.WarningInfo;
import com.packetdoctor.diagnose.ModBlame;
import com.packetdoctor.network.ExplanationPayload;
import com.packetdoctor.network.ServerExplanation;
import com.packetdoctor.server.ServerConfig;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.SkipPacketException;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The server's side: one {@link PlayerSession} per connected player, the same packet
 * checks the player's game runs (worded for admins), and a record of every disconnect.
 *
 * <p>When the server disconnects a player whose game has Packet Doctor, it first sends a
 * {@link ServerExplanation} saying who did it and why. Players without the mod are never
 * sent anything extra.
 */
public final class ServerMonitor implements ConnectionObserver {
	private static final long CONTEXT_MS = 60_000;
	private static final long KICK_REASON_MS = 15_000;
	private static final int MAX_PENDING = 8192;
	private static final int MAX_ENDED = 50;
	private static volatile @Nullable ServerMonitor instance;

	/** Receives what the monitor finds; implemented by the server entrypoint. */
	public interface Listener {
		void onWarning(PlayerSession session, Warning warning);

		void onDisconnect(DisconnectInfo info);
	}

	private record Pending(PlayerSession session, PacketRecord record) {
	}

	private record KickReason(String kicker, @Nullable String reason, long time) {
	}

	/** Who ended a connection, captured when the server disconnects a player. */
	private record Kick(String kind, @Nullable String kicker, @Nullable String reason, boolean stopping, long time) {
	}

	private final Supplier<ServerConfig> config;
	private final Listener listener;
	private final Map<UUID, PlayerSession> online = new ConcurrentHashMap<>();
	private final Map<Connection, PlayerSession> byConnection = Collections.synchronizedMap(new WeakHashMap<>());
	private final Map<UUID, PlayerSession> ended = Collections.synchronizedMap(new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, PlayerSession> eldest) {
			return size() > MAX_ENDED;
		}
	});
	private final Deque<DisconnectInfo> disconnects = new ArrayDeque<>();
	private final Map<Object, Integer> decodedSizes = Collections.synchronizedMap(new IdentityHashMap<>());
	private final Map<Object, Pending> unsized = Collections.synchronizedMap(new IdentityHashMap<>());
	private final Map<UUID, KickReason> kickReasons = new ConcurrentHashMap<>();

	private ServerMonitor(Supplier<ServerConfig> config, Listener listener) {
		this.config = config;
		this.listener = listener;
	}

	/** Called by the common entrypoint; also registers with {@link NetHooks}. */
	public static void init(Supplier<ServerConfig> config, Listener listener) {
		ServerMonitor monitor = new ServerMonitor(config, listener);
		instance = monitor;
		NetHooks.setServer(monitor);
	}

	public static @Nullable ServerMonitor get() {
		return instance;
	}

	/** Forgets everything (a new server is starting, e.g. another singleplayer world). */
	public void reset() {
		crash = null;
		online.clear();
		byConnection.clear();
		ended.clear();
		synchronized (disconnects) {
			disconnects.clear();
		}
		decodedSizes.clear();
		unsized.clear();
		kickReasons.clear();
	}

	// --- Sessions -------------------------------------------------------------------

	private @Nullable PlayerSession session(Connection connection) {
		PlayerSession s = byConnection.get(connection);
		if (s != null) return s;
		if (!(connection.getPacketListener() instanceof ServerCommonPacketListenerImpl l)) return null;
		GameProfile profile = l.getOwner();
		PlayerSession created = online.compute(profile.id(), (id, old) ->
				old != null && old.connection.get() == connection ? old : new PlayerSession(this, profile.id(), profile.name(), connection));
		byConnection.put(connection, created);
		return created;
	}

	public @Nullable PlayerSession online(UUID id) {
		return online.get(id);
	}

	/** The online session, or the most recent ended one. */
	public @Nullable PlayerSession any(UUID id) {
		PlayerSession s = online.get(id);
		return s != null ? s : ended.get(id);
	}

	public @Nullable PlayerSession findByName(String name) {
		for (PlayerSession s : online.values()) if (s.name.equalsIgnoreCase(name)) return s;
		synchronized (ended) {
			List<PlayerSession> list = new ArrayList<>(ended.values());
			for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).name.equalsIgnoreCase(name)) return list.get(i);
		}
		return null;
	}

	public List<PlayerSession> onlineSessions() {
		return List.copyOf(online.values());
	}

	public List<String> knownNames() {
		List<String> names = new ArrayList<>();
		for (PlayerSession s : online.values()) names.add(s.name);
		synchronized (ended) {
			for (PlayerSession s : ended.values()) if (!names.contains(s.name)) names.add(s.name);
		}
		return names;
	}

	public List<DisconnectInfo> disconnects(int max) {
		synchronized (disconnects) {
			return disconnects.stream().limit(Math.max(0, max)).toList();
		}
	}

	private boolean monitoring() {
		return config.get().monitorPackets;
	}

	// --- Packets ----------------------------------------------------------------------

	@Override
	public void onDecoded(ProtocolInfo<?> protocol, Object packet, int size) {
		if (!monitoring() || protocol.id() == ConnectionProtocol.STATUS || protocol.id() == ConnectionProtocol.HANDSHAKING) return;
		if (decodedSizes.size() > MAX_PENDING) decodedSizes.clear();
		decodedSizes.put(packet, size);
	}

	/** A packet from a player's game reached the server. */
	@Override
	public void onInbound(Connection connection, Packet<?> packet) {
		Integer size = decodedSizes.remove(packet);
		if (!monitoring()) return;
		PlayerSession s = session(connection);
		if (s == null) return;
		long now = System.currentTimeMillis();
		String id = PacketNames.id(packet);
		PacketRecord record = new PacketRecord(now, Direction.OUT, phase(connection), id, size == null ? -1 : size);
		s.log.add(record);
		s.fromPlayer.record(PacketNames.path(id), now);
		PacketRules.fromPlayer(s, packet, record);
		if (size != null) PacketRules.fromPlayerSize(s, record, size);
	}

	/** The server is sending a packet to a player. */
	@Override
	public void onOutbound(Connection connection, Packet<?> packet) {
		if (!monitoring()) return;
		PlayerSession s = session(connection);
		if (s == null) return;
		long now = System.currentTimeMillis();
		String phase = phase(connection);
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> sub : bundle.subPackets()) outboundOne(connection, s, sub, phase, now);
		} else {
			outboundOne(connection, s, packet, phase, now);
		}
	}

	private void outboundOne(Connection connection, PlayerSession s, Packet<?> packet, String phase, long now) {
		String id = PacketNames.id(packet);
		String path = PacketNames.path(id);
		PacketRecord record = new PacketRecord(now, Direction.IN, phase, id, -1);
		s.log.add(record);
		s.toPlayer.record(path, now);
		// The server moved the player itself, so the next position they send isn't a suspicious jump.
		if (path.equals("player_position") || path.equals("respawn") || path.equals("login")) s.resetMovement();
		if (!connection.isMemoryConnection()) {
			if (unsized.size() > MAX_PENDING) unsized.clear();
			unsized.put(packet, new Pending(s, record));
		}
	}

	@Override
	public void onEncoded(ProtocolInfo<?> protocol, Object packet, int size) {
		Pending p = unsized.remove(packet);
		if (p == null) return;
		p.session.log.sized(p.record, size);
		PacketRules.toPlayerSize(p.session, p.record, size);
	}

	private static String phase(Connection connection) {
		PacketListener l = connection.getPacketListener();
		return l == null ? "?" : l.protocol().id();
	}

	// --- Errors -----------------------------------------------------------------------

	@Override
	public void onNetworkException(Connection connection, Throwable cause) {
		PlayerSession s = session(connection);
		if (s == null) return;
		s.error = cause;
		s.errorTime = System.currentTimeMillis();
		if (cause instanceof SkipPacketException) {
			s.warn(s.w("srv.skipped", Severity.WARNING).detail(PacketRules.shortMessage(cause)).source("packetdoctor.source.player"), null);
		} else if (cause instanceof DecoderException) {
			s.warn(s.w("srv.decode", Severity.DANGER).detail(PacketRules.shortMessage(cause)).source("packetdoctor.source.player"), null);
		}
	}

	/** The server failed while handling a packet from this player (server thread). */
	public void onPacketError(ServerCommonPacketListenerImpl listener, Packet<?> packet, Throwable cause) {
		PlayerSession s = online.get(listener.getOwner().id());
		if (s == null) return;
		s.error = cause;
		s.errorTime = System.currentTimeMillis();
		String id = PacketNames.id(packet);
		ModBlame.Culprit mod = ModBlame.prime(ModBlame.blame(cause, true));
		s.warn(s.w("srv.handle", Severity.DANGER).detail(PacketNames.describe(id), PacketRules.shortMessage(cause))
				.packet(Direction.OUT, id).mod(mod == null ? null : mod.label()), null);
	}

	@Override
	public void onChannelInactive(Connection connection) {
	}

	@Override
	public void onDisconnect(Connection connection, DisconnectionDetails details) {
		// The player-level hooks below see more (who kicked, which player), so nothing here.
	}

	// --- Kicks and disconnects --------------------------------------------------------

	/** A mod asked (through the API) to attach its reason to this player's next kick. */
	public void setKickReason(UUID player, String kicker, @Nullable String reason) {
		kickReasons.put(player, new KickReason(kicker, reason, System.currentTimeMillis()));
	}

	/**
	 * The server is disconnecting a player (any thread). Works out who asked, then tells
	 * the player's game, if it has Packet Doctor, before the disconnect packet goes out.
	 */
	public void onKick(ServerCommonPacketListenerImpl listener, DisconnectionDetails details) {
		UUID id = listener.getOwner().id();
		PlayerSession s = online.get(id);
		if (s != null && s.kick != null && System.currentTimeMillis() - s.kick.time() < 5000) return; // already handled

		Kick kick = classifyKick(new Throwable().getStackTrace());
		KickReason given = kickReasons.remove(id);
		if (given != null && System.currentTimeMillis() - given.time() < KICK_REASON_MS) {
			kick = new Kick("MOD", given.kicker(), given.reason(), kick.stopping(), kick.time());
		}
		if (s != null) s.kick = kick;

		if (!config.get().sendExplanations) return;
		boolean sent = sendExplanation(listener, s, kick);
		if (s != null) s.explanationSent = sent;
	}

	private static Kick classifyKick(StackTraceElement[] stack) {
		long now = System.currentTimeMillis();
		for (StackTraceElement f : stack) {
			if (f.getClassName().endsWith(".KickCommand")) return new Kick("OPERATOR", null, null, false, now);
			if (f.getMethodName().equals("stopServer") || f.getMethodName().equals("removeAll")) return new Kick("SERVER", null, null, true, now);
		}
		ModBlame.Culprit mod = ModBlame.prime(ModBlame.blame(stack, true));
		if (mod != null) return new Kick("MOD", mod.label(), null, false, now);
		return new Kick("SERVER", null, null, false, now);
	}

	/** The server's crash, as players are told about it. */
	private record CrashNote(String kind, String headline, String source) {
	}

	private volatile @Nullable CrashNote crash;

	/**
	 * The server crashed (any thread, also the watchdog's while the server thread is stuck).
	 * Every player with Packet Doctor is told now, because after a watchdog crash the server
	 * can't kick anyone: their games only see the connection die. The shutdown kicks that may
	 * follow don't send a second, vaguer explanation.
	 */
	public void onServerCrash(String kind, String headline, String source) {
		crash = new CrashNote(kind, headline, source);
		if (!config.get().sendExplanations) return;
		long now = System.currentTimeMillis();
		for (PlayerSession s : online.values()) {
			Connection c = s.connection.get();
			if (c == null || !(c.getPacketListener() instanceof ServerCommonPacketListenerImpl listener)) continue;
			Kick kick = new Kick("SERVER", null, null, true, now);
			s.kick = kick;
			s.explanationSent = sendExplanation(listener, s, kick);
		}
	}

	private boolean sendExplanation(ServerCommonPacketListenerImpl listener, @Nullable PlayerSession s, Kick kick) {
		try {
			boolean play = listener instanceof ServerGamePacketListenerImpl;
			boolean canSend = play
					? ServerPlayNetworking.canSend((ServerGamePacketListenerImpl) listener, ExplanationPayload.TYPE)
					: listener instanceof ServerConfigurationPacketListenerImpl c && ServerConfigurationNetworking.canSend(c, ExplanationPayload.TYPE);
			if (!canSend) return false;

			String error = s != null && s.error != null && System.currentTimeMillis() - s.errorTime < CONTEXT_MS
					? PacketRules.shortMessage(s.error) : null;
			List<WarningData> warnings = new ArrayList<>();
			if (s != null) {
				for (Warning w : s.warnings.since(CONTEXT_MS)) {
					if (warnings.size() >= 12) break;
					warnings.add(w.toData());
				}
			}
			CrashNote c = crash;
			ServerExplanation explanation = new ServerExplanation(ServerExplanation.CURRENT, kick.kind(), kick.kicker(),
					kick.reason(), error, warnings, PacketDoctor.version(), c == null ? null : c.kind(), c == null ? null : c.headline(),
					c == null ? null : c.source());
			ExplanationPayload payload = new ExplanationPayload(explanation.toJson());
			listener.send(play ? ServerPlayNetworking.createClientboundPacket(payload) : ServerConfigurationNetworking.createClientboundPacket(payload));
			return true;
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Couldn't send the disconnect explanation", e);
			return false;
		}
	}

	/** A player's connection ended (server thread). */
	public void onLeave(ServerCommonPacketListenerImpl listener, Connection connection, DisconnectionDetails details) {
		GameProfile profile = listener.getOwner();
		PlayerSession s = online.get(profile.id());
		if (s != null && s.connection.get() == connection) {
			online.remove(profile.id());
			ended.remove(profile.id());
			ended.put(profile.id(), s);
		}
		byConnection.remove(connection);

		long now = System.currentTimeMillis();
		Component reason = details.reason();
		String key = reason.getContents() instanceof TranslatableContents t ? t.getKey() : null;
		Kick kick = s != null && s.kick != null && now - s.kick.time() < CONTEXT_MS ? s.kick : null;
		Throwable error = s != null && s.error != null && now - s.errorTime < CONTEXT_MS ? s.error : null;

		DisconnectCause cause;
		if (kick != null && kick.stopping() || "multiplayer.disconnect.server_shutdown".equals(key)) cause = DisconnectCause.SERVER_STOPPED;
		else if ("disconnect.timeout".equals(key)) cause = DisconnectCause.TIMED_OUT;
		else if (kick != null) cause = DisconnectCause.KICKED;
		else if (error instanceof DecoderException || "disconnect.packetError".equals(key)) cause = DisconnectCause.BAD_DATA;
		else if (error instanceof IOException) cause = DisconnectCause.LOST_CONNECTION;
		else cause = DisconnectCause.LEFT;

		String text = reason.getString();
		String serverError = error == null ? null : PacketRules.shortMessage(error);
		String summary = switch (cause) {
			case KICKED -> switch (kick.kind()) {
				case "MOD" -> Tr.t("packetdoctor.sdc.kicked_mod", kick.kicker(), kick.reason() != null ? kick.reason() : text);
				case "OPERATOR" -> Tr.t("packetdoctor.sdc.kicked_operator", text);
				default -> Tr.t("packetdoctor.sdc.kicked_server", text);
			};
			case BAD_DATA -> Tr.t("packetdoctor.sdc.bad_data", serverError != null ? serverError : text);
			case LOST_CONNECTION -> Tr.t("packetdoctor.sdc.lost_connection", serverError != null ? serverError : text);
			case TIMED_OUT -> Tr.t("packetdoctor.sdc.timed_out");
			case SERVER_STOPPED -> Tr.t("packetdoctor.sdc.server_stopped");
			case REFUSED -> Tr.t("packetdoctor.sdc.refused", text);
			case LEFT -> Tr.t("packetdoctor.sdc.left", text);
		};

		List<WarningInfo> warnings = s == null ? List.of() : s.warnings.since(CONTEXT_MS).stream().map(ServerMonitor::info).toList();
		DisconnectInfo info = new DisconnectInfo(profile.id(), profile.name(), now, cause, text, key,
				kick == null ? null : kick.kind(), kick == null ? null : kick.kicker(), kick == null ? null : kick.reason(),
				serverError, summary, warnings, s != null && s.explanationSent);
		remember(info);
	}

	/** A login was refused (whitelist, ban, full...) before the player joined (any thread). */
	public void onRefused(String name, Component reason) {
		String key = reason.getContents() instanceof TranslatableContents t ? t.getKey() : null;
		remember(new DisconnectInfo(null, name, System.currentTimeMillis(), DisconnectCause.REFUSED, reason.getString(), key,
				null, null, null, null, Tr.t("packetdoctor.sdc.refused", reason.getString()), List.of(), false));
	}

	private void remember(DisconnectInfo info) {
		synchronized (disconnects) {
			disconnects.addFirst(info);
			while (disconnects.size() > config.get().keepDisconnects) disconnects.removeLast();
		}
		listener.onDisconnect(info);
	}

	// --- Tick -------------------------------------------------------------------------

	/** Once per server tick: judge each player's finished seconds of traffic. */
	public void tick() {
		if (!monitoring()) return;
		long now = System.currentTimeMillis();
		for (PlayerSession s : online.values()) {
			for (RateTracker.Second sec : s.fromPlayer.drain(now)) PacketRules.fromPlayerRate(s, sec);
			for (RateTracker.Second sec : s.toPlayer.drain(now)) PacketRules.toPlayerRate(s, sec);
		}
	}

	// --- Conversions for the API ------------------------------------------------------

	public static WarningInfo info(Warning w) {
		String direction = w.direction == null ? null : w.direction == Direction.OUT ? "FROM_PLAYER" : "TO_PLAYER";
		return new WarningInfo(w.id, w.severity.name(), w.title(), w.detail(), w.advice(), w.source(), w.packetName(),
				direction, w.firstSeen, w.lastSeen(), w.count());
	}

	/** One player's connection, as the server sees it. */
	public static final class PlayerSession implements RuleSink {
		public final UUID uuid;
		public final String name;
		public final long joined = System.currentTimeMillis();
		private final ServerMonitor monitor;
		private final WeakReference<Connection> connection;
		private final PacketLog log;
		private final WarningLog warnings;
		private final RateTracker fromPlayer = new RateTracker();
		private final RateTracker toPlayer = new RateTracker();
		private volatile @Nullable Throwable error;
		private volatile long errorTime;
		private volatile @Nullable Kick kick;
		private volatile boolean explanationSent;
		private double lastX = Double.NaN;
		private double lastY;
		private double lastZ;

		private PlayerSession(ServerMonitor monitor, UUID uuid, String name, Connection connection) {
			this.monitor = monitor;
			this.uuid = uuid;
			this.name = name;
			this.connection = new WeakReference<>(connection);
			this.log = new PacketLog(monitor.config.get().playerLogSize);
			this.warnings = new WarningLog(w -> monitor.listener.onWarning(this, w));
		}

		public PacketLog log() {
			return log;
		}

		public WarningLog warnings() {
			return warnings;
		}

		/** Whether this player's game runs Packet Doctor (it registered the explanation channel). */
		public boolean hasClientMod() {
			Connection c = connection.get();
			if (c == null) return false;
			PacketListener l = c.getPacketListener();
			try {
				if (l instanceof ServerGamePacketListenerImpl g) return ServerPlayNetworking.canSend(g, ExplanationPayload.TYPE);
				if (l instanceof ServerConfigurationPacketListenerImpl cfg) return ServerConfigurationNetworking.canSend(cfg, ExplanationPayload.TYPE);
			} catch (RuntimeException ignored) {
			}
			return false;
		}

		public PlayerReport report() {
			long now = System.currentTimeMillis();
			long[] t = log.totals();
			Severity worst = null;
			List<Warning> all = warnings.all();
			for (Warning w : all) if (worst == null || w.severity.atLeast(worst)) worst = w.severity;
			// Log totals are from the player's point of view: IN = to the player, OUT = from the player.
			return new PlayerReport(uuid, name, hasClientMod(), fromPlayer.average(3, now), toPlayer.average(3, now),
					t[1], t[0], t[3], t[2], all.size(), worst == null ? "NONE" : worst.name(), joined);
		}

		@Override
		public Warning.Builder w(String id, Severity severity) {
			return Warning.server(id, severity);
		}

		@Override
		public Warning warn(Warning.Builder builder, @Nullable PacketRecord record) {
			Warning warning = builder.build();
			if (record != null) record.flag(warning.severity);
			return warnings.add(warning, monitor.config.get().alertCooldownSeconds * 1000L);
		}

		@Override
		public @Nullable String blameHere(String warningKey) {
			return null; // the server can't see which of the player's mods sent something
		}

		@Override
		public @Nullable String blameForType(String path) {
			return null;
		}

		@Override
		public synchronized double moved(double x, double y, double z) {
			double d = Double.isNaN(lastX) ? -1 : Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ));
			lastX = x;
			lastY = y;
			lastZ = z;
			return d;
		}

		@Override
		public synchronized void resetMovement() {
			lastX = Double.NaN;
		}

		@Override
		public String toString() {
			return name + " (" + uuid.toString().toLowerCase(Locale.ROOT) + ")";
		}
	}
}
