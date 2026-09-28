package com.packetdoctor.net;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.diagnose.ModBlame;
import com.packetdoctor.network.ServerExplanation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.BundleDelimiterPacket;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * The player's side: watches the game's own connection, keeps the live log, runs the
 * {@link PacketRules}, and remembers enough about the end of a connection to explain it.
 *
 * <p>Only the game connection is watched; the connections the server list opens to ping
 * servers (STATUS protocol) are ignored. Hooks run on Netty's network thread and on the
 * game thread, so shared state is volatile, concurrent, or guarded by {@code this}.
 */
public final class PacketMonitor implements ConnectionObserver, RuleSink {
	private static final long SILENCE_MS = 8_000;
	private static final long CONTEXT_MS = 60_000;
	private static final int MAX_PENDING = 4096;
	private static PacketMonitor instance;

	private final PacketLog log;
	private final WarningLog warnings;
	private final IntSupplier cooldownSeconds;
	private final RateTracker inRate = new RateTracker();
	private final RateTracker outRate = new RateTracker();
	/** Sizes from the decoder, waiting for the same packet object to reach the handler. */
	private final Map<Object, Integer> decodedSizes = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Logged outgoing packets waiting for the encoder to report their size. */
	private final Map<Object, PacketRecord> unsized = Collections.synchronizedMap(new IdentityHashMap<>());
	/** Packet types that went over a rate limit; the next one sent gets its call stack checked. */
	private final Set<String> blameWanted = ConcurrentHashMap.newKeySet();
	private final Map<String, String> blameByType = new ConcurrentHashMap<>();

	private volatile WeakReference<Connection> current = new WeakReference<>(null);
	private volatile long lastInbound;
	private volatile boolean silenceReported;
	private volatile int ownEntityId = -1;
	private volatile String server = "";
	private volatile @Nullable String transferTarget;

	private volatile @Nullable Throwable networkError;
	private volatile long networkErrorTime;
	private volatile @Nullable Throwable packetError;
	private volatile @Nullable String failedPacket;
	private volatile long packetErrorTime;
	private volatile @Nullable ServerExplanation serverExplanation;
	private volatile long serverExplanationTime;
	private volatile @Nullable DisconnectRecord lastDisconnect;

	// Outgoing movement and chat, guarded by this.
	private double lastX = Double.NaN;
	private double lastY;
	private double lastZ;
	private double spamLevel;
	private long spamTime;

	private PacketMonitor(int logSize, IntSupplier cooldownSeconds, java.util.function.Consumer<Warning> notifier) {
		this.log = new PacketLog(logSize);
		this.warnings = new WarningLog(notifier);
		this.cooldownSeconds = cooldownSeconds;
	}

	/** Called by the client entrypoint; also registers with {@link NetHooks}. */
	public static void init(int logSize, IntSupplier cooldownSeconds, java.util.function.Consumer<Warning> notifier) {
		instance = new PacketMonitor(logSize, cooldownSeconds, notifier);
		NetHooks.setClient(instance);
	}

	public static PacketMonitor get() {
		return instance;
	}

	public PacketLog log() {
		return log;
	}

	public WarningLog warnings() {
		return warnings;
	}

	public String server() {
		return server;
	}

	public double inboundRate() {
		return inRate.average(3, System.currentTimeMillis());
	}

	public double outboundRate() {
		return outRate.average(3, System.currentTimeMillis());
	}

	public boolean isConnected() {
		Connection c = current.get();
		return c != null && c.isConnected();
	}

	int ownEntityId() {
		return ownEntityId;
	}

	// --- Which connections count --------------------------------------------------------

	private static boolean isTracked(Connection connection) {
		if (connection.getReceiving() != PacketFlow.CLIENTBOUND) return false;
		PacketListener listener = connection.getPacketListener();
		return listener != null && listener.protocol() != ConnectionProtocol.STATUS;
	}

	private static String phase(Connection connection) {
		PacketListener listener = connection.getPacketListener();
		return listener == null ? "?" : listener.protocol().id();
	}

