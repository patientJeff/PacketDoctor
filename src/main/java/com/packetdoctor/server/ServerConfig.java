package com.packetdoctor.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.packetdoctor.PacketDoctor;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Server settings, stored as {@code config/packetdoctor-server.json}. Also readable and
 * writable by other mods through {@link com.packetdoctor.api.PacketDoctorApi#settings()}.
 */
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Tell players' games (with Packet Doctor) exactly why they were disconnected. */
	public boolean sendExplanations = true;
	/** Record every player's packets and run the checks. Off = only disconnects are tracked. */
	public boolean monitorPackets = true;
	/** Chat alerts to online operators for serious warnings (dedicated servers only). */
	public boolean alertOps = true;
	/** The least serious warning that alerts operators: INFO, WARNING or DANGER. */
	public String alertMinSeverity = "DANGER";
	/** Seconds before the same warning about the same player alerts again. */
	public int alertCooldownSeconds = 60;
	/** Also write alerts to the server console/log. */
	public boolean logWarnings = true;
	/** Packets remembered per player, for exports. */
	public int playerLogSize = 2000;
	/** Disconnects remembered for the command and the API. */
	public int keepDisconnects = 100;
	/** Saved report files to keep. */
	public int keepReports = 30;

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("packetdoctor-server.json");
	}

	public static ServerConfig load() {
		Path path = path();
		ServerConfig config = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				config = GSON.fromJson(reader, ServerConfig.class);
			} catch (IOException | RuntimeException e) {
				PacketDoctor.LOGGER.warn("Couldn't read {}, using defaults", path, e);
			}
		}
		if (config == null) config = new ServerConfig();
		config.clamp();
		config.save();
		return config;
	}

	private void clamp() {
		alertCooldownSeconds = Math.clamp(alertCooldownSeconds, 0, 86400);
		playerLogSize = Math.clamp(playerLogSize, 100, 100_000);
		keepDisconnects = Math.clamp(keepDisconnects, 10, 10_000);
		keepReports = Math.clamp(keepReports, 1, 1000);
		try {
			alertMinSeverity = com.packetdoctor.net.Severity.valueOf(alertMinSeverity.toUpperCase(Locale.ROOT)).name();
		} catch (RuntimeException e) {
			alertMinSeverity = "DANGER";
		}
	}

	public synchronized void save() {
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

	/** Every setting by name, in file order. */
	public synchronized Map<String, Object> asMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("sendExplanations", sendExplanations);
		m.put("monitorPackets", monitorPackets);
		m.put("alertOps", alertOps);
		m.put("alertMinSeverity", alertMinSeverity);
		m.put("alertCooldownSeconds", alertCooldownSeconds);
		m.put("logWarnings", logWarnings);
		m.put("playerLogSize", playerLogSize);
		m.put("keepDisconnects", keepDisconnects);
		m.put("keepReports", keepReports);
		return m;
	}

	/**
	 * Changes one setting and saves. Returns false for an unknown name or a value of the
	 * wrong type. Numbers are clamped to their allowed range.
	 */
	public synchronized boolean set(String name, Object value) {
		try {
			switch (name) {
				case "sendExplanations" -> sendExplanations = (Boolean) value;
				case "monitorPackets" -> monitorPackets = (Boolean) value;
				case "alertOps" -> alertOps = (Boolean) value;
				case "alertMinSeverity" -> alertMinSeverity = com.packetdoctor.net.Severity.valueOf(
						String.valueOf(value).toUpperCase(Locale.ROOT)).name();
				case "alertCooldownSeconds" -> alertCooldownSeconds = ((Number) value).intValue();
				case "logWarnings" -> logWarnings = (Boolean) value;
				case "playerLogSize" -> playerLogSize = ((Number) value).intValue();
				case "keepDisconnects" -> keepDisconnects = ((Number) value).intValue();
				case "keepReports" -> keepReports = ((Number) value).intValue();
				default -> {
					return false;
				}
			}
		} catch (ClassCastException | IllegalArgumentException | NullPointerException e) {
			return false;
		}
		clamp();
		save();
		return true;
	}
}
