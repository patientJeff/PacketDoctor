package com.packetdoctor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Player settings, stored as {@code config/packetdoctor.json} (the server's are in
 * {@link com.packetdoctor.server.ServerConfig}). Every field has a safe
 * default, so a missing or broken file just falls back to defaults and is rewritten.
 */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Pop-up toasts for new warnings while playing. */
	public boolean toasts = true;
	/** Also pop up informational notes (unknown mod channels, transfers...), not just problems. */
	public boolean toastInfo = false;
	/** Seconds before the same kind of warning may pop up again. */
	public int toastCooldownSeconds = 30;
	/** The "Packet Doctor" button in the top-left of the title screen. */
	public boolean titleScreenButton = true;
	/** After a crash, open the explanation automatically the next time the title screen shows. */
	public boolean openCrashExplanation = true;
	/** How many packets are remembered, for the Live view and exports (50,000 is a few minutes of play). */
	public int logSize = 50_000;
	/** How many saved report files to keep in {@code .minecraft/packetdoctor/reports}. */
	public int keepReports = 30;

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("packetdoctor.json");
	}

	public static Config load() {
		Path path = path();
		Config config = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				config = GSON.fromJson(reader, Config.class);
			} catch (IOException | RuntimeException e) {
				PacketDoctor.LOGGER.warn("Couldn't read {}, using defaults", path, e);
			}
		}
		if (config == null) config = new Config();
		config.clamp();
		config.save();
		return config;
	}

	private void clamp() {
		toastCooldownSeconds = Math.clamp(toastCooldownSeconds, 0, 3600);
		logSize = Math.clamp(logSize, 1000, 500_000);
		keepReports = Math.clamp(keepReports, 1, 1000);
	}

	public void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			PacketDoctor.LOGGER.warn("Couldn't save {}", path, e);
		}
	}
}
