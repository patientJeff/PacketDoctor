package com.packetdoctor.server;

import com.mojang.brigadier.context.CommandContext;
import com.packetdoctor.net.ServerMonitor;
import com.packetdoctor.server.perf.LagMonitor;
import com.packetdoctor.server.perf.LagReport;
import com.packetdoctor.server.perf.ProfileData;
import com.packetdoctor.server.perf.Where;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import static com.packetdoctor.Tr.t;

/**
 * What admins see about players: {@code /packetdoctor players} (everyone online, sorted by
 * how much lag is around them) and the extra sections of {@code /packetdoctor player <name>}.
 */
final class PlayerCommands {
	private PlayerCommands() {
	}

	private static void send(CommandContext<CommandSourceStack> c, Component line) {
		c.getSource().sendSystemMessage(line);
	}

	private static @Nullable ProfileData profile() {
		LagMonitor m = LagMonitor.get();
		return m == null || !PacketDoctorServer.config().profileServer ? null : m.profile(LagCommands.DEFAULT_MINUTES);
	}

	static int players(CommandContext<CommandSourceStack> c) {
		ServerMonitor monitor = ServerMonitor.get();
		List<ServerPlayer> online = new ArrayList<>(c.getSource().getServer().getPlayerList().getPlayers());
		ProfileData d = profile();
		online.sort(Comparator.comparingLong((ServerPlayer p) -> d == null ? 0 : d.players.get(p.getGameProfile().name())).reversed());
		send(c, Component.literal("[Packet Doctor] ").withStyle(ChatFormatting.GOLD)
				.append(Component.literal(t("packetdoctor.cmd.players.title", online.size())).withStyle(ChatFormatting.WHITE)));
		for (ServerPlayer p : online) {
			String name = p.getGameProfile().name();
			PlayerInsight.Track tr = PlayerInsight.get(p.getUUID());
			ServerMonitor.PlayerSession s = monitor == null ? null : monitor.online(p.getUUID());
			MutableComponent line = Component.literal(" ").append(Component.literal(name).withStyle(style -> style.withColor(ChatFormatting.AQUA)
					.withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/packetdoctor player " + name))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(t("packetdoctor.cmd.click_details"))))));
			int ping = p.connection.latency();
			line.append(Component.literal("  " + t("packetdoctor.cmd.players.ping", ping)).withStyle(ping > 250 ? ChatFormatting.RED
					: ping > 120 ? ChatFormatting.YELLOW : ChatFormatting.GREEN));
			if (d != null && !d.isEmpty()) {
				long near = d.players.get(name);
				double pct = d.percent(near);
				line.append(Component.literal("  " + t("packetdoctor.cmd.players.lag", LagReport.pct(pct)))
						.withStyle(pct >= 20 ? ChatFormatting.RED : pct >= 8 ? ChatFormatting.GOLD : ChatFormatting.GRAY));
			}
			if (tr != null) {
				line.append(Component.literal(String.format(Locale.ROOT, "  %s %d, %d", Where.dimName(tr.dim), tr.x, tr.z)).withStyle(ChatFormatting.DARK_GRAY));
				int explored = tr.newChunks(5);
				if (explored >= 20) {
					line.append(Component.literal("  " + t(tr.elytra ? "packetdoctor.cmd.players.exploring_elytra" : "packetdoctor.cmd.players.exploring", explored))
							.withStyle(ChatFormatting.YELLOW));
				}
			}
			if (s != null && s.warnings().all().size() > 0) {
				line.append(Component.literal("  " + t("packetdoctor.cmd.players.warnings", s.warnings().all().size())).withStyle(ChatFormatting.GOLD));
			}
			send(c, line);
		}
		if (online.isEmpty()) send(c, Component.literal(t("packetdoctor.cmd.nobody")).withStyle(ChatFormatting.GRAY));
		else send(c, Component.literal(t("packetdoctor.cmd.players.hint")).withStyle(ChatFormatting.DARK_GRAY));
		return online.size();
	}

	/** The connection, world and lag sections of one player's details (online players only). */
	static void details(CommandContext<CommandSourceStack> c, ServerMonitor.PlayerSession s) {
		ServerPlayer p = c.getSource().getServer().getPlayerList().getPlayer(s.uuid);
		if (p == null) return;
		PlayerInsight.Track tr = PlayerInsight.get(s.uuid);

		// Connection and game.
		send(c, Component.literal(t("packetdoctor.cmd.player.section.connection")).withStyle(ChatFormatting.WHITE));
		int ping = p.connection.latency();
		String brand = s.brand != null ? s.brand : t("packetdoctor.cmd.player.unknown");
		send(c, Component.literal(" " + t("packetdoctor.cmd.player.connection", ping, brand, p.clientInformation().viewDistance(),
				p.clientInformation().language())).withStyle(ChatFormatting.GRAY));
		List<String> mods = clientMods(p);
		if (!mods.isEmpty()) {
			send(c, Component.literal(" " + t("packetdoctor.cmd.player.client_mods", mods.size(), String.join(", ", mods.subList(0, Math.min(20, mods.size())))
					+ (mods.size() > 20 ? ", ..." : ""))).withStyle(ChatFormatting.GRAY));
		}
		String sent = s.busiestFromPlayer(10);
		if (!sent.isEmpty()) send(c, Component.literal(" " + t("packetdoctor.cmd.player.sends", sent)).withStyle(ChatFormatting.DARK_GRAY));
		String received = s.busiestToPlayer(10);
		if (!received.isEmpty()) send(c, Component.literal(" " + t("packetdoctor.cmd.player.receives", received)).withStyle(ChatFormatting.DARK_GRAY));

		// Where they are and what they are doing.
		send(c, Component.literal(t("packetdoctor.cmd.player.section.world")).withStyle(ChatFormatting.WHITE));
		String dim = p.level().dimension().identifier().toString();
		String tp = "/execute in " + dim + " run tp @s " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ();
		send(c, Component.literal(" " + t("packetdoctor.cmd.player.where", Where.dimName(dim), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
				p.gameMode().getName())).withStyle(style -> style.withColor(ChatFormatting.GRAY)
				.withClickEvent(new ClickEvent.RunCommand(tp)).withHoverEvent(new HoverEvent.ShowText(Component.literal(t("packetdoctor.cmd.lag.click_tp"))))));
		if (tr != null) {
			String moving = String.format(Locale.ROOT, "%.1f", tr.speed(10));
			send(c, Component.literal(" " + t(tr.elytra ? "packetdoctor.cmd.player.moving_elytra" : "packetdoctor.cmd.player.moving", moving,
					tr.newChunks(5), tr.sessionChunks)).withStyle(tr.newChunks(5) >= 100 ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
			if (tr.nearbyTime > 0) {
				send(c, Component.literal(" " + t("packetdoctor.cmd.player.nearby", PlayerInsight.NEARBY_BLOCKS, tr.nearbyEntities,
						tr.topNearbyEntities.isEmpty() ? "-" : String.join(", ", tr.topNearbyEntities), tr.nearbyBlockEntities,
						tr.topNearbyBlockEntities.isEmpty() ? "-" : String.join(", ", tr.topNearbyBlockEntities))).withStyle(ChatFormatting.GRAY));
			}
		}

		// Lag around them.
		ProfileData d = profile();
		if (d != null && !d.isEmpty()) {
			String name = p.getGameProfile().name();
			long near = d.players.get(name);
			MutableComponent line = Component.literal(" " + t("packetdoctor.cmd.player.lag", LagReport.pct(d.percent(near)), LagReport.ms(d, near)))
					.withStyle(d.percent(near) >= 15 ? ChatFormatting.GOLD : ChatFormatting.GRAY);
			send(c, Component.literal(t("packetdoctor.cmd.player.section.lag")).withStyle(ChatFormatting.WHITE));
			send(c, line);
			for (Map.Entry<String, Long> th : d.playerThings.topWithin(name, 4)) {
				send(c, Component.literal("   " + LagReport.pct(d.percent(th.getValue())) + "  " + LagReport.typeOf(th.getKey())
						+ " (" + LagReport.kindWord(th.getKey()) + ")").withStyle(ChatFormatting.DARK_GRAY));
			}
		}

		// Time played.
		long session = System.currentTimeMillis() - s.joined;
		int playTicks = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
		send(c, Component.literal(" " + t("packetdoctor.cmd.player.time", duration(session), duration(playTicks * 50L))).withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * Mods on the player's game that talk to the server, from the network channels it
	 * registered (purely visual client mods register none, so they can't be seen).
	 */
	private static List<String> clientMods(ServerPlayer p) {
		TreeSet<String> out = new TreeSet<>();
		try {
			for (Identifier id : ServerPlayNetworking.getSendable(p)) {
				String ns = id.getNamespace();
				if (ns.equals("minecraft") || ns.equals("c") || ns.startsWith("fabric") || ns.equals("packetdoctor")) continue;
				out.add(ns);
			}
		} catch (RuntimeException ignored) {
		}
		return List.copyOf(out);
	}

	private static String duration(long ms) {
		Duration d = Duration.ofMillis(Math.max(0, ms));
		long h = d.toHours();
		long m = d.toMinutesPart();
		return h > 0 ? h + " h " + m + " min" : m + " min";
	}
}
