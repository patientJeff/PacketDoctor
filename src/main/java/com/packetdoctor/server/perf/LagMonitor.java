package com.packetdoctor.server.perf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.api.ConsoleLine;
import com.packetdoctor.api.LagCause;
import com.packetdoctor.api.LagSpikeInfo;
import com.packetdoctor.api.PerformanceInfo;
import com.packetdoctor.diagnose.Reports;
import com.packetdoctor.server.ServerConfig;
import com.packetdoctor.server.console.ConsoleCapture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Watches how long every server tick takes, and finds out what slow ticks were spent on.
 *
 * <p>A small background thread looks at the server thread's call stack every few
 * milliseconds, but only while a tick has already run long; a healthy server is never
 * sampled. Slow ticks in a row become one lag spike. TPS staying low becomes "overloaded".
 * A tick stuck for seconds is written to disk as it happens, so if the server is then
 * killed (or stopped by the watchdog), the next start can still say what it was stuck on.
 */
public final class LagMonitor {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String STUCK_FILE = "stuck-tick.json";
	private static final long MS = 1_000_000L;
	/** A tick is sampled once it has run this long. */
	private static final long SAMPLE_AFTER_NS = 25 * MS;
	private static final int HISTORY = 6000;
	private static final long SPIKE_GAP_MS = 2000;
	private static final long SPIKE_MAX_MS = 10_000;
	private static volatile @Nullable LagMonitor instance;

	/** Receives what the monitor finds, on the server thread. */
	public interface Listener {
		void onSpike(LagSpikeInfo spike);

		void onOverloaded(PerformanceInfo info);

		void onRecovered(PerformanceInfo info);
	}

	/** What a stuck tick looked like, saved while it is still stuck. */
	public record StuckTick(long since, long elapsedMs, List<LagCause> causes, List<String> frames) {
	}

	private record Context(long time, int loadedChunks, int entities, List<String> topEntities, int players) {
	}

	private final Supplier<ServerConfig> config;
	private final Listener listener;
	private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
	private final Object lock = new Object();

	// Written by the server thread, read by the sampler.
	private volatile @Nullable Thread serverThread;
	private volatile long tickStartNanos;
	/** When the server thread started its current stretch of work (a tick or a queued task); 0 when idle. */
	private volatile long busyStartNanos;
	private volatile long taskStartNanos;
	private volatile boolean inTick;
	private volatile boolean running;
	private @Nullable Thread sampler;

	// Guarded by lock.
	private final List<LagAnalysis.Sample> tickSamples = new ArrayList<>();
	private final Deque<List<LagAnalysis.Sample>> secondSamples = new ArrayDeque<>();
	private long currentSecond;
	private final long[] tickEnds = new long[HISTORY];
	private final long[] tickDurations = new long[HISTORY];
	private int tickPos;
	private int tickCount;
	private final Deque<LagSpikeInfo> spikes = new ArrayDeque<>();

	// Server thread only.
	private long gcAtTickStart;
	private long taskNanos;
	private @Nullable OpenSpike open;
	private long lowSeconds;
	private long goodSeconds;
	private int ticksSinceContext;
	private long lastCantKeepUpMs;
	private long lastCantKeepUpTicks;
	private long lastCantKeepUpTime;
	private volatile boolean overloaded;
	private volatile float targetTps = 20;
	private volatile @Nullable Context context;
	private volatile long stuckWrittenAt;
	private volatile boolean stuckLogged;

	private static final class OpenSpike {
		final long start;
		long lastSlowTick;
		long durationMs;
		long overMs;
		long gcMs;
		int ticks;
		final List<LagAnalysis.Sample> samples = new ArrayList<>();

		OpenSpike(long start) {
			this.start = start;
		}
	}

	private LagMonitor(Supplier<ServerConfig> config, Listener listener) {
		this.config = config;
		this.listener = listener;
	}

	public static void init(Supplier<ServerConfig> config, Listener listener) {
		instance = new LagMonitor(config, listener);
	}

	public static @Nullable LagMonitor get() {
		return instance;
	}

	// --- Lifecycle -----------------------------------------------------------------------

	/** A dedicated server is starting: begin watching. */
	public void start() {
		synchronized (lock) {
			Arrays.fill(tickEnds, 0);
			tickPos = 0;
			tickCount = 0;
			spikes.clear();
			secondSamples.clear();
		}
		open = null;
		overloaded = false;
		running = true;
		Thread t = new Thread(this::sampleLoop, "Packet Doctor lag sampler");
		t.setDaemon(true);
		t.setPriority(Thread.MAX_PRIORITY);
		t.start();
		sampler = t;
	}

