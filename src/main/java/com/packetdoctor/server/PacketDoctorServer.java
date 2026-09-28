package com.packetdoctor.server;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.api.CrashInfo;
import com.packetdoctor.api.DisconnectCause;
import com.packetdoctor.api.DisconnectInfo;
import com.packetdoctor.api.PacketDoctorEvents;
import com.packetdoctor.api.WarningInfo;
import com.packetdoctor.diagnose.CrashHandler;
import com.packetdoctor.diagnose.Diagnosis;
import com.packetdoctor.diagnose.Reports;
import com.packetdoctor.net.ServerMonitor;
import com.packetdoctor.net.Severity;
import com.packetdoctor.net.Warning;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import static com.packetdoctor.Tr.t;

/**
 * The server side: settings, the {@link ServerMonitor}, the {@code /packetdoctor} command,
 * alerts for operators, server crash explanations, and the events the API exposes.
 * Also runs inside singleplayer worlds (the built-in server), where operator alerts are off.
 */
public final class PacketDoctorServer {
	private static ServerConfig config;
	private static volatile @Nullable MinecraftServer server;
	private static volatile @Nullable CrashInfo lastCrash;

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

		ServerLifecycleEvents.SERVER_STARTING.register(s -> {
			server = s;
			ServerMonitor monitor = ServerMonitor.get();
			if (monitor != null) monitor.reset();
			if (s.isDedicatedServer()) CrashHandler.keepReports = config.keepReports;
		});
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			if (s.isDedicatedServer()) reportPreviousCrash();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
		ServerTickEvents.END_SERVER_TICK.register(s -> {
			ServerMonitor monitor = ServerMonitor.get();
			if (monitor != null) monitor.tick();
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> PacketDoctorCommand.register(dispatcher));
		CrashHandler.serverCrashListener = d -> {
			CrashInfo info = crashInfo(d);
			lastCrash = info;
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

	/** On start-up, explain in the console why the server crashed last time. */
	private static void reportPreviousCrash() {
		Diagnosis d = Reports.loadPendingCrash(Reports.SERVER_CRASH);
		if (d == null) return;
		lastCrash = crashInfo(d);
		Reports.clearPendingCrash(Reports.SERVER_CRASH);
		PacketDoctor.LOGGER.warn("The server crashed last time: {}", d.headline());
		PacketDoctor.LOGGER.warn("  {}", d.summary());
		PacketDoctor.LOGGER.warn("  {}", d.sourceLabel());
		for (int i = 0; i < d.tips().size(); i++) PacketDoctor.LOGGER.warn("  {}. {}", i + 1, d.tips().get(i));
		if (d.reportFile() != null) PacketDoctor.LOGGER.warn("  Full report: {}", d.reportFile());
	}

	static CrashInfo crashInfo(Diagnosis d) {
		return new CrashInfo(d.time(), d.headline(), d.summary(), d.sourceLabel(), d.tips(), d.reportFile());
	}

	// --- Reacting to what the monitor finds ----------------------------------------------

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
			if (serious && config.alertOps && s.isDedicatedServer()) alertOps(s, session, info, warning.severity);
		});
	}

	private static void alertOps(MinecraftServer s, ServerMonitor.PlayerSession session, WarningInfo info, Severity severity) {
		MutableComponent hover = Component.literal(info.detail()).withStyle(ChatFormatting.GRAY)
				.append(Component.literal("\n\n" + info.advice()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal("\n\n" + t("packetdoctor.cmd.alert.click")).withStyle(ChatFormatting.YELLOW));
		Component message = Component.literal("[Packet Doctor] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(session.name + ": ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(info.title()).withColor(severity.color & 0xFFFFFF))
				.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))
						.withClickEvent(new ClickEvent.RunCommand("/packetdoctor player " + session.name)));
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
