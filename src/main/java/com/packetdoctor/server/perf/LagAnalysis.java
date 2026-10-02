package com.packetdoctor.server.perf;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.Tr;
import com.packetdoctor.api.LagCause;
import com.packetdoctor.diagnose.ModBlame;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns snapshots of the server thread's call stack into plain causes ("generating new
 * terrain, mostly in Terralith's code"). This is a sampling profiler in miniature: the
 * more snapshots land in something, the more time it took.
 *
 * <p>Each snapshot is read from the innermost call outwards. The first call that says what
 * kind of work it is decides the kind, so an entity that walks into an unloaded chunk counts
 * as chunk loading, not as entities.
 */
public final class LagAnalysis {
	/** Entity and block classes too general to name as an example. */
	private static final Set<String> GENERIC = Set.of("Entity", "LivingEntity", "Mob", "PathfinderMob", "AgeableMob", "Animal",
			"Monster", "AbstractVillager", "TamableAnimal", "WaterAnimal", "FlyingMob", "Projectile", "AbstractArrow", "Block",
			"BlockBehaviour", "BaseEntityBlock", "BlockEntity", "BlockStateBase", "EntityType", "Brain", "Leashable", "Targeting",
			"BlockState", "StateHolder", "FlowingFluid", "Fluid");

	/** What one snapshot of the server thread showed. */
	public record Sample(LagKind kind, @Nullable String mod, @Nullable String example) {
	}

	private LagAnalysis() {
	}

	public static Sample classify(StackTraceElement[] frames, Thread.State state) {
		LagKind kind = null;
		int kindAt = -1;
		String example = null;
		String innermostMod = null;
		int modAt = -1;
		for (int i = 0; i < frames.length; i++) {
			StackTraceElement f = frames[i];
			String cls = f.getClassName();
			if (innermostMod == null) {
				String id = ModBlame.modForFrame(f);
				if (id != null && !ModBlame.isPlatform(id) && !id.equals(PacketDoctor.MOD_ID)) {
					innermostMod = id;
					modAt = i;
				}
			}
			if (example == null) example = example(cls);
			if (kind == null) {
				kind = kindOf(cls, f.getMethodName());
				if (kind != null) kindAt = i;
			}
			if (kind != null && innermostMod != null && example != null) break;
		}
		// A mod counts when its code ran inside (or right around) the work, not when it merely
		// wraps the whole tick, which some performance mods do on every stack.
		String mod = innermostMod != null && (kind == null || modAt <= kindAt + 2) ? innermostMod : null;
		if (kind == null) {
			boolean waiting = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED;
			kind = mod != null ? LagKind.MOD : waiting ? LagKind.WAITING : LagKind.OTHER;
		}
		return new Sample(kind, mod, example);
	}

