package com.packetdoctor.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.api.ConsoleProblem;
import com.packetdoctor.api.CrashInfo;
import com.packetdoctor.api.DisconnectInfo;
import com.packetdoctor.api.LagCause;
import com.packetdoctor.api.LagSpikeInfo;
import com.packetdoctor.api.PerformanceInfo;
import com.packetdoctor.api.PlayerReport;
import com.packetdoctor.api.WarningInfo;
import com.packetdoctor.net.PacketExport;
import com.packetdoctor.net.PacketNames;
import com.packetdoctor.net.ServerMonitor;
import com.packetdoctor.net.Severity;
import com.packetdoctor.net.Warning;
import com.packetdoctor.server.console.ConsoleCapture;
import com.packetdoctor.server.perf.LagAnalysis;
import com.packetdoctor.server.perf.LagMonitor;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.packetdoctor.Tr.t;

/**
 * {@code /packetdoctor} for admins (text only; admin GUIs use the API instead).
 * <ul>
 *   <li>{@code /packetdoctor} - who is online, with the mod or not, and their worst problem</li>
 *   <li>{@code /packetdoctor player <name>} - one player's traffic and warnings</li>
 *   <li>{@code /packetdoctor disconnects [count]} - recent disconnects and why</li>
 *   <li>{@code /packetdoctor export <name>} - save a player's packet log to a file</li>
 *   <li>{@code /packetdoctor clear <name>} - forget a player's warnings</li>
 *   <li>{@code /packetdoctor crash} - the last server crash (or unexpected stop), explained</li>
 *   <li>{@code /packetdoctor lag} - TPS, what slow ticks are spent on, and recent lag spikes</li>
 *   <li>{@code /packetdoctor console [clear]} - console warnings and errors, grouped and explained</li>
 * </ul>
 */
