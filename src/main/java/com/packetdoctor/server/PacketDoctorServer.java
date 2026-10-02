package com.packetdoctor.server;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.api.ConsoleProblem;
import com.packetdoctor.api.CrashInfo;
import com.packetdoctor.api.DisconnectCause;
import com.packetdoctor.api.DisconnectInfo;
import com.packetdoctor.api.LagCause;
import com.packetdoctor.api.LagSpikeInfo;
import com.packetdoctor.api.PacketDoctorEvents;
import com.packetdoctor.api.PerformanceInfo;
import com.packetdoctor.api.WarningInfo;
import com.packetdoctor.diagnose.CrashHandler;
import com.packetdoctor.diagnose.Diagnosis;
import com.packetdoctor.diagnose.PreviousRun;
import com.packetdoctor.diagnose.Reports;
import com.packetdoctor.net.ServerMonitor;
import com.packetdoctor.net.Severity;
import com.packetdoctor.net.Warning;
import com.packetdoctor.network.ServerStatus;
import com.packetdoctor.network.StatusPayload;
import com.packetdoctor.server.console.ConsoleCapture;
import com.packetdoctor.server.perf.LagAnalysis;
import com.packetdoctor.server.perf.LagMonitor;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.packetdoctor.Tr.t;

/**
 * The server side: settings, the {@link ServerMonitor}, the lag monitor and console reader,
 * the {@code /packetdoctor} command, alerts for operators, crash explanations (including
 * ones worked out from the logs on the next start), and the events the API exposes.
 * Player monitoring also runs inside singleplayer worlds; the console and lag features
 * and operator alerts are for dedicated servers.
 */
public final class PacketDoctorServer {
	private static ServerConfig config;
	private static volatile @Nullable MinecraftServer server;
	private static volatile @Nullable CrashInfo lastCrash;
	private static PreviousRun.@Nullable State previousRun;
	private static final Map<String, Long> consoleAlerts = new ConcurrentHashMap<>();
	private static volatile boolean started;
	private static final java.util.Queue<ConsoleProblem> pendingProblems = new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static volatile boolean crashed;

	private PacketDoctorServer() {
	}