	/** Starts a fresh session the first time a new connection is seen. */
	private void touch(Connection connection) {
		if (current.get() == connection) return;
		synchronized (this) {
			if (current.get() == connection) return;
			current = new WeakReference<>(connection);
			log.reset();
			warnings.clear();
			inRate.reset();
			outRate.reset();
			decodedSizes.clear();
			unsized.clear();
			blameWanted.clear();
			blameByType.clear();
			lastX = Double.NaN;
			spamLevel = 0;
			lastInbound = System.currentTimeMillis();
			silenceReported = false;
			transferTarget = null;
			networkError = null;
			packetError = null;
			failedPacket = null;
			serverExplanation = null;
		}
	}

	// --- Packets ----------------------------------------------------------------------

	@Override
	public void onDecoded(ProtocolInfo<?> protocol, Object packet, int size) {
		if (protocol.id() == ConnectionProtocol.STATUS || packet instanceof BundleDelimiterPacket<?>) return;
		if (decodedSizes.size() > MAX_PENDING) decodedSizes.clear();
		decodedSizes.put(packet, size);
	}

	@Override
	public void onInbound(Connection connection, Packet<?> packet) {
		if (!isTracked(connection)) return;
		touch(connection);
		long now = System.currentTimeMillis();
		String phase = phase(connection);
		if (packet instanceof BundlePacket<?> bundle) {
			for (Packet<?> sub : bundle.subPackets()) inboundOne(sub, phase, now);
		} else {
			inboundOne(packet, phase, now);
		}
	}

	private void inboundOne(Packet<?> packet, String phase, long now) {
		String id = PacketNames.id(packet);
		Integer size = decodedSizes.remove(packet);
		PacketRecord record = new PacketRecord(now, Direction.IN, phase, id, size == null ? -1 : size);
		log.add(record);
		inRate.record(PacketNames.path(id), now);

		long quiet = now - lastInbound;
		lastInbound = now;
		if (silenceReported) {
			silenceReported = false;
			warn(w("in.silence.end", Severity.INFO).detail(Math.round(quiet / 1000.0)), null);
		}

		PacketRules.clientInbound(this, packet, record);
		if (size != null) PacketRules.toPlayerSize(this, record, size);
	}

	@Override
	public void onOutbound(Connection connection, Packet<?> packet) {
		if (!isTracked(connection)) return;
		touch(connection);
		long now = System.currentTimeMillis();
		String id = PacketNames.id(packet);
		String path = PacketNames.path(id);
		PacketRecord record = new PacketRecord(now, Direction.OUT, phase(connection), id, -1);
		log.add(record);
		outRate.record(path, now);
		if (!connection.isMemoryConnection()) {
			if (unsized.size() > MAX_PENDING) unsized.clear();
			unsized.put(packet, record);
		}
		PacketRules.fromPlayer(this, packet, record);
		PacketRules.clientSpam(this, packet, record);
		if (blameWanted.remove(path)) {
			String blame = stackBlame();
			if (blame != null) blameByType.put(path, blame);
		}
	}

	@Override
	public void onEncoded(ProtocolInfo<?> protocol, Object packet, int size) {
		if (protocol.id() == ConnectionProtocol.STATUS) return;
		PacketRecord record = unsized.remove(packet);
		if (record == null) return;
		log.sized(record, size);
		PacketRules.fromPlayerSize(this, record, size);
	}

	// --- Errors and the end of the connection -----------------------------------------

	@Override
	public void onNetworkException(Connection connection, Throwable cause) {
		if (!isTracked(connection)) return;
		touch(connection);
		networkError = cause;
		networkErrorTime = System.currentTimeMillis();
		PacketRules.clientNetworkError(this, cause);
	}

	/** The game failed while handling a packet from the server (game thread). */
	public void onPacketError(Packet<?> packet, Throwable cause) {
		try {
			packetError = cause;
			packetErrorTime = System.currentTimeMillis();
			failedPacket = PacketNames.id(packet);
			PacketRules.clientPacketError(this, failedPacket, cause);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Packet error hook failed", e);
		}
	}

