package com.packetdoctor.server.perf;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.Tr;
import com.packetdoctor.diagnose.ModBlame;
import com.packetdoctor.diagnose.Reports;
import com.packetdoctor.net.PacketNames;
import com.packetdoctor.server.PlayerInsight;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static com.packetdoctor.Tr.t;

/**
 * Turns a {@link ProfileData} into what an admin needs: the biggest causes of lag, ranked,
 * each with what exactly is involved, where it is, and what to do about it. Four views of
 * the same time, so they overlap: by mod, by place (chunk), by block entity or entity type,
 * and by kind of work.
 *
 * <p>Live details (what is in a chunk right now) read the world, so building findings with
 * a server must happen on the server thread.
 */
public final class LagReport {
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter HUMAN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	public enum Type {
		MOD, PLACE, THING, WORK
	}

	/**
	 * One cause.
	 *
	 * @param key       the mod id, spot key, thing key or LagKind name
	 * @param percent   share of the server thread's busy time, 0-100
	 * @param msPerTick about how many milliseconds of each tick it costs
	 * @param where     a place to teleport to, if there is one
	 */
	public record Finding(Type type, String key, String title, double percent, double msPerTick, List<String> details, List<String> actions,
						  @Nullable Where where) {
	}

	private LagReport() {
	}

	// --- Findings ----------------------------------------------------------------------

	/** All findings, biggest first. */
	public static List<Finding> findings(ProfileData d, LagMonitor.@Nullable Census census, @Nullable MinecraftServer server) {
		List<Finding> out = new ArrayList<>();
		if (d.isEmpty()) return out;
		out.addAll(mods(d, census, 2, 8));
		out.addAll(places(d, census, server, 3, 6));
		out.addAll(things(d, census, 3, 6));
		out.addAll(work(d, 5, 6));
		out.sort(Comparator.comparingDouble(Finding::percent).reversed());
		return out;
	}

	public static List<Finding> mods(ProfileData d, LagMonitor.@Nullable Census census, double minPercent, int max) {
		List<Finding> out = new ArrayList<>();
		for (Map.Entry<String, Long> e : d.mods.top(50)) {
			if (out.size() >= max) break;
			String mod = e.getKey();
			if (mod.equals(PacketDoctor.MOD_ID) || ModBlame.isPlatform(mod)) continue;
			if (d.percent(e.getValue()) < minPercent) break;
			out.add(mod(d, census, mod));
		}
		return out;
	}