	public void stop() {
		running = false;
		tickStartNanos = 0;
		busyStartNanos = 0;
		taskStartNanos = 0;
		inTick = false;
		Thread t = sampler;
		if (t != null) t.interrupt();
		sampler = null;
		clearStuck();
	}

	private boolean enabled() {
		return running && config.get().lagMonitor;
	}

	// --- Ticks (server thread) -----------------------------------------------------------

	public static void onTickStart() {
		LagMonitor m = instance;
		if (m != null && m.enabled()) m.tickStart();
	}

	public static void onTickEnd(MinecraftServer server) {
		LagMonitor m = instance;
		if (m != null && m.enabled()) {
			try {
				m.tickEnd(server);
			} catch (RuntimeException e) {
				PacketDoctor.LOGGER.debug("Lag monitor failed", e);
			}
		}
	}

	/**
	 * Work queued for the server thread (console and RCON commands, plugin and mod tasks)
	 * runs between ticks, so it is timed and sampled too, and counted with the next tick.
	 */
	public static void onTaskStart() {
		LagMonitor m = instance;
		if (m != null && m.enabled() && !m.inTick && m.taskStartNanos == 0) {
			m.serverThread = Thread.currentThread();
			long now = System.nanoTime();
			m.taskStartNanos = now;
			m.busyStartNanos = now;
		}
	}

	public static void onTaskEnd() {
		LagMonitor m = instance;
		if (m == null || m.inTick) return;
		long start = m.taskStartNanos;
		if (start == 0) return;
		m.taskStartNanos = 0;
		m.busyStartNanos = 0;
		m.taskNanos += System.nanoTime() - start;
	}

	private void tickStart() {
		serverThread = Thread.currentThread();
		gcAtTickStart = gcMillis();
		inTick = true;
		long now = System.nanoTime();
		tickStartNanos = now;
		busyStartNanos = now;
	}

	private void tickEnd(MinecraftServer server) {
		long start = tickStartNanos;
		tickStartNanos = 0;
		busyStartNanos = 0;
		inTick = false;
		if (start == 0) return;
		long nowNanos = System.nanoTime();
		long durationNs = nowNanos - start + taskNanos;
		taskNanos = 0;
		long now = System.currentTimeMillis();
		long durationMs = durationNs / MS;
		targetTps = server.tickRateManager().tickrate();
		long budgetMs = Math.max(1, 1000 / Math.max(1, (long) targetTps));
		List<LagAnalysis.Sample> samples;
		synchronized (lock) {
			tickEnds[tickPos] = nowNanos;
			tickDurations[tickPos] = durationNs;
			tickPos = (tickPos + 1) % HISTORY;
			tickCount = Math.min(tickCount + 1, HISTORY);
			samples = tickSamples.isEmpty() ? List.of() : new ArrayList<>(tickSamples);
			tickSamples.clear();
			// Only ticks that really ran over count towards "what slow ticks were spent on".
			if (durationMs > budgetMs && !samples.isEmpty()) {
				long second = now / 1000;
				if (second != currentSecond || secondSamples.isEmpty()) {
					currentSecond = second;
					secondSamples.addLast(new ArrayList<>());
					while (secondSamples.size() > 60) secondSamples.removeFirst();
				}
				List<LagAnalysis.Sample> bucket = secondSamples.getLast();
				if (bucket.size() < 2000) bucket.addAll(samples.subList(0, Math.min(samples.size(), 2000 - bucket.size())));
			}
		}
		if (stuckWrittenAt != 0) {
			clearStuck();
			if (stuckLogged) PacketDoctor.LOGGER.info("The stuck tick finished after {}", LagAnalysis.seconds(durationMs));
		}

		if (durationMs >= config.get().lagSpikeMs) {
			if (open == null || now - open.lastSlowTick > SPIKE_GAP_MS || now - open.start > SPIKE_MAX_MS) {
				if (open != null) finishSpike(server);
				open = new OpenSpike(now - durationMs);
			}
			open.lastSlowTick = now;
			open.durationMs += durationMs;
			open.overMs += Math.max(0, durationMs - budgetMs);
			open.gcMs += Math.max(0, gcMillis() - gcAtTickStart);
			open.ticks++;
			if (open.samples.size() < 50_000) open.samples.addAll(samples);
		} else if (open != null && now - open.lastSlowTick > SPIKE_GAP_MS) {
			finishSpike(server);
		}

		if (++ticksSinceContext >= 100) {
			ticksSinceContext = 0;
			updateContext(server);
			checkOverload(server);
		}
	}

