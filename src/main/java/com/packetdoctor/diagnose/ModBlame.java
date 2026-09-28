package com.packetdoctor.diagnose;

import com.packetdoctor.PacketDoctor;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Works out which mod a stack trace points at.
 *
 * <p>Two signals are used for each frame:
 * <ol>
 *   <li>Mixin handler names. Fabric's Mixin names injected methods
 *       {@code handler$zza000$modid$name}, so code a mod injected into a Minecraft
 *       class still names that mod.</li>
 *   <li>Where the class file lives. Each mod's jar (including nested jars) is checked
 *       for the class, and the result is cached.</li>
 * </ol>
 * Minecraft, Java, Fabric Loader and Fabric API are "platform": they are on nearly every
 * stack, so they are only blamed when nothing else is found.
 */
public final class ModBlame {
	/** One mod found on a stack, with how many frames belonged to it. */
	public record Culprit(String id, String name, int frames, boolean platform) {
		public String label() {
			return name.equals(id) ? name : name + " (" + id + ")";
		}
	}

	private static final Set<String> PLATFORM = Set.of("minecraft", "java", "fabricloader", "mixinextras", "fabric-api", "fabric-api-base");
	private static final Map<String, Optional<String>> CLASS_CACHE = new ConcurrentHashMap<>();
	private static volatile @Nullable List<ModContainer> searchOrder;

	private ModBlame() {
	}

	public static boolean isPlatform(String id) {
		return PLATFORM.contains(id) || id.startsWith("fabric-");
	}

	/** Mods on the whole cause chain of {@code t}, deepest cause first, platform mods last. */
	public static List<Culprit> blame(Throwable t, boolean ignoreSelf) {
		List<StackTraceElement> frames = new ArrayList<>();
		for (Throwable c : causes(t)) frames.addAll(List.of(c.getStackTrace()));
		return blame(frames.toArray(StackTraceElement[]::new), ignoreSelf);
	}

	/** Mods on a stack, in the order they first appear, platform mods last. */
	public static List<Culprit> blame(StackTraceElement[] frames, boolean ignoreSelf) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (StackTraceElement frame : frames) {
			String mod = modForFrame(frame);
			if (mod == null) continue;
			if (ignoreSelf && mod.equals(PacketDoctor.MOD_ID)) continue;
			counts.merge(mod, 1, Integer::sum);
		}
		List<Culprit> mods = new ArrayList<>();
		List<Culprit> platform = new ArrayList<>();
		counts.forEach((id, n) -> {
			boolean p = isPlatform(id);
			(p ? platform : mods).add(new Culprit(id, name(id), n, p));
		});
		mods.addAll(platform);
		return mods;
	}

	/** The first non-platform mod, if any. */
	public static @Nullable Culprit prime(List<Culprit> culprits) {
		for (Culprit c : culprits) {
			if (!c.platform()) return c;
		}
		return null;
	}

	/** The cause chain, deepest (root) cause first. Guards against cycles. */
	public static List<Throwable> causes(Throwable t) {
		List<Throwable> chain = new ArrayList<>();
		Map<Throwable, Boolean> seen = new IdentityHashMap<>();
		for (Throwable c = t; c != null && seen.put(c, Boolean.TRUE) == null; c = c.getCause()) {
			chain.addFirst(c);
		}
		return chain;
	}

	public static String name(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(c -> c.getMetadata().getName())
				.orElse(modId);
	}

	/** The mod whose namespace matches, e.g. {@code sodium} for {@code sodium:config_sync}. */
	public static @Nullable String modForNamespace(String namespace) {
		return FabricLoader.getInstance().isModLoaded(namespace) ? namespace : null;
	}

	public static @Nullable String modForFrame(StackTraceElement frame) {
		String method = frame.getMethodName();
		if (method.indexOf('$') >= 0) {
			String[] parts = method.split("\\$");
			// handler$zza000$modid$name: the mod id is the third part.
			if (parts.length >= 4 && isKnownMod(parts[2])) return parts[2];
			for (int i = 1; i < parts.length - 1; i++) {
				if (isKnownMod(parts[i]) && !parts[i].equals("minecraft")) return parts[i];
			}
		}
		return modForClass(frame.getClassName());
	}

	private static boolean isKnownMod(String s) {
		return !s.isEmpty() && FabricLoader.getInstance().isModLoaded(s);
	}

	public static @Nullable String modForClass(String className) {
		if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.")
				|| className.startsWith("sun.") || className.startsWith("com.sun.")) {
			return "java";
		}
		if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.blaze3d.")) return "minecraft";
		if (className.startsWith("net.fabricmc.loader.") || className.startsWith("org.spongepowered.")
				|| className.startsWith("com.llamalad7.")) {
			return "fabricloader";
		}
		if (className.startsWith("com.packetdoctor.")) return PacketDoctor.MOD_ID;

		String outer = className;
		int lambda = outer.indexOf("$$Lambda");
		if (lambda >= 0) outer = outer.substring(0, lambda);
		int hidden = outer.indexOf('/');
		if (hidden >= 0) outer = outer.substring(0, hidden);
		String key = outer;
		return CLASS_CACHE.computeIfAbsent(key, ModBlame::lookup).orElse(null);
	}

	private static Optional<String> lookup(String className) {
		String resource = className.replace('.', '/') + ".class";
		for (ModContainer mod : searchOrder()) {
			try {
				for (Path root : mod.getRootPaths()) {
					if (Files.exists(root.resolve(resource))) return Optional.of(mod.getMetadata().getId());
				}
			} catch (RuntimeException ignored) {
				// A broken file system for one mod shouldn't stop the search.
			}
		}
		return Optional.empty(); // a library (Netty, LWJGL, Gson...) or a generated class
	}

	/** Every mod except the built-ins, real mods before Fabric API modules. */
	private static List<ModContainer> searchOrder() {
		List<ModContainer> order = searchOrder;
		if (order != null) return order;
		List<ModContainer> mods = new ArrayList<>();
		List<ModContainer> platform = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (id.equals("minecraft") || id.equals("java") || id.equals("fabricloader")) continue;
			(isPlatform(id) ? platform : mods).add(mod);
		}
		mods.addAll(platform);
		searchOrder = order = List.copyOf(mods);
		return order;
	}

	/**
	 * Installed mods that change what the game sends (movement, clicks, block placement)
	 * in ways anti-cheat plugins commonly react to. Purely visual mods are left out.
	 */
	public static List<String> installedRiskyMods() {
		List<String> out = new ArrayList<>();
		for (String id : List.of("freecam", "baritone", "meteor-client", "wurst", "aristois", "inertia", "tweakeroo",
				"litematica", "itemscroller", "inventoryprofilesnext", "mousewheelie", "accurateblockplacement",
				"clickcrystals", "autoclicker")) {
			if (FabricLoader.getInstance().isModLoaded(id)) out.add(name(id));
		}
		return out;
	}
}