	/** Everything known about one mod's share of the time. */
	public static Finding mod(ProfileData d, LagMonitor.@Nullable Census census, String mod) {
		long own = d.mods.get(mod);
		String label = label(mod);
		double pct = d.percent(own);
		List<String> details = new ArrayList<>();
		List<String> actions = new ArrayList<>();

		List<Map.Entry<String, Long>> methods = d.modMethods.topWithin(mod, 6);
		if (!methods.isEmpty()) {
			details.add(t("packetdoctor.find.mod.doing"));
			for (Map.Entry<String, Long> m : methods) details.add("  " + share(m.getValue(), own) + "  " + m.getKey());
		}
		List<Map.Entry<String, Long>> vias = d.modVias.topWithin(mod, 4);
		if (!vias.isEmpty()) {
			details.add(t("packetdoctor.find.mod.how"));
			for (Map.Entry<String, Long> v : vias) details.add("  " + share(v.getValue(), own) + "  " + v.getKey());
		}
		List<Map.Entry<String, Long>> kinds = d.modKinds.topWithin(mod, 4);
		List<String> kindLines = new ArrayList<>();
		for (Map.Entry<String, Long> k : kinds) {
			LagKind kind = kind(k.getKey());
			if (kind == null || kind == LagKind.MOD) continue;
			kindLines.add("  " + share(k.getValue(), own) + "  " + kind.label());
		}
		if (!kindLines.isEmpty()) {
			details.add(t("packetdoctor.find.mod.while"));
			details.addAll(kindLines);
		}
		List<Map.Entry<String, Long>> things = d.modThings.topWithin(mod, 4);
		if (!things.isEmpty()) {
			details.add(t("packetdoctor.find.mod.for"));
			for (Map.Entry<String, Long> th : things) details.add("  " + share(th.getValue(), own) + "  " + thingLabel(th.getKey(), census));
		}
		List<Map.Entry<String, Long>> spots = d.modSpots.topWithin(mod, 3);
		Where busiest = null;
		if (!spots.isEmpty()) {
			details.add(t("packetdoctor.find.mod.where"));
			for (Map.Entry<String, Long> s : spots) {
				Where w = d.place(s.getKey());
				if (w == null) continue;
				if (busiest == null) busiest = w;
				details.add("  " + share(s.getValue(), own) + "  " + placeLabel(w));
			}
		}

		// What to do depends on how its time is spent.
		String topVia = vias.isEmpty() ? "" : vias.getFirst().getKey();
		LagKind topKind = null;
		for (Map.Entry<String, Long> k : kinds) {
			LagKind kind = kind(k.getKey());
			if (kind != null && kind != LagKind.MOD) {
				topKind = kind;
				break;
			}
		}
		String ownThing = null;
		for (Map.Entry<String, Long> th : things) {
			if (namespace(th.getKey()).equals(mod)) {
				ownThing = th.getKey();
				break;
			}
		}
		if (ownThing != null) {
			int count = census == null ? 0 : census.count(ownThing);
			actions.add(t("packetdoctor.find.mod.act.own", typeOf(ownThing), count, busiest == null ? "?" : placeLabel(busiest)));
		}
		if (topVia.startsWith(prefixOf("packetdoctor.via.injected"))) {
			actions.add(t("packetdoctor.find.mod.act.injected", afterPrefix(topVia, "packetdoctor.via.injected")));
		} else if (topVia.startsWith(prefixOf("packetdoctor.via.fabric"))) {
			actions.add(t("packetdoctor.find.mod.act.callback", afterPrefix(topVia, "packetdoctor.via.fabric")));
		}
		if (topKind == LagKind.WORLDGEN) actions.add(t("packetdoctor.find.mod.act.worldgen"));
		else if (topKind != null && ownThing == null) actions.add(topKind.advice(label));
		String version = FabricLoader.getInstance().getModContainer(mod).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		actions.add(t("packetdoctor.find.mod.act.update", label, version));
		actions.add(t("packetdoctor.find.mod.act.report", label));

		return new Finding(Type.MOD, mod, t("packetdoctor.find.mod.title", label, pct(pct), ms(d, own)), pct, msOf(d, own), details, actions, busiest);
	}

	public static List<Finding> places(ProfileData d, LagMonitor.@Nullable Census census, @Nullable MinecraftServer server, double minPercent, int max) {
		List<Finding> out = new ArrayList<>();
		for (Map.Entry<String, Long> e : d.spots.top(max)) {
			double pct = d.percent(e.getValue());
			if (pct < minPercent) break;
			Where w = d.place(e.getKey());
			if (w == null) continue;
			long own = e.getValue();
			List<String> details = new ArrayList<>();
			List<String> actions = new ArrayList<>();
			if (w.player() != null) details.add(t("packetdoctor.find.place.near", w.player()));
			List<Map.Entry<String, Long>> things = d.spotThings.topWithin(e.getKey(), 4);
			if (!things.isEmpty()) {
				details.add(t("packetdoctor.find.place.what"));
				for (Map.Entry<String, Long> th : things) details.add("  " + share(th.getValue(), own) + "  " + typeOf(th.getKey()) + " (" + kindWord(th.getKey()) + ")");
			}
			List<Map.Entry<String, Long>> mods = d.spotMods.topWithin(e.getKey(), 3);
			for (Map.Entry<String, Long> m : mods) {
				if (ModBlame.isPlatform(m.getKey()) || m.getKey().equals(PacketDoctor.MOD_ID)) continue;
				details.add(t("packetdoctor.find.place.mod", label(m.getKey()), share(m.getValue(), own)));
			}
			String live = server == null ? null : liveContents(server, w);
			if (live != null) details.add(t("packetdoctor.find.place.now", live));
			if (!things.isEmpty()) actions.add(advice(things.getFirst().getKey()));
			if (w.player() != null) actions.add(t("packetdoctor.find.place.act.owner", w.player()));
			actions.add(t("packetdoctor.find.place.act.visit"));
			String title = t("packetdoctor.find.place.title", w.dimName(), w.chunkX() * 16 + 8, w.chunkZ() * 16 + 8, pct(pct), ms(d, own));
			out.add(new Finding(Type.PLACE, e.getKey(), title, pct, msOf(d, own), details, actions, w));
		}
		return out;
	}

