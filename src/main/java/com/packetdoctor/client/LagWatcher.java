package com.packetdoctor.client;

import com.packetdoctor.Tr;
import com.packetdoctor.net.Severity;
import com.packetdoctor.network.ServerStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out why the player is lagging: the server, the connection, or their own game.
 *
 * <ul>
 *   <li><b>The server:</b> its exact TPS and causes when it runs Packet Doctor; otherwise TPS
 *       estimated from the player list's latency update, which the server sends every 600
 *       ticks (30 s at full speed, longer when it is slow). A server freeze shows as game data
 *       stopping while pings are still answered at once: pings are answered by the server's
 *       network thread, which keeps going when the game thread is stuck.</li>
 *   <li><b>The connection:</b> real ping and jitter from Minecraft's own debug ping, sent once
 *       a second (the same packet the F3 network chart uses), and pings that never return.</li>
 *   <li><b>The game:</b> FPS, ticks where the game hitched, and Java memory pauses.</li>
 * </ul>
 * Packets arrive on the network thread and the rest runs on the game thread, so state is
 * guarded by {@code this}.
 */
public final class LagWatcher {
	private static final LagWatcher INSTANCE = new LagWatcher();
	private static final long PING_EVERY_MS = 1000;
	private static final long PING_LOST_MS = 5000;
	private static final long FREEZE_MS = 1500;
	private static final long EPISODE_AFTER_MS = 3000;
	private static final long EPISODE_QUIET_MS = 5000;

	/** Where lag comes from. */
	public enum Cause {
		SERVER("packetdoctor.lag.cause.server"),
		CONNECTION("packetdoctor.lag.cause.connection"),
		GAME("packetdoctor.lag.cause.game");

		private final String key;

		Cause(String key) {
			this.key = key;
		}

		public String label() {
			return Tr.t(key);
		}

		public String verdict() {
			return Tr.t(key + ".verdict");
		}

		public String advice() {
			return Tr.t(key + ".advice");
		}
	}

	/** One thing noticed, in plain words, with a short form for pop-ups. */
	public record Finding(Cause cause, Severity severity, String text, String shortText) {
	}

	/** A finding whose full text is {@code key} and pop-up text {@code key.short}. */
	private static Finding find(Cause cause, Severity severity, String key, Object... args) {
		return new Finding(cause, severity, Tr.t(key, args), Tr.t(key + ".short", args));
	}

	/** A stretch of lag that has ended (or is still going, with {@code end} 0). */
	public record Episode(long start, long end, Cause cause, String text) {
	}

	/**
	 * Everything known right now.
	 *
	 * @param tpsSource {@code SERVER} (exact, from Packet Doctor on the server), {@code ESTIMATE},
	 *                  {@code SINGLEPLAYER}, or {@code NONE}
	 */
	public record Snapshot(long time, boolean connected, @Nullable Double tps, String tpsSource, double targetTps, @Nullable Double mspt,
			@Nullable Double msptMax, List<ServerStatus.Cause> serverCauses, int pingMs, int jitterMs, int lostPings, int fps,
			int memoryPercent, List<Finding> findings) {

		public @Nullable Cause primary() {
			for (Cause c : Cause.values()) {
				for (Finding f : findings) if (f.cause() == c && f.severity() != Severity.INFO) return c;
			}
			return null;
		}
	}

	private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

	// Connection state (guarded by this).
	private @Nullable ClientPacketListener listener;
	private final Deque<long[]> rtts = new ArrayDeque<>();
	private final Deque<Long> outstanding = new ArrayDeque<>();
	private final Deque<Long> lost = new ArrayDeque<>();
	private final Deque<Long> corrections = new ArrayDeque<>();
	private long lastPingSent;
	private long lastPongAt;
	private long lastGameAt;
	private long joinedAt;
	private long latencyUpdateNanos;
	private double estimatedTps = Double.NaN;
	private long estimatedAt;
	private int shortIntervals;
	private float targetTps = 20;
	private @Nullable ServerStatus status;
	private long statusAt;
	private long freezeFrom;
	private boolean freezeIsServer;
	private long pastFreezeMs;
	private long pastFreezeAt;
	private boolean pastFreezeServer;

