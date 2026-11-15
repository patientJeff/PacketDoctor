package com.packetdoctor.server;

import com.packetdoctor.server.perf.LagMonitor;
import com.packetdoctor.server.perf.PlayerSpots;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player is doing in the world, for admins: where they are, how fast they move,
 * how much new land they are exploring (which makes the server load and generate chunks),
 * and how much is loaded around them. Updated by the server thread once a second (the
 * area around each player every ten seconds).
 */
public final class PlayerInsight {
	/** The area counted around a player, in blocks each way. */
	public static final int NEARBY_BLOCKS = 128;
	private static final int MAX_VISITED = 50_000;
	private static final Map<UUID, Track> TRACKS = new ConcurrentHashMap<>();

	/** One player's recent movement and surroundings. */
	public static final class Track {
		public final UUID uuid;
		public volatile String name;
		public volatile String dim = "?";
		public volatile int x;
		public volatile int y;
		public volatile int z;
		public volatile boolean elytra;
		public volatile String gameMode = "?";
		public volatile long lastSeen;
		/** Horizontal positions, one per second: time, dimension hash, x, z. */
		private final Deque<double[]> positions = new ArrayDeque<>();
		private final Set<String> visited = new HashSet<>();
		private final Deque<Long> newChunkTimes = new ArrayDeque<>();
		public volatile int sessionChunks;
		public volatile int nearbyEntities;
		public volatile List<String> topNearbyEntities = List.of();
		public volatile int nearbyBlockEntities;
		public volatile List<String> topNearbyBlockEntities = List.of();
		public volatile long nearbyTime;

		Track(UUID uuid, String name) {
			this.uuid = uuid;
			this.name = name;
		}

		/** Average horizontal speed over the last {@code seconds}, in blocks per second (teleports and dimension changes left out). */
		public synchronized double speed(int seconds) {
			double dist = 0;
			double time = 0;
			double[] prev = null;
			long from = System.currentTimeMillis() - seconds * 1000L;
			for (double[] p : positions) {
				if (p[0] < from) continue;
				if (prev != null && prev[1] == p[1]) {
					double d = Math.hypot(p[2] - prev[2], p[3] - prev[3]);
					double dt = (p[0] - prev[0]) / 1000.0;
					if (d < 200) {
						dist += d;
						time += dt;
					}
				}
				prev = p;
			}
			return time <= 0 ? 0 : dist / time;
		}

		/** Chunks entered for the first time this session in the last {@code minutes}. */
		public synchronized int newChunks(int minutes) {
			long from = System.currentTimeMillis() - minutes * 60_000L;
			int n = 0;
			for (long t : newChunkTimes) if (t >= from) n++;
			return n;
		}

		synchronized void moved(long now, String dimension, double px, double pz) {
			positions.addLast(new double[]{now, dimension.hashCode(), px, pz});
			while (positions.size() > 120) positions.removeFirst();
			String chunk = dimension + " " + ((int) Math.floor(px) >> 4) + " " + ((int) Math.floor(pz) >> 4);
			if (visited.size() < MAX_VISITED && visited.add(chunk)) {
				sessionChunks++;
				if (positions.size() > 1) newChunkTimes.addLast(now); // the chunk they joined in isn't "explored"
			}
			while (!newChunkTimes.isEmpty() && now - newChunkTimes.peekFirst() > 30 * 60_000L) newChunkTimes.removeFirst();
		}
	}

	private PlayerInsight() {
	}

	public static @Nullable Track get(UUID uuid) {
		return TRACKS.get(uuid);
	}

	/** Every tick; does its work once a second. */
	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 20 != 0) return;
		long now = System.currentTimeMillis();
		List<PlayerSpots.Spot> spots = new ArrayList<>();
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		for (int i = 0; i < players.size(); i++) {
			ServerPlayer p = players.get(i);
			Track t = TRACKS.computeIfAbsent(p.getUUID(), id -> new Track(id, p.getGameProfile().name()));
			ServerLevel level = p.level();
			String dim = level.dimension().identifier().toString();
			t.name = p.getGameProfile().name();
			t.dim = dim;
			t.x = p.getBlockX();
			t.y = p.getBlockY();
			t.z = p.getBlockZ();
			t.elytra = p.isFallFlying();
			t.gameMode = p.gameMode().getName();
			t.lastSeen = now;
			t.moved(now, dim, p.getX(), p.getZ());
			spots.add(new PlayerSpots.Spot(t.name, dim, p.getX(), p.getZ()));
			// The surroundings every ten seconds, players spread over those ten seconds.
			if ((tick / 20 + i) % 10 == 0) surroundings(t, p, level);
		}
		PlayerSpots.update(spots);
		if (tick % 6000 == 0) TRACKS.values().removeIf(t -> now - t.lastSeen > 3_600_000L);
	}

	private static void surroundings(Track t, ServerPlayer p, ServerLevel level) {
		int r = NEARBY_BLOCKS;
		AABB box = new AABB(p.getX() - r, p.getY() - 256, p.getZ() - r, p.getX() + r, p.getY() + 256, p.getZ() + r);
		Map<String, Integer> entities = new HashMap<>();
		int entityCount = 0;
		for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof ServerPlayer))) {
			entityCount++;
			entities.merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
		}
		LagMonitor lag = LagMonitor.get();
		LagMonitor.Census census = lag == null ? null : lag.census();
		Map<String, Integer> blocks = new HashMap<>();
		int blockCount = 0;
		int cx = p.getBlockX() >> 4;
		int cz = p.getBlockZ() >> 4;
		int rc = r >> 4;
		for (int dx = -rc; dx <= rc; dx++) {
			for (int dz = -rc; dz <= rc; dz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					String type = be.typeHolder().getRegisteredName();
					// Only the kinds that tick: chests and signs cost nothing while nobody uses them.
					if (census != null && census.count("B " + type) == 0) continue;
					blockCount++;
					blocks.merge(type, 1, Integer::sum);
				}
			}
		}
		t.nearbyEntities = entityCount;
		t.topNearbyEntities = top(entities);
		t.nearbyBlockEntities = blockCount;
		t.topNearbyBlockEntities = top(blocks);
		t.nearbyTime = System.currentTimeMillis();
	}

	private static List<String> top(Map<String, Integer> counts) {
		return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
				.map(e -> e.getKey().replace("minecraft:", "") + " x" + e.getValue()).toList();
	}

	/** The players exploring the most new chunks lately, busiest first, as "Bob (120 new chunks, elytra)". */
	public static List<String> explorers(int minutes, int max) {
		List<Track> list = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Track t : TRACKS.values()) if (now - t.lastSeen < 10_000 && t.newChunks(minutes) > 0) list.add(t);
		list.sort((a, b) -> Integer.compare(b.newChunks(minutes), a.newChunks(minutes)));
		List<String> out = new ArrayList<>();
		for (Track t : list.subList(0, Math.min(max, list.size()))) {
			out.add(com.packetdoctor.Tr.t(t.elytra ? "packetdoctor.player.explorer_elytra" : "packetdoctor.player.explorer", t.name, t.newChunks(minutes),
					String.format(java.util.Locale.ROOT, "%.0f", t.speed(30))));
		}
		return out;
	}
}