	public static List<Finding> things(ProfileData d, LagMonitor.@Nullable Census census, double minPercent, int max) {
		List<Finding> out = new ArrayList<>();
		for (Map.Entry<String, Long> e : d.things.top(max)) {
			double pct = d.percent(e.getValue());
			if (pct < minPercent) break;
			String thing = e.getKey();
			long own = e.getValue();
			int count = census == null ? 0 : census.count(thing);
			List<String> details = new ArrayList<>();
			if (count > 0) {
				details.add(t("packetdoctor.find.thing.each", PacketNames.count(count), String.format(Locale.ROOT, "%.3f", msOf(d, own) / count)));
			}
			List<Map.Entry<String, Long>> spots = d.thingSpots.topWithin(thing, 3);
			Where busiest = null;
			if (!spots.isEmpty()) {
				details.add(t("packetdoctor.find.thing.where"));
				for (Map.Entry<String, Long> s : spots) {
					Where w = d.place(s.getKey());
					if (w == null) continue;
					if (busiest == null) busiest = w;
					details.add("  " + share(s.getValue(), own) + "  " + placeLabel(w));
				}
			}
			String ns = namespace(thing);
			if (!ns.equals("minecraft")) details.add(t("packetdoctor.find.thing.from_mod", label(ns)));
			List<String> actions = new ArrayList<>(List.of(advice(thing)));
			String title = t("packetdoctor.find.thing.title", typeOf(thing), kindWord(thing), count > 0 ? PacketNames.count(count) : "?", pct(pct), ms(d, own));
			out.add(new Finding(Type.THING, thing, title, pct, msOf(d, own), details, actions, busiest));
		}
		return out;
	}

	public static List<Finding> work(ProfileData d, double minPercent, int max) {
		List<Finding> out = new ArrayList<>();
		for (Map.Entry<String, Long> e : d.kinds.top(30)) {
			if (out.size() >= max) break;
			LagKind kind = kind(e.getKey());
			if (kind == null || kind == LagKind.MOD) continue;
			double pct = d.percent(e.getValue());
			if (pct < minPercent) break;
			if (kind == LagKind.OTHER && pct < 25) continue;
			long own = e.getValue();
			List<String> details = new ArrayList<>();
			List<String> actions = new ArrayList<>();
			List<Map.Entry<String, Long>> things = d.kindThings.topWithin(e.getKey(), 4);
			if (!things.isEmpty()) {
				details.add(t("packetdoctor.find.work.things"));
				for (Map.Entry<String, Long> th : things) details.add("  " + share(th.getValue(), own) + "  " + typeOf(th.getKey()) + " (" + kindWord(th.getKey()) + ")");
			}
			List<Map.Entry<String, Long>> spots = d.kindSpots.topWithin(e.getKey(), 3);
			Where busiest = null;
			if (!spots.isEmpty()) {
				details.add(t("packetdoctor.find.work.where"));
				for (Map.Entry<String, Long> s : spots) {
					Where w = d.place(s.getKey());
					if (w == null) continue;
					if (busiest == null) busiest = w;
					details.add("  " + share(s.getValue(), own) + "  " + placeLabel(w));
				}
			}
			if (kind == LagKind.WORLDGEN || kind == LagKind.CHUNK_LOADING) {
				List<String> explorers = PlayerInsight.explorers(5, 3);
				if (!explorers.isEmpty()) details.add(t("packetdoctor.find.work.explorers", String.join(", ", explorers)));
			}
			actions.add(kind.advice(""));
			if (!things.isEmpty()) actions.add(advice(things.getFirst().getKey()));
			String title = t("packetdoctor.find.work.title", kind.label(), pct(pct), ms(d, own));
			out.add(new Finding(Type.WORK, kind.name(), title, pct, msOf(d, own), details, actions, busiest));
		}
		return out;
	}