	/** A Packet Doctor server explained, just before disconnecting us, why it did. */
	public void onServerExplanation(ServerExplanation explanation) {
		serverExplanation = explanation;
		serverExplanationTime = System.currentTimeMillis();
	}

	@Override
	public void onDisconnect(Connection connection, DisconnectionDetails details) {
		if (!connection.isConnected() || !isTracked(connection)) return;
		StackTraceElement[] stack = new Throwable().getStackTrace();
		record(connection, details.reason(), classify(stack, details.reason()), stack, details.report());
	}

	/**
	 * The socket closed. When the server sends no disconnect packet (crash, restart,
	 * network drop), this is the only sign, so record it unless a reason is already known.
	 */
	@Override
	public void onChannelInactive(Connection connection) {
		if (!isTracked(connection)) return;
		DisconnectRecord last = lastDisconnect;
		if (last != null && last.connectionId() == System.identityHashCode(connection)) return;
		record(connection, Component.translatable("disconnect.endOfStream"), DisconnectRecord.Trigger.CONNECTION_CLOSED,
				new Throwable().getStackTrace(), Optional.empty());
	}

	private void record(Connection connection, Component reason, DisconnectRecord.Trigger trigger,
			StackTraceElement[] stack, Optional<Path> report) {
		long now = System.currentTimeMillis();
		Throwable netErr = now - networkErrorTime < CONTEXT_MS ? networkError : null;
		Throwable pktErr = now - packetErrorTime < CONTEXT_MS ? packetError : null;
		ServerExplanation explained = now - serverExplanationTime < CONTEXT_MS ? serverExplanation : null;
		if (trigger == DisconnectRecord.Trigger.NETWORK_ERROR && netErr instanceof io.netty.handler.timeout.TimeoutException) {
			trigger = DisconnectRecord.Trigger.TIMEOUT;
		}
		RateTracker.Second peak = outRate.peak(5, now);
		String brand = null;
		ClientPacketListener listener = Minecraft.getInstance().getConnection();
		if (listener != null) brand = listener.serverBrand();

		String where = server;
		// Kicked while still logging in, the game hasn't recorded the server entry yet: use the socket address.
		if (where.isEmpty() && connection.getRemoteAddress() instanceof InetSocketAddress a) {
			where = a.getHostString() + ":" + a.getPort();
		}
		lastDisconnect = new DisconnectRecord(now, System.identityHashCode(connection), reason, trigger, stack,
				netErr, pktErr, pktErr != null ? failedPacket : null, now - lastInbound, log.recent(40),
				warnings.since(CONTEXT_MS), peak == null ? null : peak.top(4), peak == null ? 0 : peak.total(),
				where, brand, transferTarget, explained, report);
		if (trigger != DisconnectRecord.Trigger.PLAYER) {
			PacketDoctor.LOGGER.info("Connection to {} ended: \"{}\" ({}{})", where, reason.getString(), trigger,
					explained != null ? ", server explained it" : "");
		}
	}

	/** Reads the call stack to see who asked for the disconnect. */
	private static DisconnectRecord.Trigger classify(StackTraceElement[] stack, Component reason) {
		String key = reason.getContents() instanceof TranslatableContents t ? t.getKey() : "";
		if (key.equals("multiplayer.status.quitting") || key.equals("connect.aborted")) return DisconnectRecord.Trigger.PLAYER;

		for (StackTraceElement frame : stack) {
			if (frame.getMethodName().equals("disconnectFromWorld") || frame.getClassName().endsWith(".PauseScreen")) {
				return DisconnectRecord.Trigger.PLAYER;
			}
		}

		// The first frame outside Connection.disconnect and this mod is the caller.
		for (StackTraceElement frame : stack) {
			String cls = frame.getClassName();
			String method = frame.getMethodName();
			if (PacketDoctor.MOD_ID.equals(ModBlame.modForFrame(frame))) continue;
			if (cls.equals(Connection.class.getName()) && method.equals("disconnect")) continue;
			switch (method) {
				case "handleDisconnect" -> {
					return DisconnectRecord.Trigger.SERVER_KICK;
				}
				case "exceptionCaught" -> {
					return DisconnectRecord.Trigger.NETWORK_ERROR;
				}
				case "channelInactive" -> {
					return DisconnectRecord.Trigger.CONNECTION_CLOSED;
				}
				case "onPacketError", "channelRead0" -> {
					return DisconnectRecord.Trigger.PACKET_ERROR;
				}
				default -> {
				}
			}
			break;
		}

		if (ModBlame.prime(ModBlame.blame(stack, true)) != null) return DisconnectRecord.Trigger.MOD;
		return key.isEmpty() ? DisconnectRecord.Trigger.UNKNOWN : DisconnectRecord.Trigger.CLIENT;
	}

