package com.packetdoctor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.locale.Language;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;

/**
 * Translated text for explanations and warnings, which are built as plain strings
 * (they are saved to disk and copied to the clipboard, not just drawn on screen).
 *
 * <p>Text comes from the game's current language. A dedicated server only has English,
 * and very early in startup (a crash while loading) the game has no languages at all, so
 * anything the current language lacks comes from the mod's bundled English file. An
 * explanation never shows raw keys.
 *
 * <p>Arguments use {@code %s} / {@code %1$s}, as in Minecraft's own language files.
 */
public final class Tr {
	private static final String BUNDLED = "/assets/" + PacketDoctor.MOD_ID + "/lang/en_us.json";
	private static volatile @Nullable Map<String, String> english;

	private Tr() {
	}

	public static String t(String key, Object... args) {
		String pattern = lookup(key);
		if (args.length == 0) return pattern;
		try {
			return String.format(Locale.ROOT, pattern, args);
		} catch (IllegalFormatException e) {
			return pattern; // a translation with broken placeholders shouldn't break the explanation
		}
	}

	public static boolean has(String key) {
		try {
			if (Language.getInstance().has(key)) return true;
		} catch (RuntimeException ignored) {
		}
		return english().containsKey(key);
	}

	private static String lookup(String key) {
		try {
			Language language = Language.getInstance();
			if (language.has(key)) return language.getOrDefault(key);
		} catch (RuntimeException ignored) {
			// No language available (very early crash): fall through to the bundled file.
		}
		return english().getOrDefault(key, key);
	}

	private static Map<String, String> english() {
		Map<String, String> map = english;
		if (map != null) return map;
		map = new HashMap<>();
		try (InputStream in = Tr.class.getResourceAsStream(BUNDLED)) {
			if (in != null) {
				JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				for (Map.Entry<String, JsonElement> e : json.entrySet()) map.put(e.getKey(), e.getValue().getAsString());
			}
		} catch (Exception e) {
			PacketDoctor.LOGGER.warn("Couldn't read the bundled English text", e);
		}
		english = map;
		return map;
	}
}