	private void finishSpike(MinecraftServer server) {
		OpenSpike s = open;
		open = null;
		if (s == null) return;
		double gcShare = s.durationMs == 0 ? 0 : (double) s.gcMs / s.durationMs;
		List<LagCause> causes = LagAnalysis.causes(s.samples, gcShare);
		int ticksLost = (int) Math.max(s.ticks > 0 ? 1 : 0, s.overMs * targetTps / 1000);
		if (System.currentTimeMillis() - lastCantKeepUpTime < 10_000 && lastCantKeepUpTicks > ticksLost) ticksLost = (int) lastCantKeepUpTicks;
		String summary = LagAnalysis.summary(s.durationMs, ticksLost, causes);

		List<String> console = new ArrayList<>();
		ConsoleCapture capture = ConsoleCapture.get();
		if (capture != null) {
			for (ConsoleLine l : capture.warningsBetween(s.start - 1000, s.lastSlowTick + 1000, 6)) console.add(ConsoleCapture.format(l));
		}
		if (context == null || System.currentTimeMillis() - context.time() > 5000) updateContext(server);
		Context ctx = context;
		LagSpikeInfo info = new LagSpikeInfo(s.start, s.durationMs, ticksLost, s.ticks, summary, causes, List.copyOf(console),
				ctx == null ? List.of() : ctx.topEntities());
		synchronized (lock) {
			spikes.addFirst(info);
			while (spikes.size() > 50) spikes.removeLast();
		}
		listener.onSpike(info);
	}

	/** Records "Can't keep up!" from the console, which has the server's own count of ticks behind. */
	public void onCantKeepUp(long msBehind, long ticksBehind) {
		lastCantKeepUpMs = msBehind;
		lastCantKeepUpTicks = ticksBehind;
		lastCantKeepUpTime = System.currentTimeMillis();
	}

	/** Once every 100 ticks: has TPS stayed low (or recovered)? */
	private void checkOverload(MinecraftServer server) {
		double tps = tps(10);
		long now = System.currentTimeMillis();
		if (tps < targetTps * 0.9) {
			lowSeconds += 5;
			goodSeconds = 0;
		} else if (tps >= targetTps * 0.97) {
			goodSeconds += 5;
			lowSeconds = 0;
		}
		if (!overloaded && lowSeconds >= 15) {
			overloaded = true;
			listener.onOverloaded(performance());
		} else if (overloaded && goodSeconds >= 30) {
			overloaded = false;
			listener.onRecovered(performance());
		}
	}