	// Game thread only.
	private long lastClientTick;
	private final Deque<long[]> hitches = new ArrayDeque<>();
	private final Deque<long[]> gcPauses = new ArrayDeque<>();
	private long lastGcTotal = -1;
	private long lastSnapshotAt;
	private volatile Snapshot snapshot = empty();

	// Episodes (guarded by this).
	private @Nullable Cause current;
	private long currentSince;
	private long quietSince;
	private @Nullable Finding currentFinding;
	private boolean currentAnnounced;
	private final Deque<Episode> episodes = new ArrayDeque<>();
	private final Map<Cause, Long> lastToast = new EnumMap<>(Cause.class);

	private LagWatcher() {
	}

	public static LagWatcher get() {
		return INSTANCE;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	public synchronized List<Episode> episodes() {
		List<Episode> out = new ArrayList<>(episodes);
		if (current != null && currentAnnounced && currentFinding != null) {
			out.addFirst(new Episode(currentSince, 0, current, currentFinding.text()));
		}
		return out;
	}

	// --- Network thread --------------------------------------------------------------------

	/** Every packet from the server, as it arrives. */
	public synchronized void onInbound(Packet<?> packet) {
		long now = Util.getMillis();
		switch (packet) {
			case ClientboundPongResponsePacket p -> {
				long rtt = now - p.time();
				if (outstanding.remove(p.time()) && rtt >= 0 && rtt < 60_000) {
					rtts.addLast(new long[] {now, rtt});
					while (rtts.size() > 120) rtts.removeFirst();
				}
				lastPongAt = now;
				return; // answered by the network thread, so it says nothing about the server's game thread
			}
			case ClientboundPlayerInfoUpdatePacket p when p.actions().equals(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY)) ->
					latencyUpdate(System.nanoTime());
			case ClientboundTickingStatePacket p -> targetTps = p.tickRate();
			case ClientboundLoginPacket p -> {
				joinedAt = now;
				latencyUpdateNanos = 0;
				estimatedTps = Double.NaN;
				shortIntervals = 0;
			}
			case ClientboundRespawnPacket p -> joinedAt = now;
			case ClientboundPlayerPositionPacket p -> {
				// The server moving us back, outside of joining and respawning, is rubber-banding.
				if (now - joinedAt > 5000) {
					corrections.addLast(now);
					while (corrections.size() > 50) corrections.removeFirst();
				}
			}
			case ClientboundKeepAlivePacket p -> {
			}
			default -> {
			}
		}
		// Data is flowing again: if it had stopped for a while, that was a freeze. Pings answered
		// during it mean the server froze; pings sent but not answered mean the connection stalled.
		long gap = now - lastGameAt;
		if (lastGameAt != 0 && gap > FREEZE_MS && now - joinedAt > 5000) {
			boolean pongs = lastPongAt > lastGameAt;
			boolean pinged = lastPingSent > lastGameAt;
			if (pongs || pinged) {
				pastFreezeMs = gap;
				pastFreezeAt = now;
				pastFreezeServer = pongs;
			}
		}
		lastGameAt = now;
	}

	/**
	 * The server sends a latency-only player list update every 600 ticks: 30 seconds at
	 * 20 TPS. A longer gap means the server ran slower. Servers whose plugins send it more
	 * often can't be measured this way.
	 */
	private void latencyUpdate(long nanos) {
		long previous = latencyUpdateNanos;
		latencyUpdateNanos = nanos;
		if (previous == 0) return;
		double seconds = (nanos - previous) / 1e9;
		double expected = 600 / Math.max(1, targetTps);
		if (seconds < expected * 0.8) {
			shortIntervals++;
			if (shortIntervals >= 2) estimatedTps = Double.NaN;
			return;
		}
		if (shortIntervals >= 2) return;
		estimatedTps = Math.min(targetTps, 600 / seconds);
		estimatedAt = Util.getMillis();
	}