	/** A one-line answer to "what is this mod doing?", for chat alerts. */
	public static String modShort(ProfileData d, String mod) {
		List<Map.Entry<String, Long>> methods = d.modMethods.topWithin(mod, 1);
		List<Map.Entry<String, Long>> vias = d.modVias.topWithin(mod, 1);
		List<Map.Entry<String, Long>> things = d.modThings.topWithin(mod, 1);
		String what = methods.isEmpty() ? "?" : methods.getFirst().getKey();
		String how = vias.isEmpty() ? "" : vias.getFirst().getKey();
		if (!things.isEmpty()) how = how.isEmpty() ? typeOf(things.getFirst().getKey()) : how + ", " + typeOf(things.getFirst().getKey());
		return how.isEmpty() ? t("packetdoctor.find.mod.short", what) : t("packetdoctor.find.mod.short_how", what, how);
	}

	// --- Live details ------------------------------------------------------------------

	/** What is in a chunk right now: "120 hopper, 40 villager" (server thread). */
	private static @Nullable String liveContents(MinecraftServer server, Where w) {
		try {
			ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(w.dim())));
			if (level == null) return null;
			LevelChunk chunk = level.getChunkSource().getChunkNow(w.chunkX(), w.chunkZ());
			if (chunk == null) return null;
			Map<String, Integer> counts = new HashMap<>();
			for (BlockEntity be : chunk.getBlockEntities().values()) counts.merge(be.typeHolder().getRegisteredName(), 1, Integer::sum);
			int minX = w.chunkX() * 16;
			int minZ = w.chunkZ() * 16;
			AABB box = new AABB(minX, level.getMinY(), minZ, minX + 16, level.getMaxY(), minZ + 16);
			for (Entity e : level.getEntities((Entity) null, box, e -> true)) counts.merge(EntityType.getKey(e.getType()).toString(), 1, Integer::sum);
			if (counts.isEmpty()) return null;
			StringBuilder sb = new StringBuilder();
			counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(6).forEach(e -> {
				if (!sb.isEmpty()) sb.append(", ");
				sb.append(e.getValue()).append(' ').append(e.getKey().replace("minecraft:", ""));
			});
			return sb.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	// --- The report file -----------------------------------------------------------------

	public static Path folder() {
		return Reports.folder().resolve("lag");
	}