	private static @Nullable LagKind kindOf(String cls, String m) {
		if (cls.startsWith("net.minecraft.world.level.levelgen") || cls.contains(".chunk.status.") || cls.endsWith("ChunkGenerator")
				|| cls.endsWith("WorldGenRegion") || cls.startsWith("net.minecraft.world.level.biome.")) {
			return LagKind.WORLDGEN;
		}
		if (cls.endsWith("ServerExplosion")) return LagKind.EXPLOSIONS;
		if (cls.contains(".level.pathfinder.") || cls.contains("PathNavigation")) return LagKind.PATHFINDING;
		if (cls.endsWith("HopperBlockEntity")) return LagKind.HOPPERS;
		// NeighborUpdater lives in the redstone package but carries every block update, not just redstone.
		if (cls.contains(".level.redstone.") && (cls.contains("NeighborUpdater") || cls.endsWith("Orientation"))) return LagKind.SCHEDULED_TICKS;
		if (cls.contains(".level.redstone.") || cls.endsWith("RedStoneWireBlock") || cls.contains(".block.piston.")
				|| cls.endsWith("DiodeBlock") || cls.endsWith("ObserverBlock") || cls.endsWith("RepeaterBlock") || cls.endsWith("ComparatorBlock")) {
			return LagKind.REDSTONE;
		}
		if (cls.contains(".world.level.lighting.")) return LagKind.LIGHTING;
		if (cls.contains(".world.entity.ai.")) return LagKind.MOB_AI;
		if (cls.endsWith("NaturalSpawner") || cls.endsWith("Spawner") && cls.startsWith("net.minecraft.") || m.equals("tickCustomSpawners")) {
			return LagKind.SPAWNING;
		}
		if (cls.contains(".chunk.storage.") || cls.contains(".level.storage.") || cls.endsWith("RegionFile")
				|| (cls.endsWith("ChunkMap") || cls.equals("net.minecraft.server.MinecraftServer")) && (m.startsWith("save") || m.equals("autoSave"))) {
			return LagKind.SAVING;
		}
		if (cls.contains("ChunkMap$TrackedEntity") || cls.endsWith("ServerEntity")) return LagKind.ENTITY_TRACKING;
		if (cls.endsWith("ServerChunkCache") || cls.contains("ServerChunkCache$") || cls.endsWith("ChunkMap") || cls.contains("ChunkHolder")
				|| cls.contains("DistanceManager") || cls.contains("TicketStorage")) {
			return LagKind.CHUNK_LOADING;
		}
		if (cls.endsWith("LevelTicks")) return LagKind.SCHEDULED_TICKS;
		if (cls.endsWith("ServerLevel") && (m.startsWith("tickChunk") || m.equals("tickBlock") || m.equals("tickFluid")
				|| m.startsWith("tickPrecipitation") || m.startsWith("tickThunder"))) {
			return LagKind.CHUNK_TICKS;
		}
		if (cls.contains("TickingBlockEntity") || cls.contains("BlockEntity") && m.toLowerCase(Locale.ROOT).contains("tick")) {
			return LagKind.BLOCK_ENTITIES;
		}
		if (cls.endsWith("ServerLevel") && (m.equals("tickNonPassenger") || m.equals("tickPassenger")) || cls.endsWith("EntityTickList")
				|| cls.startsWith("net.minecraft.world.entity.") && (m.equals("tick") || m.equals("aiStep") || m.equals("baseTick"))) {
			return LagKind.ENTITIES;
		}
		if (cls.contains(".commands.") || cls.startsWith("com.mojang.brigadier.") || cls.endsWith("ServerFunctionManager")
				|| cls.contains(".commands.functions.")) {
			return LagKind.COMMANDS;
		}
		if (cls.startsWith("net.minecraft.server.network.") || cls.endsWith("PacketUtils") || cls.endsWith("PacketProcessor")) {
			return LagKind.PLAYER_PACKETS;
		}
		if (cls.endsWith("PlayerList") || cls.endsWith("ServerPlayer")) return LagKind.PLAYERS;
		return null;
	}

	/**
	 * "Villager" for net.minecraft.world.entity.npc.villager.Villager; null for anything that
	 * isn't an actual entity, block or block entity (helpers like EntitySelector) or is too general.
	 */
	private static @Nullable String example(String cls) {
		if (!cls.startsWith("net.minecraft.world.entity.") && !cls.startsWith("net.minecraft.world.level.block.")) return null;
		if (cls.contains(".entity.ai.")) return null;
		int inner = cls.indexOf('$');
		String outer = inner >= 0 ? cls.substring(0, inner) : cls;
		String simple = outer.substring(outer.lastIndexOf('.') + 1);
		if (simple.isEmpty() || GENERIC.contains(simple) || simple.startsWith("Abstract")) return null;
		return IS_THING.computeIfAbsent(outer, LagAnalysis::isThing) ? simple : null;
	}

	private static final Map<String, Boolean> IS_THING = new java.util.concurrent.ConcurrentHashMap<>();

	private static boolean isThing(String className) {
		try {
			Class<?> c = Class.forName(className, false, LagAnalysis.class.getClassLoader());
			return Entity.class.isAssignableFrom(c) || Block.class.isAssignableFrom(c) || BlockEntity.class.isAssignableFrom(c);
		} catch (Throwable t) {
			return false;
		}
	}

	/** "Entities (mobs...)" to "entities (mobs...)" mid-sentence, but "Mob AI" stays "mob AI". */
	private static String midSentence(String label) {
		if (label.length() < 2 || !Character.isUpperCase(label.charAt(0)) || Character.isUpperCase(label.charAt(1))) return label;
		return Character.toLowerCase(label.charAt(0)) + label.substring(1);
	}

	// --- Adding samples up ----------------------------------------------------------------