	/** Packet Doctor on the server said how it is doing (game thread). */
	public synchronized void onStatus(ServerStatus s) {
		status = s;
		statusAt = Util.getMillis();
		targetTps = (float) s.targetTps();
	}

	// --- Game thread ------------------------------------------------------------------------

	public void tick(Minecraft mc) {
		long now = Util.getMillis();
		// A gap between client ticks is the game itself hitching (loading screens excepted).
		if (lastClientTick != 0 && mc.level != null && mc.gui.overlay() == null && mc.isWindowActive()) {
			long gap = now - lastClientTick;
			if (gap > 300) {
				hitches.addLast(new long[] {now, gap});
				while (hitches.size() > 30) hitches.removeFirst();
			}
		}
		lastClientTick = now;

		ClientPacketListener connection = mc.getConnection();
		synchronized (this) {
			if (connection != listener) reset(connection, now);
			if (connection != null && mc.player != null && !mc.isLocalServer() && PacketDoctorClient.config().measurePing
					&& now - lastPingSent >= PING_EVERY_MS && now - joinedAt > 2000) {
				lastPingSent = now;
				outstanding.addLast(now);
				while (outstanding.size() > 30) outstanding.removeFirst();
				connection.getConnection().send(new ServerboundPingRequestPacket(now));
			}
			while (!outstanding.isEmpty() && now - outstanding.peekFirst() > PING_LOST_MS) {
				outstanding.removeFirst();
				lost.addLast(now);
			}
			while (!lost.isEmpty() && now - lost.peekFirst() > 30_000) lost.removeFirst();
			while (!corrections.isEmpty() && now - corrections.peekFirst() > 30_000) corrections.removeFirst();
		}

		if (now - lastSnapshotAt >= 1000) {
			lastSnapshotAt = now;
			trackGc(now);
			Snapshot s = build(mc, connection, now);
			snapshot = s;
			episodes(s, now);
		}
	}

	private synchronized void reset(@Nullable ClientPacketListener connection, long now) {
		listener = connection;
		rtts.clear();
		outstanding.clear();
		lost.clear();
		corrections.clear();
		status = null;
		estimatedTps = Double.NaN;
		latencyUpdateNanos = 0;
		shortIntervals = 0;
		targetTps = 20;
		lastGameAt = now;
		lastPongAt = 0;
		joinedAt = now;
		pastFreezeMs = 0;
		freezeFrom = 0;
		if (current != null) endEpisode(now);
		current = null;
		if (connection == null) episodes.clear();
	}

	private void trackGc(long now) {
		long total = 0;
		for (GarbageCollectorMXBean b : gcBeans) total += Math.max(0, b.getCollectionTime());
		if (lastGcTotal >= 0 && total > lastGcTotal) gcPauses.addLast(new long[] {now, total - lastGcTotal});
		lastGcTotal = total;
		while (!gcPauses.isEmpty() && now - gcPauses.peekFirst()[0] > 10_000) gcPauses.removeFirst();
		while (!hitches.isEmpty() && now - hitches.peekFirst()[0] > 10_000) hitches.removeFirst();
	}