	public static void init() {
		config = ServerConfig.load();
		ServerMonitor.init(() -> config, new ServerMonitor.Listener() {
			@Override
			public void onWarning(ServerMonitor.PlayerSession session, Warning warning) {
				warned(session, warning);
			}

			@Override
			public void onDisconnect(DisconnectInfo info) {
				disconnected(info);
			}
		});
		LagMonitor.init(() -> config, new LagMonitor.Listener() {
			@Override
			public void onSpike(LagSpikeInfo spike) {
				lagSpike(spike);
			}

			@Override
			public void onOverloaded(PerformanceInfo info) {
				overloaded(info, true);
			}

			@Override
			public void onRecovered(PerformanceInfo info) {
				overloaded(info, false);
			}
		});
		// The run marker starts here, before worlds and data packs load, so a server that never
		// finishes starting is noticed next time too.
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) previousRun = PreviousRun.begin();
		// As early as possible, so errors while the server loads are read too.
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER && config.captureConsole) {
			ConsoleCapture.install(config.consoleLines, PacketDoctorServer::consoleProblem);
			ConsoleCapture capture = ConsoleCapture.get();
			if (capture != null) {
				capture.setLagListener((ms, ticks) -> {
					LagMonitor m = LagMonitor.get();
					if (m != null) m.onCantKeepUp(ms, ticks);
				});
				capture.setStartupListener(PacketDoctorServer::startupFailure);
			}
		}

		ServerLifecycleEvents.SERVER_STARTING.register(s -> {
			server = s;
			ServerMonitor monitor = ServerMonitor.get();
			if (monitor != null) monitor.reset();
			if (s.isDedicatedServer()) {
				CrashHandler.keepReports = config.keepReports;
				LagMonitor lag = LagMonitor.get();
				if (lag != null) lag.start();
			}
		});
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			if (s.isDedicatedServer()) {
				started = true;
				reportPreviousRun();
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			if (s.isDedicatedServer()) {
				LagMonitor lag = LagMonitor.get();
				if (lag != null) lag.stop();
				// Only a server that started and didn't crash stopped "normally".
				if (started && !crashed) PreviousRun.markStopped();
			}
			server = null;
		});
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			ServerMonitor monitor = ServerMonitor.get();
			if (monitor != null) monitor.tick();
			if (s.getTickCount() % 40 == 0) shareStatus(s);
			dispatchConsoleProblems(s);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PacketDoctorCommand.register(dispatcher));
		CrashHandler.serverCrashListener = d -> {
			CrashInfo info = crashInfo(d);
			lastCrash = info;
			crashed = true;
			ServerMonitor monitor = ServerMonitor.get();
			if (monitor != null) monitor.onServerCrash(info.kind(), d.headline(), d.source() != null ? d.source() : d.sourceKind().label());
			PacketDoctorEvents.SERVER_CRASH.invoker().onServerCrash(info);
		};
	}

	public static ServerConfig config() {
		return config;
	}

	public static @Nullable MinecraftServer server() {
		return server;
	}

	public static @Nullable CrashInfo lastCrash() {
		return lastCrash;
	}

	/**
	 * On start-up, explain in the console how the last run ended badly: from the crash
	 * explanation saved at the time, or else from the logs and files it left behind.
	 */
	private static void reportPreviousRun() {
		LagMonitor.StuckTick stuck = LagMonitor.takePreviousStuck();
		Diagnosis d = Reports.loadPendingCrash(Reports.SERVER_CRASH);
		if (d != null) {
			Reports.clearPendingCrash(Reports.SERVER_CRASH);
		} else if (previousRun != null && !previousRun.cleanStop()) {
			try {
				d = PreviousRun.explain(previousRun, stuck);
				if (d != null) d = Reports.save(d, config.keepReports);
			} catch (RuntimeException e) {
				PacketDoctor.LOGGER.warn("Couldn't work out how the last run ended", e);
			}
		}
		if (d == null) return;
		lastCrash = crashInfo(d);
		PacketDoctor.LOGGER.warn("The server didn't stop normally last time: {}", d.headline());
		PacketDoctor.LOGGER.warn("  {}", d.summary());
		PacketDoctor.LOGGER.warn("  {}", d.sourceLabel());
		for (int i = 0; i < d.tips().size(); i++) PacketDoctor.LOGGER.warn("  {}. {}", i + 1, d.tips().get(i));
		if (d.reportFile() != null) PacketDoctor.LOGGER.warn("  Full report: {}", d.reportFile());
	}

	/**
	 * Minecraft said the server can't start. This arrives inside the logging system, so the
	 * explanation (which logs itself) is worked out on another thread; the server may exit
	 * right after, so it is waited for, briefly.
	 */
	private static void startupFailure(String message, @Nullable Throwable thrown) {
		if (started) return;
		Thread worker = new Thread(() -> CrashHandler.onServerStartupFailure(message, thrown), "Packet Doctor start-up failure");
		worker.setDaemon(true);
		worker.start();
		try {
			worker.join(5000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Every two seconds: tell players whose game has Packet Doctor how the server is running. */
	private static void shareStatus(MinecraftServer s) {
		LagMonitor lag = LagMonitor.get();
		if (!s.isDedicatedServer() || lag == null || !config.sharePerformance || !config.lagMonitor) return;
		List<ServerPlayer> players = s.getPlayerList().getPlayers();
		if (players.isEmpty()) return;
		StatusPayload payload = null;
		for (ServerPlayer p : players) {
			if (!ServerPlayNetworking.canSend(p, StatusPayload.TYPE)) continue;
			if (payload == null) {
				PerformanceInfo info = lag.performance();
				List<ServerStatus.Cause> causes = new ArrayList<>();
				for (LagCause c : info.causes()) {
					if (causes.size() >= 3) break;
					causes.add(new ServerStatus.Cause(c.label(), c.percent(), c.mod()));
				}
				payload = new StatusPayload(new ServerStatus(ServerStatus.CURRENT, info.tps10s(), info.tps1m(), info.targetTps(),
						info.msptAverage(), info.msptMax(), info.overloaded(), causes).toJson());
			}
			ServerPlayNetworking.send(p, payload);
		}
	}

	static CrashInfo crashInfo(Diagnosis d) {
		return new CrashInfo(d.time(), d.cause() != null ? d.cause() : "CRASH", d.headline(), d.summary(), d.sourceLabel(), d.tips(),
				d.warnings(), d.reportFile());
	}

	// --- Reacting to what the monitors find ----------------------------------------------

	private static void warned(ServerMonitor.PlayerSession session, Warning warning) {
		MinecraftServer s = server;
		if (s == null) return;
		WarningInfo info = ServerMonitor.info(warning);
		Severity min = Severity.valueOf(config.alertMinSeverity);
		boolean serious = warning.severity.atLeast(min);
		if (serious && config.logWarnings) {
			PacketDoctor.LOGGER.warn("[{}] {} - {}", session.name, info.title(), info.detail());
		}
		// Events and chat must run on the server thread; warnings are often found on network threads.
		s.execute(() -> {
			PacketDoctorEvents.PLAYER_WARNING.invoker().onWarning(session.uuid, session.name, info);
			if (serious && config.alertOps && s.isDedicatedServer()) {
				MutableComponent hover = Component.literal(info.detail()).withStyle(ChatFormatting.GRAY)
						.append(Component.literal("\n\n" + info.advice()).withStyle(ChatFormatting.WHITE))
						.append(Component.literal("\n\n" + t("packetdoctor.cmd.alert.click")).withStyle(ChatFormatting.YELLOW));
				alertOps(s, Component.literal(session.name + ": ").withStyle(ChatFormatting.WHITE)
						.append(Component.literal(info.title()).withColor(warning.severity.color & 0xFFFFFF)), hover,
						"/packetdoctor player " + session.name);
			}
		});
	}

	/** Lag spikes arrive on the server thread. */
	private static void lagSpike(LagSpikeInfo spike) {
		if (config.logLagSpikeMs > 0 && spike.durationMs() >= config.logLagSpikeMs) {
			PacketDoctor.LOGGER.warn("Lag spike: {}", spike.summary());
		}
		PacketDoctorEvents.LAG_SPIKE.invoker().onLagSpike(spike);
		MinecraftServer s = server;
		if (s != null && config.alertOps && config.alertLagSpikeMs > 0 && spike.durationMs() >= config.alertLagSpikeMs) {
			String top = spike.causes().isEmpty() ? "" : " - " + LagAnalysis.line(spike.causes().getFirst());
			alertOps(s, Component.literal(t("packetdoctor.cmd.alert.lag", LagAnalysis.seconds(spike.durationMs())) + top)
					.withStyle(ChatFormatting.YELLOW), Component.literal(spike.summary()), "/packetdoctor lag");
		}
	}

	private static void overloaded(PerformanceInfo info, boolean nowOverloaded) {
		String causes = info.causes().isEmpty() ? "" : " - " + LagAnalysis.line(info.causes().getFirst());
		if (nowOverloaded) {
			PacketDoctor.LOGGER.warn("The server is overloaded: {} TPS (of {}), {} ms per tick{}", info.tps10s(), info.targetTps(),
					info.msptAverage(), causes);
			PacketDoctorEvents.SERVER_OVERLOADED.invoker().onPerformanceChange(info);
			MinecraftServer s = server;
			if (s != null && config.alertOps) {
				alertOps(s, Component.literal(t("packetdoctor.cmd.alert.overloaded", info.tps10s()) + causes).withStyle(ChatFormatting.RED),
						Component.literal(t("packetdoctor.cmd.click_details")), "/packetdoctor lag");
			}
		} else {
			PacketDoctor.LOGGER.info("The server is keeping up again: {} TPS", info.tps10s());
			PacketDoctorEvents.SERVER_RECOVERED.invoker().onPerformanceChange(info);
		}
	}

	/**
	 * A new (or growing) console problem. It arrives from inside the logging system, on
	 * whichever thread logged; anything that logs from here (an event listener, say) would be
	 * dropped by Log4j, and the server thread would even run it straight away. So it only
	 * queues; {@link #dispatchConsoleProblems} handles it at the end of the tick.
	 */
	private static void consoleProblem(ConsoleProblem problem) {
		if (server == null) return;
		if (pendingProblems.size() < 1000) pendingProblems.add(problem);
	}

	private static void dispatchConsoleProblems(MinecraftServer s) {
		ConsoleProblem problem;
		while ((problem = pendingProblems.poll()) != null) {
			PacketDoctorEvents.CONSOLE_PROBLEM.invoker().onConsoleProblem(problem);
			if (!config.alertOps || !problem.severity().equals("DANGER") || problem.count() != 1) continue;
			// The same kind of problem alerts at most once per cooldown.
			String key = problem.id() + "|" + problem.mod();
			long now = System.currentTimeMillis();
			Long last = consoleAlerts.get(key);
			if (last != null && now - last < config.alertCooldownSeconds * 1000L) continue;
			consoleAlerts.put(key, now);
			MutableComponent hover = Component.literal(problem.explanation()).withStyle(ChatFormatting.GRAY)
					.append(Component.literal("\n\n" + problem.advice()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("\n\n" + problem.example()).withStyle(ChatFormatting.DARK_GRAY));
			alertOps(s, Component.literal(t("packetdoctor.cmd.alert.console", problem.title())).withColor(Severity.DANGER.color & 0xFFFFFF),
					hover, "/packetdoctor console");
		}
	}

	/** A chat line to every online operator, with details on hover and a command on click. */
	private static void alertOps(MinecraftServer s, Component text, Component hover, String command) {
		if (!s.isDedicatedServer()) return;
		Component message = Component.literal("[Packet Doctor] ").withStyle(ChatFormatting.GOLD).append(text)
				.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)).withClickEvent(new ClickEvent.RunCommand(command)));
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (s.getPlayerList().isOp(p.nameAndId())) p.sendSystemMessage(message);
		}
	}

	private static void disconnected(DisconnectInfo info) {
		MinecraftServer s = server;
		if (info.cause() != DisconnectCause.LEFT) PacketDoctor.LOGGER.info("{}: {}", info.name(), info.summary());
		if (s == null) return;
		s.execute(() -> PacketDoctorEvents.PLAYER_DISCONNECT.invoker().onDisconnect(info));
	}
}