	/**
	 * The disconnect that ended the last session, for the disconnect screen. Only a recent,
	 * not-yet-claimed record is returned; the player leaving on purpose is never returned.
	 */
	public synchronized @Nullable DisconnectRecord claimDisconnect() {
		DisconnectRecord r = lastDisconnect;
		if (r == null || r.trigger() == DisconnectRecord.Trigger.PLAYER) return null;
		if (System.currentTimeMillis() - r.time() > CONTEXT_MS) return null;
		lastDisconnect = null;
		return r;
	}

	// --- Game tick ------------------------------------------------------------------

	/** Once per client tick, on the game thread. */
	public void tick(Minecraft client) {
		ownEntityId = client.player != null ? client.player.getId() : -1;
		ServerData data = client.getCurrentServer();
		if (client.isLocalServer()) server = "Singleplayer / LAN";
		else if (data != null) server = data.ip;

		Connection connection = current.get();
		if (connection == null || !connection.isConnected()) return;
		long now = System.currentTimeMillis();
		for (RateTracker.Second s : inRate.drain(now)) PacketRules.clientInboundRate(this, s);
		for (RateTracker.Second s : outRate.drain(now)) PacketRules.fromPlayerRate(this, s);

		boolean playing = connection.getPacketListener() != null
				&& connection.getPacketListener().protocol() == ConnectionProtocol.PLAY;
		long quiet = now - lastInbound;
		if (playing && !connection.isMemoryConnection() && quiet > SILENCE_MS && !silenceReported) {
			silenceReported = true;
			warn(w("in.silence", Severity.WARNING).detail(Math.round(quiet / 1000.0))
					.source("packetdoctor.source.server_or_network"), null);
		}
	}

	// --- RuleSink -------------------------------------------------------------------

	@Override
	public Warning.Builder w(String id, Severity severity) {
		return Warning.of(id, severity);
	}

	@Override
	public Warning warn(Warning.Builder builder, @Nullable PacketRecord record) {
		Warning warning = builder.build();
		if (record != null) record.flag(warning.severity);
		return warnings.add(warning, cooldownSeconds.getAsInt() * 1000L);
	}

	@Override
	public @Nullable String blameHere(String warningKey) {
		return warnings.contains(warningKey) ? null : stackBlame();
	}

	@Override
	public @Nullable String blameForType(String path) {
		String known = blameByType.get(path);
		if (known == null) blameWanted.add(path);
		return known;
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

	/** The mod on the current call stack (for outgoing packets a mod sent), or null. */
	private static @Nullable String stackBlame() {
		ModBlame.Culprit c = ModBlame.prime(ModBlame.blame(new Throwable().getStackTrace(), true));
		return c == null ? null : c.label();
	}

	void addParticles(long count) {
		inRate.addExtra(count, System.currentTimeMillis());
	}

	void noteTransfer(String target) {
		transferTarget = target;
	}

	/** Mirrors vanilla's chat spam counter: +20 per message, -1 per tick, kick above 200. */
	synchronized double bumpSpam(long now) {
		spamLevel = Math.max(0, spamLevel - (now - spamTime) / 50.0) + 20;
		spamTime = now;
		return spamLevel;
	}

	/** Warning lines for reports, most severe first. */
	public static List<String> lines(List<Warning> list, int max) {
		List<String> out = new ArrayList<>();
		for (Warning w : list) {
			if (out.size() >= max) break;
			out.add(w.oneLine());
		}
		return out;
	}
}