	private synchronized Snapshot build(Minecraft mc, @Nullable ClientPacketListener connection, long now) {
		List<Finding> findings = new ArrayList<>();
		boolean connected = connection != null && mc.player != null;
		if (!connected) return empty();

		// --- The server ---
		Double tps = null;
		Double mspt = null;
		Double msptMax = null;
		String source = "NONE";
		List<ServerStatus.Cause> causes = List.of();
		IntegratedServer local = mc.getSingleplayerServer();
		ServerStatus st = status;
		if (local != null) {
			mspt = local.getAverageTickTimeNanos() / 1e6;
			float target = local.tickRateManager().tickrate();
			targetTps = target;
			tps = Math.min(target, 1000 / Math.max(0.001, mspt));
			source = "SINGLEPLAYER";
		} else if (st != null && now - statusAt < 6000) {
			tps = st.tps10s();
			mspt = st.mspt();
			msptMax = st.msptMax();
			causes = st.causes();
			source = "SERVER";
		} else if (!Double.isNaN(estimatedTps) && now - estimatedAt < 70_000) {
			tps = estimatedTps;
			source = "ESTIMATE";
		}
		if (tps != null && tps < targetTps * 0.9) {
			String t = fmt(tps);
			String target = fmt(targetTps);
			if (source.equals("SINGLEPLAYER")) {
				findings.add(find(Cause.GAME, Severity.WARNING, "packetdoctor.lag.find.singleplayer_slow", t, target));
			} else {
				Severity sev = tps < targetTps * 0.5 ? Severity.DANGER : Severity.WARNING;
				String text = Tr.t(source.equals("SERVER") ? "packetdoctor.lag.find.server_slow" : "packetdoctor.lag.find.server_slow_estimate", t, target);
				if (!causes.isEmpty()) text += " " + Tr.t("packetdoctor.lag.find.server_cause", causeText(causes.getFirst()));
				findings.add(new Finding(Cause.SERVER, sev, text,
						Tr.t(source.equals("SERVER") ? "packetdoctor.lag.find.server_slow.short" : "packetdoctor.lag.find.server_slow_estimate.short", t, target)));
			}
		}

		// --- Freezes: game data stopped. Pings still answered = the server; not answered = the connection ---
		long gap = now - lastGameAt;
		if (local == null && gap > FREEZE_MS && now - joinedAt > 5000) {
			if (freezeFrom == 0) freezeFrom = lastGameAt;
			freezeIsServer = lastPongAt > lastGameAt && outstanding.stream().noneMatch(t -> now - t > 1500);
			findings.add(freezeIsServer
					? find(Cause.SERVER, Severity.DANGER, "packetdoctor.lag.find.server_frozen", seconds(gap))
					: find(Cause.CONNECTION, Severity.DANGER, "packetdoctor.lag.find.stall", seconds(gap)));
		} else {
			freezeFrom = 0;
			// A short freeze can start and end between two checks; it is measured when data resumes.
			if (local == null && pastFreezeMs > 0 && now - pastFreezeAt < 6000) {
				findings.add(pastFreezeServer
						? find(Cause.SERVER, Severity.DANGER, "packetdoctor.lag.find.server_froze", seconds(pastFreezeMs))
						: find(Cause.CONNECTION, Severity.DANGER, "packetdoctor.lag.find.stalled", seconds(pastFreezeMs)));
			}
		}

		// --- The connection ---
		int ping = -1;
		int jitter = 0;
		List<Long> recent = new ArrayList<>();
		for (long[] r : rtts) if (now - r[0] < 10_000) recent.add(r[1]);
		if (recent.size() >= 2) {
			List<Long> sorted = new ArrayList<>(recent);
			sorted.sort(null);
			ping = (int) (long) sorted.get(sorted.size() / 2);
			long sum = 0;
			for (long r : recent) sum += Math.abs(r - ping);
			jitter = (int) (sum / recent.size());
		} else if (mc.player != null) {
			PlayerInfo info = connection.getPlayerInfo(mc.player.getUUID());
			if (info != null && info.getLatency() > 0) ping = info.getLatency();
		}
		if (local == null) {
			if (ping > 250) {
				findings.add(find(Cause.CONNECTION, ping > 600 ? Severity.DANGER : Severity.WARNING, "packetdoctor.lag.find.ping_high", ping));
			}
			if (jitter > 80) findings.add(find(Cause.CONNECTION, Severity.WARNING, "packetdoctor.lag.find.jitter", jitter));
			if (lost.size() >= 2) findings.add(find(Cause.CONNECTION, Severity.WARNING, "packetdoctor.lag.find.lost", lost.size()));
		}

		// --- The game ---
		int fps = mc.getFps();
		// Minecraft lowers its own frame rate when the player is AFK, minimised or in a menu; that isn't lag.
		var limiter = mc.getFramerateLimitTracker();
		boolean throttled = !limiter.getThrottleReason().name().equals("NONE");
		if (fps < 25 && !throttled && fps < limiter.getFramerateLimit() * 0.8 && mc.isWindowActive() && mc.level != null) {
			findings.add(find(Cause.GAME, fps < 12 ? Severity.DANGER : Severity.WARNING, "packetdoctor.lag.find.fps", fps));
		}
		long worstHitch = 0;
		for (long[] h : hitches) worstHitch = Math.max(worstHitch, h[1]);
		if (worstHitch > 300) findings.add(find(Cause.GAME, Severity.WARNING, "packetdoctor.lag.find.hitch", seconds(worstHitch)));
		long gc = 0;
		for (long[] g : gcPauses) gc += g[1];
		if (gc > 250) findings.add(find(Cause.GAME, Severity.WARNING, "packetdoctor.lag.find.gc", seconds(gc)));
		Runtime rt = Runtime.getRuntime();
		int memory = (int) Math.round(100.0 * (rt.totalMemory() - rt.freeMemory()) / Math.max(1, rt.maxMemory()));
		if (memory >= 92) findings.add(find(Cause.GAME, Severity.WARNING, "packetdoctor.lag.find.memory", memory));

		// --- Rubber-banding: a symptom, pinned on whatever else is wrong ---
		if (corrections.size() >= 3) findings.add(find(Cause.SERVER, Severity.INFO, "packetdoctor.lag.find.corrections", corrections.size()));

		return new Snapshot(now, true, tps, source, targetTps, mspt, msptMax, causes, ping, jitter, lost.size(), fps, memory, List.copyOf(findings));
	}