final class PacketDoctorCommand {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private PacketDoctorCommand() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("packetdoctor")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.executes(PacketDoctorCommand::status)
				.then(Commands.literal("player")
						.then(Commands.argument("name", StringArgumentType.word()).suggests(PacketDoctorCommand::names)
								.executes(PacketDoctorCommand::player)))
				.then(Commands.literal("disconnects")
						.executes(c -> disconnects(c, 10))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
								.executes(c -> disconnects(c, IntegerArgumentType.getInteger(c, "count")))))
				.then(Commands.literal("export")
						.then(Commands.argument("name", StringArgumentType.word()).suggests(PacketDoctorCommand::names)
								.executes(PacketDoctorCommand::export)))
				.then(Commands.literal("clear")
						.then(Commands.argument("name", StringArgumentType.word()).suggests(PacketDoctorCommand::names)
								.executes(PacketDoctorCommand::clear)))
				.then(Commands.literal("crash").executes(PacketDoctorCommand::crash))
				.then(Commands.literal("lag").executes(PacketDoctorCommand::lag))
				.then(Commands.literal("console").executes(PacketDoctorCommand::console)
						.then(Commands.literal("clear").executes(PacketDoctorCommand::clearConsole))));
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> names(
			CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		ServerMonitor m = ServerMonitor.get();
		return SharedSuggestionProvider.suggest(m == null ? List.of() : m.knownNames(), builder);
	}

	private static void send(CommandContext<CommandSourceStack> c, Component line) {
		c.getSource().sendSystemMessage(line);
	}

	private static Component header(String text) {
		return Component.literal("[Packet Doctor] ").withStyle(ChatFormatting.GOLD).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	/** A clickable player name that runs {@code /packetdoctor player <name>}. */
	private static MutableComponent playerLink(String name) {
		return Component.literal(name).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand("/packetdoctor player " + name))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(t("packetdoctor.cmd.click_details")))));
	}

	private static int status(CommandContext<CommandSourceStack> c) {
		ServerMonitor m = ServerMonitor.get();
		if (m == null) return 0;
		List<ServerMonitor.PlayerSession> sessions = m.onlineSessions();
		long withMod = sessions.stream().filter(ServerMonitor.PlayerSession::hasClientMod).count();
		send(c, header(t("packetdoctor.cmd.status", PacketDoctor.version(), sessions.size(), withMod)));
		if (!PacketDoctorServer.config().monitorPackets) send(c, Component.literal(t("packetdoctor.cmd.monitoring_off")).withStyle(ChatFormatting.YELLOW));
		for (ServerMonitor.PlayerSession s : sessions) {
			PlayerReport r = s.report();
			MutableComponent line = Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY).append(playerLink(s.name))
					.append(Component.literal(String.format(Locale.ROOT, "  %.0f/s %s, %.0f/s %s", r.packetsFromPlayerPerSecond(),
							t("packetdoctor.cmd.from"), r.packetsToPlayerPerSecond(), t("packetdoctor.cmd.to"))).withStyle(ChatFormatting.GRAY));
			if (r.hasClientMod()) line.append(Component.literal("  " + t("packetdoctor.cmd.has_mod")).withStyle(ChatFormatting.GREEN));
			if (r.warningCount() > 0) {
				Severity worst = Severity.valueOf(r.worstSeverity());
				line.append(Component.literal("  " + t("packetdoctor.cmd.warnings", r.warningCount(), worst.label())).withColor(worst.color & 0xFFFFFF));
			}
			send(c, line);
		}
		if (sessions.isEmpty()) send(c, Component.literal(t("packetdoctor.cmd.nobody")).withStyle(ChatFormatting.GRAY));
		send(c, Component.literal(t("packetdoctor.cmd.help")).withStyle(ChatFormatting.DARK_GRAY));
		return sessions.size();
	}

	private static ServerMonitor.PlayerSession find(CommandContext<CommandSourceStack> c) {
		ServerMonitor m = ServerMonitor.get();
		String name = StringArgumentType.getString(c, "name");
		ServerMonitor.PlayerSession s = m == null ? null : m.findByName(name);
		if (s == null) c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.unknown_player", name)));
		return s;
	}

	private static int player(CommandContext<CommandSourceStack> c) {
		ServerMonitor.PlayerSession s = find(c);
		if (s == null) return 0;
		ServerMonitor m = ServerMonitor.get();
		boolean online = m != null && m.online(s.uuid) == s;
		PlayerReport r = s.report();
		send(c, header(s.name + (online ? "" : " " + t("packetdoctor.cmd.offline"))));
		send(c, Component.literal(t(r.hasClientMod() ? "packetdoctor.cmd.mod_yes" : "packetdoctor.cmd.mod_no")).withStyle(ChatFormatting.GRAY));
		send(c, Component.literal(t("packetdoctor.cmd.traffic",
				String.format(Locale.ROOT, "%.0f", r.packetsFromPlayerPerSecond()), PacketNames.count(r.packetsFromPlayer()), PacketNames.bytes(r.bytesFromPlayer()),
				String.format(Locale.ROOT, "%.0f", r.packetsToPlayerPerSecond()), PacketNames.count(r.packetsToPlayer()), PacketNames.bytes(r.bytesToPlayer())))
				.withStyle(ChatFormatting.GRAY));
		List<Warning> warnings = s.warnings().all();
		if (warnings.isEmpty()) {
			send(c, Component.literal(t("packetdoctor.cmd.no_warnings")).withStyle(ChatFormatting.GREEN));
		} else {
			for (Warning w : warnings.subList(0, Math.min(10, warnings.size()))) {
				WarningInfo info = ServerMonitor.info(w);
				MutableComponent line = Component.literal(" [" + w.severity.label() + "] ").withColor(w.severity.color & 0xFFFFFF)
						.append(Component.literal(info.title() + (info.count() > 1 ? " x" + info.count() : "")).withStyle(ChatFormatting.WHITE))
						.append(Component.literal(" - " + info.detail()).withStyle(ChatFormatting.GRAY));
				line.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(info.advice()))));
				send(c, line);
			}
			if (warnings.size() > 10) send(c, Component.literal(t("packetdoctor.cmd.more", warnings.size() - 10)).withStyle(ChatFormatting.DARK_GRAY));
		}
		if (!online && m != null) {
			for (DisconnectInfo d : m.disconnects(Integer.MAX_VALUE)) {
				if (s.uuid.equals(d.uuid())) {
					send(c, Component.literal(t("packetdoctor.cmd.last_disconnect", d.summary())).withStyle(ChatFormatting.YELLOW));
					break;
				}
			}
		}
		return 1;
	}

	private static int disconnects(CommandContext<CommandSourceStack> c, int count) {
		ServerMonitor m = ServerMonitor.get();
		List<DisconnectInfo> list = m == null ? List.of() : m.disconnects(count);
		send(c, header(t("packetdoctor.cmd.disconnects", list.size())));
		for (DisconnectInfo d : list) {
			ChatFormatting color = switch (d.cause()) {
				case LEFT -> ChatFormatting.GRAY;
				case KICKED, REFUSED -> ChatFormatting.YELLOW;
				case BAD_DATA -> ChatFormatting.RED;
				default -> ChatFormatting.GOLD;
			};
			MutableComponent line = Component.literal(" " + TIME.format(Instant.ofEpochMilli(d.time())) + " ").withStyle(ChatFormatting.DARK_GRAY)
					.append(d.uuid() != null ? playerLink(d.name()) : Component.literal(d.name()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal(": " + d.summary()).withStyle(color));
			if (d.explanationSent()) line.append(Component.literal(" " + t("packetdoctor.cmd.explained")).withStyle(ChatFormatting.GREEN));
			send(c, line);
		}
		return list.size();
	}

	private static int export(CommandContext<CommandSourceStack> c) {
		ServerMonitor.PlayerSession s = find(c);
		if (s == null) return 0;
		try {
			Path file = PacketExport.write(s.name, c.getSource().getServer().isDedicatedServer() ? "dedicated server" : "singleplayer", s.log(), s.warnings().all(), PacketDoctorServer.config().keepReports);
			send(c, header(t("packetdoctor.cmd.exported", file.toAbsolutePath())));
			return 1;
		} catch (IOException e) {
			c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.export_failed", e.getMessage())));
			return 0;
		}
	}

	private static int clear(CommandContext<CommandSourceStack> c) {
		ServerMonitor.PlayerSession s = find(c);
		if (s == null) return 0;
		s.warnings().clear();
		send(c, header(t("packetdoctor.cmd.cleared", s.name)));
		return 1;
	}

	private static int lag(CommandContext<CommandSourceStack> c) {
		LagMonitor m = LagMonitor.get();
		if (m == null || !c.getSource().getServer().isDedicatedServer() || !PacketDoctorServer.config().lagMonitor) {
			c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.lag.off")));
			return 0;
		}
		PerformanceInfo p = m.performance();
		send(c, header(t("packetdoctor.cmd.lag.title")));
		send(c, Component.literal(t("packetdoctor.cmd.lag.tps", tps(p.tps10s()), tps(p.tps1m()), tps(p.tps5m()), tps(p.targetTps())))
				.withStyle(tpsColor(p.tps1m(), p.targetTps())));
		send(c, Component.literal(t("packetdoctor.cmd.lag.mspt", String.format(Locale.ROOT, "%.1f", p.msptAverage()),
				String.format(Locale.ROOT, "%.0f", p.msptMax()), String.format(Locale.ROOT, "%.0f", 1000 / Math.max(1, p.targetTps()))))
				.withStyle(ChatFormatting.GRAY));
		if (p.overloaded()) send(c, Component.literal(t("packetdoctor.cmd.lag.overloaded")).withStyle(ChatFormatting.RED));

		if (p.causes().isEmpty()) {
			send(c, Component.literal(t("packetdoctor.cmd.lag.no_slow")).withStyle(ChatFormatting.GREEN));
		} else {
			send(c, Component.literal(t("packetdoctor.cmd.lag.causes")).withStyle(ChatFormatting.WHITE));
			for (LagCause cause : p.causes()) send(c, causeLine(cause));
		}
		send(c, Component.literal(t("packetdoctor.cmd.lag.world", PacketNames.count(p.loadedChunks()), PacketNames.count(p.entities()),
				p.topEntities().isEmpty() ? "-" : String.join(", ", p.topEntities().subList(0, Math.min(3, p.topEntities().size()))))).withStyle(ChatFormatting.DARK_GRAY));

		List<LagSpikeInfo> spikes = m.spikes(5);
		if (!spikes.isEmpty()) {
			send(c, Component.literal(t("packetdoctor.cmd.lag.spikes")).withStyle(ChatFormatting.WHITE));
			for (LagSpikeInfo s : spikes) {
				MutableComponent hover = Component.literal(s.summary()).withStyle(ChatFormatting.WHITE);
				for (LagCause cause : s.causes()) hover.append(Component.literal("\n" + LagAnalysis.line(cause)).withStyle(ChatFormatting.GRAY));
				for (String line : s.consoleLines()) hover.append(Component.literal("\n" + line).withStyle(ChatFormatting.DARK_GRAY));
				String top = s.causes().isEmpty() ? t("packetdoctor.cmd.lag.unknown")
						: s.causes().getFirst().label() + String.format(Locale.ROOT, " %.0f%%", s.causes().getFirst().percent());
				send(c, Component.literal(" " + TIME.format(Instant.ofEpochMilli(s.time())) + "  ").withStyle(ChatFormatting.DARK_GRAY)
						.append(Component.literal(LagAnalysis.seconds(s.durationMs())).withStyle(ChatFormatting.YELLOW))
						.append(Component.literal("  " + top).withStyle(ChatFormatting.GRAY))
						.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))));
			}
		}
		return (int) Math.round(p.tps1m());
	}

	private static Component causeLine(LagCause cause) {
		MutableComponent hover = Component.literal(cause.advice()).withStyle(ChatFormatting.WHITE);
		return Component.literal(String.format(Locale.ROOT, " %3.0f%% ", cause.percent())).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(cause.label()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(cause.mod() != null ? "  " + cause.mod() : "").withStyle(ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal(cause.examples().isEmpty() ? "" : "  (" + String.join(", ", cause.examples()) + ")").withStyle(ChatFormatting.GRAY))
				.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	private static String tps(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static ChatFormatting tpsColor(double tps, double target) {
		return tps >= target * 0.95 ? ChatFormatting.GREEN : tps >= target * 0.75 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	private static int console(CommandContext<CommandSourceStack> c) {
		ConsoleCapture capture = ConsoleCapture.get();
		if (capture == null) {
			c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.console.off")));
			return 0;
		}
		List<ConsoleProblem> problems = capture.problems();
		send(c, header(t("packetdoctor.cmd.console.title", problems.size())));
		if (problems.isEmpty()) send(c, Component.literal(t("packetdoctor.cmd.console.none")).withStyle(ChatFormatting.GREEN));
		for (ConsoleProblem p : problems.subList(0, Math.min(10, problems.size()))) {
			Severity severity = Severity.valueOf(p.severity());
			MutableComponent hover = Component.literal(p.explanation()).withStyle(ChatFormatting.GRAY)
					.append(Component.literal("\n\n" + p.advice()).withStyle(ChatFormatting.WHITE))
					.append(Component.literal("\n\n" + p.example()).withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal("\n" + t("packetdoctor.cmd.console.seen", TIME.format(Instant.ofEpochMilli(p.firstSeen())),
							TIME.format(Instant.ofEpochMilli(p.lastSeen())))).withStyle(ChatFormatting.DARK_GRAY));
			send(c, Component.literal(" [" + severity.label() + "] ").withColor(severity.color & 0xFFFFFF)
					.append(Component.literal(p.title() + (p.count() > 1 ? " x" + p.count() : "")).withStyle(ChatFormatting.WHITE))
					.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))));
		}
		if (problems.size() > 10) send(c, Component.literal(t("packetdoctor.cmd.more", problems.size() - 10)).withStyle(ChatFormatting.DARK_GRAY));
		if (!problems.isEmpty()) send(c, Component.literal(t("packetdoctor.cmd.console.hover")).withStyle(ChatFormatting.DARK_GRAY));
		return problems.size();
	}

	private static int clearConsole(CommandContext<CommandSourceStack> c) {
		ConsoleCapture capture = ConsoleCapture.get();
		if (capture != null) capture.clearProblems();
		send(c, header(t("packetdoctor.cmd.console.cleared")));
		return 1;
	}

	private static int crash(CommandContext<CommandSourceStack> c) {
		CrashInfo crash = PacketDoctorServer.lastCrash();
		if (crash == null) {
			send(c, header(t("packetdoctor.cmd.no_crash")));
			return 0;
		}
		send(c, header(t("packetdoctor.cmd.crash", TIME.format(Instant.ofEpochMilli(crash.time())))));
		send(c, Component.literal(crash.headline()).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		send(c, Component.literal(crash.summary()).withStyle(ChatFormatting.WHITE));
		send(c, Component.literal(crash.source()).withStyle(ChatFormatting.LIGHT_PURPLE));
		for (int i = 0; i < crash.tips().size(); i++) send(c, Component.literal(" " + (i + 1) + ". " + crash.tips().get(i)).withStyle(ChatFormatting.GRAY));
		if (crash.reportFile() != null) send(c, Component.literal(t("packetdoctor.cmd.report_file", crash.reportFile())).withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}
}