	/**
	 * Writes a full report and returns its path.
	 *
	 * @param what       what it covers, e.g. "the last 5 minutes"
	 * @param fileLabel  a word for the file name ("report", "spike", "overload")
	 * @param extra      a second profile to add as its own section (a spike report adds the last minutes), or null
	 */
	public static Path write(MinecraftServer server, ProfileData d, String what, String fileLabel, @Nullable ProfileData extra,
							 @Nullable String extraWhat, int keep) throws IOException {
		LagMonitor m = LagMonitor.get();
		LagMonitor.Census census = m == null ? null : m.census();
		StringBuilder sb = new StringBuilder();
		long now = System.currentTimeMillis();
		sb.append("==== Packet Doctor lag report ====\n");
		sb.append("Time: ").append(HUMAN_TIME.format(Instant.ofEpochMilli(now))).append('\n');
		sb.append("Packet Doctor ").append(PacketDoctor.version()).append(", Minecraft ").append(server.getServerVersion())
				.append(", ").append(FabricLoader.getInstance().getAllMods().size()).append(" mods loaded\n");
		sb.append("Covers: ").append(what).append("\n");
		if (m != null) {
			var p = m.performance();
			sb.append(String.format(Locale.ROOT, "TPS: %.1f now, %.1f last minute, %.1f last 5 minutes (target %.0f)%n", p.tps10s(), p.tps1m(), p.tps5m(), p.targetTps()));
		}
		if (census != null) {
			sb.append(String.format(Locale.ROOT, "Loaded: %d chunks, %d entities, %d ticking block entities, %d players online%n",
					census.loadedChunks(), census.entities(), census.tickingBlockEntities(), server.getPlayerCount()));
		}
		sb.append('\n');
		body(sb, server, d, census);
		if (extra != null && !extra.isEmpty()) {
			sb.append("\n\n######## ").append(extraWhat).append(" ########\n\n");
			body(sb, server, extra, census);
		}
		players(sb, d);
		if (census != null) {
			sb.append("\n== Loaded right now (most common) ==\n");
			census.things().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(30)
					.forEach(e -> sb.append(String.format(Locale.ROOT, "  %7d  %s (%s)%n", e.getValue(), typeOf(e.getKey()), kindWord(e.getKey()))));
		}
		sb.append("\n== How to read this ==\n").append(t("packetdoctor.report.lag.howto")).append('\n');

		Files.createDirectories(folder());
		Path file = folder().resolve("lag-" + FILE_TIME.format(Instant.ofEpochMilli(now)) + "-" + fileLabel + ".txt");
		Files.writeString(file, sb);
		prune(keep);
		return file;
	}