	/** Starts and ends lag episodes; announces one once it has lasted a few seconds. */
	private synchronized void episodes(Snapshot s, long now) {
		Cause cause = s.primary();
		Finding top = null;
		if (cause != null) {
			for (Finding f : s.findings()) {
				if (f.cause() == cause && f.severity() != Severity.INFO && (top == null || f.severity().ordinal() > top.severity().ordinal())) {
					top = f;
				}
			}
		}
		if (cause == null) {
			if (current != null && quietSince == 0) quietSince = now;
			if (current != null && now - quietSince >= EPISODE_QUIET_MS) endEpisode(now);
			return;
		}
		quietSince = 0;
		if (current != cause) {
			if (current != null) endEpisode(now);
			current = cause;
			currentSince = now;
			currentAnnounced = false;
		}
		currentFinding = top;
		boolean urgent = top != null && top.severity() == Severity.DANGER;
		if (!currentAnnounced && top != null && (urgent || now - currentSince >= EPISODE_AFTER_MS)) {
			currentAnnounced = true;
			com.packetdoctor.PacketDoctor.LOGGER.info("Lag noticed ({}): {}", cause, top.text());
			Long last = lastToast.get(cause);
			if (last == null || now - last > 60_000) {
				lastToast.put(cause, now);
				PacketDoctorClient.notifyLag(cause, top);
			}
		}
	}

	private void endEpisode(long now) {
		if (current != null && currentAnnounced && currentFinding != null) {
			episodes.addFirst(new Episode(currentSince, now, current, currentFinding.text()));
			while (episodes.size() > 20) episodes.removeLast();
		}
		current = null;
		currentFinding = null;
		quietSince = 0;
	}

	private static Snapshot empty() {
		return new Snapshot(Util.getMillis(), false, null, "NONE", 20, null, null, List.of(), -1, 0, 0, 0, 0, List.of());
	}

	public static String causeText(ServerStatus.Cause c) {
		return String.format(Locale.ROOT, "%s (%.0f%%)", c.label(), c.percent()) + (c.mod() != null ? " - " + c.mod() : "");
	}

	public static String fmt(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	public static String seconds(long ms) {
		return ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
	}
}