	private void updateContext(MinecraftServer server) {
		int chunks = 0;
		int entities = 0;
		Map<String, Integer> byType = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			chunks += level.getChunkSource().getLoadedChunksCount();
			for (Entity e : level.getAllEntities()) {
				entities++;
				byType.merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
			}
		}
		List<String> top = byType.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
				.map(e -> e.getKey() + " x" + e.getValue()).toList();
		context = new Context(System.currentTimeMillis(), chunks, entities, top, server.getPlayerCount());
	}

	// --- The sampler thread ----------------------------------------------------------------

	private void sampleLoop() {
		while (running) {
			try {
				Thread.sleep(Math.max(1, config.get().lagSampleIntervalMs));
			} catch (InterruptedException e) {
				if (!running) return;
			}
			try {
				sampleOnce();
			} catch (Throwable t) {
				PacketDoctor.LOGGER.debug("Lag sample failed", t);
			}
		}
	}

	private void sampleOnce() {
		long start = busyStartNanos;
		Thread thread = serverThread;
		if (start == 0 || thread == null || !config.get().lagMonitor) return;
		long elapsed = System.nanoTime() - start;
		if (elapsed < SAMPLE_AFTER_NS) return;

		StackTraceElement[] stack = thread.getStackTrace();
		Thread.State state = thread.getState();
		if (busyStartNanos != start) return; // that work ended while we looked
		LagAnalysis.Sample sample = LagAnalysis.classify(stack, state);

		List<LagAnalysis.Sample> copy = null;
		synchronized (lock) {
			if (tickSamples.size() < 100_000) tickSamples.add(sample);
			if (elapsed > 5_000 * MS && System.currentTimeMillis() - stuckWrittenAt > 5000) copy = new ArrayList<>(tickSamples);
		}
		if (copy != null) writeStuck(elapsed / MS, copy, stack);
	}

	/** The server has been on one tick for seconds: save what it is doing, in case it never finishes. */
	private void writeStuck(long elapsedMs, List<LagAnalysis.Sample> samples, StackTraceElement[] stack) {
		List<LagCause> causes = LagAnalysis.causes(samples, 0);
		List<String> frames = new ArrayList<>();
		for (int i = 0; i < Math.min(30, stack.length); i++) frames.add(stack[i].toString());
		StuckTick stuck = new StuckTick(System.currentTimeMillis() - elapsedMs, elapsedMs, causes, frames);
		try {
			Files.createDirectories(Reports.folder());
			Files.writeString(Reports.folder().resolve(STUCK_FILE), GSON.toJson(stuck));
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.debug("Couldn't save the stuck tick", e);
		}
		stuckWrittenAt = System.currentTimeMillis();
		if (!stuckLogged && elapsedMs >= 10_000) {
			stuckLogged = true;
			PacketDoctor.LOGGER.warn("The server has been stuck on one tick for {}: {}", LagAnalysis.seconds(elapsedMs),
					causes.isEmpty() ? "unknown" : LagAnalysis.line(causes.getFirst()));
		}
	}

	private void clearStuck() {
		stuckWrittenAt = 0;
		stuckLogged = false;
		try {
			Files.deleteIfExists(Reports.folder().resolve(STUCK_FILE));
		} catch (IOException ignored) {
		}
	}

	/** A stuck tick saved by the previous run (it never finished), then forgets it. */
	public static @Nullable StuckTick takePreviousStuck() {
		Path file = Reports.folder().resolve(STUCK_FILE);
		if (!Files.exists(file)) return null;
		try (Reader r = Files.newBufferedReader(file)) {
			return GSON.fromJson(r, StuckTick.class);
		} catch (IOException | RuntimeException e) {
			return null;
		} finally {
			try {
				Files.deleteIfExists(file);
			} catch (IOException ignored) {
			}
		}
	}

	/** What the current (unfinished) tick has been doing, for a crash that happens during it. */
	public List<LagCause> currentTickCauses() {
		List<LagAnalysis.Sample> copy;
		synchronized (lock) {
			copy = new ArrayList<>(tickSamples);
		}
		return copy.isEmpty() ? List.of() : LagAnalysis.causes(copy, 0);
	}

	/** How long the current tick has been running, in milliseconds (0 between ticks). */
	public long currentTickMs() {
		long start = busyStartNanos;
		return start == 0 ? 0 : (System.nanoTime() - start) / MS;
	}

	// --- Reading (any thread) --------------------------------------------------------------

	public PerformanceInfo performance() {
		List<LagAnalysis.Sample> recent = new ArrayList<>();
		double avg;
		double max;
		synchronized (lock) {
			for (List<LagAnalysis.Sample> b : secondSamples) recent.addAll(b);
			long[] d = lastDurations(1200);
			avg = Arrays.stream(d).average().orElse(0) / MS;
			max = Arrays.stream(d).max().orElse(0) / (double) MS;
		}
		Context ctx = context;
		return new PerformanceInfo(System.currentTimeMillis(), round(tps(10)), round(tps(60)), round(tps(300)), targetTps, round(avg),
				round(max), overloaded, recent.isEmpty() ? List.of() : LagAnalysis.causes(recent, 0),
				ctx == null ? 0 : ctx.loadedChunks(), ctx == null ? 0 : ctx.entities(), ctx == null ? List.of() : ctx.topEntities(),
				ctx == null ? 0 : ctx.players());
	}

	public List<LagSpikeInfo> spikes(int max) {
		synchronized (lock) {
			return spikes.stream().limit(Math.max(0, max)).toList();
		}
	}

	/** Ticks per second over the last {@code seconds}, at most the target. */
	public double tps(int seconds) {
		synchronized (lock) {
			if (tickCount < 2) return targetTps;
			long newest = tickEnds[(tickPos - 1 + HISTORY) % HISTORY];
			long from = newest - seconds * 1_000_000_000L;
			int n = 0;
			long oldest = newest;
			for (int i = 1; i <= tickCount; i++) {
				long t = tickEnds[(tickPos - i + HISTORY) % HISTORY];
				if (t < from) break;
				oldest = t;
				n++;
			}
			// Time from the oldest counted tick to now, so a server frozen right now shows low TPS.
			double span = (System.nanoTime() - oldest) / 1e9;
			if (n < 2 || span <= 0) return targetTps;
			return Math.min(targetTps, (n - 1) / Math.max(span, 0.05));
		}
	}

	private long[] lastDurations(int n) {
		int count = Math.min(n, tickCount);
		long[] out = new long[count];
		for (int i = 0; i < count; i++) out[i] = tickDurations[(tickPos - 1 - i + HISTORY) % HISTORY];
		return out;
	}

	private long gcMillis() {
		long total = 0;
		for (GarbageCollectorMXBean b : gcBeans) total += Math.max(0, b.getCollectionTime());
		return total;
	}

	private static double round(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