	private static void body(StringBuilder sb, MinecraftServer server, ProfileData d, LagMonitor.@Nullable Census census) {
		if (d.isEmpty()) {
			sb.append(t("packetdoctor.report.lag.empty")).append('\n');
			return;
		}
		if (d.perTick) {
			sb.append(String.format(Locale.ROOT, "Samples: %d, standing for %.1f s of server work; %.1f ms per tick on average over %d ticks.%n%n",
					d.samples, d.totalMs / 1000.0, d.msPerTick(), d.ticks));
		} else {
			sb.append(String.format(Locale.ROOT, "Samples: %d over %.1f s of slow ticks (%d tick(s)).%n%n", d.samples, d.tickNanos / 1e9, d.ticks));
		}

		sb.append("== Biggest causes, and what to do ==\n");
		List<Finding> all = findings(d, census, server);
		if (all.isEmpty()) sb.append("  ").append(t("packetdoctor.cmd.lag.top.none")).append('\n');
		int n = 1;
		for (Finding f : all) {
			sb.append('\n').append(n++).append(". ").append(f.title()).append('\n');
			for (String line : f.details()) sb.append("     ").append(line).append('\n');
			if (!f.actions().isEmpty()) {
				sb.append("   What to do:\n");
				for (String a : f.actions()) sb.append("     - ").append(a).append('\n');
			}
			if (f.where() != null) sb.append("   Go there: ").append(f.where().teleport()).append('\n');
		}

		sb.append("\n== Time by mod ==\n");
		for (Map.Entry<String, Long> e : d.mods.top(40)) {
			if (e.getKey().equals(PacketDoctor.MOD_ID) || ModBlame.isPlatform(e.getKey())) continue;
			if (d.percent(e.getValue()) < 0.3) break;
			sb.append(String.format(Locale.ROOT, "%n  %5s  %s  %s%n", pct(d.percent(e.getValue())), ms(d, e.getValue()), label(e.getKey())));
			for (Map.Entry<String, Long> mm : d.modMethods.topWithin(e.getKey(), 12)) {
				sb.append("         ").append(share(mm.getValue(), e.getValue())).append("  ").append(mm.getKey()).append('\n');
			}
			for (Map.Entry<String, Long> v : d.modVias.topWithin(e.getKey(), 5)) {
				sb.append("         run by: ").append(share(v.getValue(), e.getValue())).append("  ").append(v.getKey()).append('\n');
			}
			for (Map.Entry<String, Long> th : d.modThings.topWithin(e.getKey(), 5)) {
				sb.append("         for:    ").append(share(th.getValue(), e.getValue())).append("  ").append(thingLabel(th.getKey(), census)).append('\n');
			}
		}

		sb.append("\n== Time by kind of work ==\n");
		for (Map.Entry<String, Long> e : d.kinds.top(30)) {
			LagKind kind = kind(e.getKey());
			if (kind == null) continue;
			sb.append(String.format(Locale.ROOT, "  %5s  %s  %s%n", pct(d.percent(e.getValue())), ms(d, e.getValue()), kind.label()));
		}

		sb.append("\n== Block entities and entities by type ==\n");
		for (Map.Entry<String, Long> e : d.things.top(30)) {
			int count = census == null ? 0 : census.count(e.getKey());
			sb.append(String.format(Locale.ROOT, "  %5s  %s  %s (%s), %s loaded%n", pct(d.percent(e.getValue())), ms(d, e.getValue()), typeOf(e.getKey()),
					kindWord(e.getKey()), count > 0 ? count : "?"));
		}

		sb.append("\n== Busiest places (chunks) ==\n");
		for (Map.Entry<String, Long> e : d.spots.top(15)) {
			Where w = d.place(e.getKey());
			if (w == null) continue;
			List<Map.Entry<String, Long>> top = d.spotThings.topWithin(e.getKey(), 3);
			StringBuilder what = new StringBuilder();
			for (Map.Entry<String, Long> th : top) {
				if (!what.isEmpty()) what.append(", ");
				what.append(typeOf(th.getKey()));
			}
			sb.append(String.format(Locale.ROOT, "  %5s  %s  %s: %s%n          %s%n", pct(d.percent(e.getValue())), ms(d, e.getValue()), placeLabel(w), what,
					w.teleport()));
		}

		sb.append("\n== Busiest single block entities and entities ==\n");
		for (Map.Entry<String, Long> e : d.singles.top(15)) {
			Where w = d.place(e.getKey());
			if (w == null) continue;
			sb.append(String.format(Locale.ROOT, "  %5s  %s  %s at %s%s%n", pct(d.percent(e.getValue())), ms(d, e.getValue()), w.type(), w.place(),
					w.player() != null ? ", near " + w.player() : ""));
		}
	}

	private static void players(StringBuilder sb, ProfileData d) {
		if (d.players.isEmpty()) return;
		sb.append("\n== Lag near players (what is ticking within ").append((int) PlayerSpots.NEAR_BLOCKS).append(" blocks of them) ==\n");
		for (Map.Entry<String, Long> e : d.players.top(20)) {
			StringBuilder what = new StringBuilder();
			for (Map.Entry<String, Long> th : d.playerThings.topWithin(e.getKey(), 3)) {
				if (!what.isEmpty()) what.append(", ");
				what.append(typeOf(th.getKey()));
			}
			sb.append(String.format(Locale.ROOT, "  %5s  %s  %s: %s%n", pct(d.percent(e.getValue())), ms(d, e.getValue()), e.getKey(), what));
		}
	}

	private static void prune(int keep) {
		try (Stream<Path> list = Files.list(folder())) {
			List<Path> files = new ArrayList<>(list.filter(p -> p.getFileName().toString().endsWith(".txt")).toList());
			if (files.size() <= keep) return;
			files.sort(Comparator.comparing(p -> p.getFileName().toString()));
			for (int i = 0; i < files.size() - keep; i++) Files.deleteIfExists(files.get(i));
		} catch (IOException e) {
			PacketDoctor.LOGGER.debug("Couldn't clean up old lag reports", e);
		}
	}