	/**
	 * Groups samples into causes, biggest first. {@code gcShare} (0-1) is the part of the
	 * time spent in garbage collection, which snapshots can't see (everything is paused).
	 */
	public static List<LagCause> causes(List<Sample> samples, double gcShare) {
		List<LagCause> out = new ArrayList<>();
		double gc = Math.clamp(gcShare, 0, 1);
		if (gc >= 0.05 || samples.isEmpty() && gc > 0) {
			out.add(new LagCause(LagKind.GC.name(), LagKind.GC.label(), round(gc * 100), null, List.of(), LagKind.GC.advice("")));
		}
		if (samples.isEmpty()) return out;

		Map<String, List<Sample>> groups = new LinkedHashMap<>();
		for (Sample s : samples) {
			String key = s.kind() == LagKind.MOD ? "MOD:" + s.mod() : s.kind().name();
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
		}
		double scale = (1 - gc) * 100.0 / samples.size();
		List<LagCause> found = new ArrayList<>();
		for (List<Sample> group : groups.values()) {
			double percent = group.size() * scale;
			if (percent < 3 && found.size() >= 3) continue;
			LagKind kind = group.getFirst().kind();
			String mod = top(group, Sample::mod, 0.3);
			String modLabel = mod == null ? null : label(mod);
			List<String> examples = topList(group, Sample::example, 3);
			found.add(new LagCause(kind.name(), kind.label(), round(percent), modLabel, examples, kind.advice(modLabel == null ? "" : modLabel)));
		}
		found.sort(Comparator.comparingDouble(LagCause::percent).reversed());
		out.addAll(found);
		out.sort(Comparator.comparingDouble(LagCause::percent).reversed());
		return out.size() > 6 ? List.copyOf(out.subList(0, 6)) : List.copyOf(out);
	}

	/** One or two sentences about a stretch of slow time. */
	public static String summary(long durationMs, int ticksLost, List<LagCause> causes) {
		String seconds = seconds(durationMs);
		if (causes.isEmpty()) return Tr.t("packetdoctor.lag.summary.unknown", seconds, ticksLost);
		LagCause top = causes.getFirst();
		StringBuilder sb = new StringBuilder(Tr.t("packetdoctor.lag.summary", seconds, ticksLost, midSentence(top.label()),
				String.format(Locale.ROOT, "%.0f", top.percent())));
		if (top.mod() != null) sb.append(' ').append(Tr.t("packetdoctor.lag.summary.mod", top.mod()));
		if (!top.examples().isEmpty()) sb.append(' ').append(Tr.t("packetdoctor.lag.summary.examples", String.join(", ", top.examples())));
		if (causes.size() > 1) {
			LagCause next = causes.get(1);
			sb.append(' ').append(Tr.t("packetdoctor.lag.summary.next", midSentence(next.label()),
					String.format(Locale.ROOT, "%.0f", next.percent())));
		}
		return sb.toString();
	}

	/** One short line per cause, e.g. "64% Generating new terrain - Terralith (terralith) - Villager". */
	public static String line(LagCause c) {
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%.0f%% %s", c.percent(), c.label()));
		if (c.mod() != null) sb.append(" - ").append(c.mod());
		if (!c.examples().isEmpty()) sb.append(" - ").append(String.join(", ", c.examples()));
		return sb.toString();
	}

	public static String seconds(long ms) {
		return ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
	}

	private static String label(String modId) {
		String name = ModBlame.name(modId);
		return name.equals(modId) ? name : name + " (" + modId + ")";
	}

	private static double round(double v) {
		return Math.round(v * 10) / 10.0;
	}

	/** The most common value, if it makes up at least {@code minShare} of the group. */
	private static @Nullable String top(List<Sample> group, java.util.function.Function<Sample, @Nullable String> f, double minShare) {
		Map<String, Integer> counts = new HashMap<>();
		for (Sample s : group) {
			String v = f.apply(s);
			if (v != null) counts.merge(v, 1, Integer::sum);
		}
		return counts.entrySet().stream().max(Map.Entry.comparingByValue())
				.filter(e -> e.getValue() >= group.size() * minShare).map(Map.Entry::getKey).orElse(null);
	}

	private static List<String> topList(List<Sample> group, java.util.function.Function<Sample, @Nullable String> f, int max) {
		Map<String, Integer> counts = new HashMap<>();
		for (Sample s : group) {
			String v = f.apply(s);
			if (v != null) counts.merge(v, 1, Integer::sum);
		}
		return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.filter(e -> e.getValue() >= Math.max(2, group.size() / 20)).limit(max).map(Map.Entry::getKey).toList();
	}
}
