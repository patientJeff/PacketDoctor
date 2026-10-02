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
	/** Time every tick and find out what slow ticks are spent on. */
	public boolean lagMonitor = true;
	/** A tick at least this slow (milliseconds) counts as a lag spike. 50 ms is a full tick at 20 TPS. */
	public int lagSpikeMs = 300;
	/** How often a slow tick is sampled, in milliseconds. Lower is more precise and costs a little more. */
	public int lagSampleIntervalMs = 10;
	/** Lag spikes at least this long are written to the console (0 = never). */
	public int logLagSpikeMs = 1000;
	/** Lag spikes at least this long alert online operators in chat (0 = never). */
	public int alertLagSpikeMs = 2000;
	/** Read the server console to explain errors, crashes and lag (dedicated servers). */
	public boolean captureConsole = true;
	/** Console lines remembered for the command and the API. */
	public int consoleLines = 1000;
	/** Tell players whose game has Packet Doctor how the server is running (TPS and what slows it), so they can see whether lag is the server or their side. */
	public boolean sharePerformance = true;

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
		lagSpikeMs = Math.clamp(lagSpikeMs, 60, 60_000);
		lagSampleIntervalMs = Math.clamp(lagSampleIntervalMs, 2, 200);
		logLagSpikeMs = Math.clamp(logLagSpikeMs, 0, 600_000);
		alertLagSpikeMs = Math.clamp(alertLagSpikeMs, 0, 600_000);
		consoleLines = Math.clamp(consoleLines, 100, 50_000);
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
		m.put("lagMonitor", lagMonitor);
		m.put("lagSpikeMs", lagSpikeMs);
		m.put("lagSampleIntervalMs", lagSampleIntervalMs);
		m.put("logLagSpikeMs", logLagSpikeMs);
		m.put("alertLagSpikeMs", alertLagSpikeMs);
		m.put("captureConsole", captureConsole);
		m.put("consoleLines", consoleLines);
		m.put("sharePerformance", sharePerformance);
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
				case "lagMonitor" -> lagMonitor = (Boolean) value;
				case "lagSpikeMs" -> lagSpikeMs = ((Number) value).intValue();
				case "lagSampleIntervalMs" -> lagSampleIntervalMs = ((Number) value).intValue();
				case "logLagSpikeMs" -> logLagSpikeMs = ((Number) value).intValue();
				case "alertLagSpikeMs" -> alertLagSpikeMs = ((Number) value).intValue();
				case "captureConsole" -> captureConsole = (Boolean) value;
				case "consoleLines" -> consoleLines = ((Number) value).intValue();
				case "sharePerformance" -> sharePerformance = (Boolean) value;
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