	// --- Words -----------------------------------------------------------------------------

	/** What to do about one block entity or entity type. */
	public static String advice(String thingKey) {
		String type = typeOf(thingKey);
		String key = "packetdoctor.advice.thing." + type.replace(':', '.');
		if (Tr.has(key)) return t(key);
		String ns = namespace(thingKey);
		boolean entity = thingKey.startsWith("E ");
		if (!ns.equals("minecraft")) {
			return t(entity ? "packetdoctor.advice.thing.modded_entity" : "packetdoctor.advice.thing.modded_block_entity", type, label(ns));
		}
		return t(entity ? "packetdoctor.advice.thing.entity" : "packetdoctor.advice.thing.block_entity", type);
	}

	/** "minecraft:hopper" from "B minecraft:hopper". */
	public static String typeOf(String thingKey) {
		return thingKey.length() > 2 && thingKey.charAt(1) == ' ' ? thingKey.substring(2) : thingKey;
	}

	public static String kindWord(String thingKey) {
		return t(thingKey.startsWith("E ") ? "packetdoctor.find.word.entity" : "packetdoctor.find.word.block_entity");
	}

	private static String thingLabel(String thingKey, LagMonitor.@Nullable Census census) {
		int count = census == null ? 0 : census.count(thingKey);
		return typeOf(thingKey) + " (" + kindWord(thingKey) + (count > 0 ? ", " + count + " " + t("packetdoctor.find.word.loaded") : "") + ")";
	}

	public static String placeLabel(Where w) {
		String s = t("packetdoctor.find.place.label", w.dimName(), w.chunkX() * 16 + 8, w.chunkZ() * 16 + 8);
		return w.player() != null ? s + ", " + t("packetdoctor.find.place.near_short", w.player()) : s;
	}

	private static String namespace(String thingKey) {
		String type = typeOf(thingKey);
		int colon = type.indexOf(':');
		return colon > 0 ? type.substring(0, colon) : type;
	}

	public static String label(String modId) {
		String name = ModBlame.name(modId);
		return name.equals(modId) ? name : name + " (" + modId + ")";
	}

	private static @Nullable LagKind kind(String name) {
		try {
			return LagKind.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static String pct(double p) {
		return p >= 9.95 || p == 0 ? String.format(Locale.ROOT, "%.0f%%", p) : String.format(Locale.ROOT, "%.1f%%", p);
	}

	private static String share(long part, long whole) {
		return pct(whole == 0 ? 0 : part * 100.0 / whole);
	}

	private static double msOf(ProfileData d, long ms) {
		return d.percent(ms) / 100.0 * d.msPerTick();
	}

	public static String ms(ProfileData d, long ms) {
		if (!d.perTick) return String.format(Locale.ROOT, "%.0f ms", d.percent(ms) / 100.0 * d.tickNanos / 1e6);
		double v = msOf(d, ms);
		return v >= 10 ? String.format(Locale.ROOT, "%.0f ms/tick", v) : String.format(Locale.ROOT, "%.1f ms/tick", v);
	}

	/** The fixed start of a translated "via" text, to recognise it again. */
	private static String prefixOf(String key) {
		String pattern = Tr.t(key, "\u0000");
		int at = pattern.indexOf('\u0000');
		return at >= 0 ? pattern.substring(0, at) : pattern;
	}

	private static String afterPrefix(String text, String key) {
		String prefix = prefixOf(key);
		String pattern = Tr.t(key, "\u0000");
		int at = pattern.indexOf('\u0000');
		String suffix = at >= 0 ? pattern.substring(at + 1) : "";
		String s = text.startsWith(prefix) ? text.substring(prefix.length()) : text;
		return !suffix.isEmpty() && s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
	}
}
