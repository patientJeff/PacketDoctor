package com.packetdoctor.server;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.api.LagSpikeInfo;
import com.packetdoctor.diagnose.ModBlame;
import com.packetdoctor.server.perf.LagAnalysis;
import com.packetdoctor.server.perf.LagMonitor;
import com.packetdoctor.server.perf.LagReport;
import com.packetdoctor.server.perf.ProfileData;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.packetdoctor.Tr.t;

/**
 * The detailed lag views under {@code /packetdoctor lag}: the biggest causes with what to
 * do, time by mod, one mod in detail, busy places, block entity and entity types, a lag
 * spike in detail, and report files.
 */
final class LagCommands {
	static final int DEFAULT_MINUTES = 5;

	@FunctionalInterface
	interface Run {
		int run(CommandContext<CommandSourceStack> c, int minutes) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
	}

	private LagCommands() {
	}

	private static @Nullable LagMonitor monitor(CommandContext<CommandSourceStack> c) {
		LagMonitor m = LagMonitor.get();
		if (m == null || !c.getSource().getServer().isDedicatedServer() || !PacketDoctorServer.config().lagMonitor) {
			c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.lag.off")));
			return null;
		}
		return m;
	}

	private static void send(CommandContext<CommandSourceStack> c, Component line) {
		c.getSource().sendSystemMessage(line);
	}

	private static Component header(String text) {
		return Component.literal("[Packet Doctor] ").withStyle(ChatFormatting.GOLD).append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	private static String window(int minutes) {
		return t(minutes == 1 ? "packetdoctor.cmd.lag.window_one" : "packetdoctor.cmd.lag.window", minutes);
	}

	private static ChatFormatting color(double percent) {
		return percent >= 20 ? ChatFormatting.RED : percent >= 8 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
	}

	/** One finding: its title (hover for everything, click to act) and the first thing to do under it. */
	static void finding(CommandContext<CommandSourceStack> c, LagReport.Finding f, int number, boolean withAction) {
		MutableComponent hover = Component.literal(f.title()).withStyle(ChatFormatting.WHITE);
		for (String d : f.details()) hover.append(Component.literal("\n" + d).withStyle(ChatFormatting.GRAY));
		if (!f.actions().isEmpty()) {
			hover.append(Component.literal("\n\n" + t("packetdoctor.cmd.lag.what_to_do")).withStyle(ChatFormatting.WHITE));
			for (String a : f.actions()) hover.append(Component.literal("\n- " + a).withStyle(ChatFormatting.GREEN));
		}
		String command = click(f);
		if (command != null) hover.append(Component.literal("\n\n" + t(command.startsWith("/execute") ? "packetdoctor.cmd.lag.click_tp"
				: "packetdoctor.cmd.lag.click_more")).withStyle(ChatFormatting.YELLOW));
		MutableComponent line = Component.literal(" " + number + ". ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(f.title()).withStyle(color(f.percent())))
				.withStyle(style -> {
					var s = style.withHoverEvent(new HoverEvent.ShowText(hover));
					return command == null ? s : s.withClickEvent(new ClickEvent.RunCommand(command));
				});
		send(c, line);
		if (withAction && !f.actions().isEmpty()) {
			send(c, Component.literal("    → " + f.actions().getFirst()).withStyle(ChatFormatting.GRAY));
		}
	}

	private static @Nullable String click(LagReport.Finding f) {
		return switch (f.type()) {
			case MOD -> "/packetdoctor lag mod " + f.key();
			case PLACE -> f.where() == null ? null : f.where().teleport();
			case THING -> f.where() == null ? null : f.where().teleport();
			case WORK -> f.where() == null ? null : f.where().teleport();
		};
	}

	private static MutableComponent button(String text, String command, String hover) {
		return Component.literal("[" + text + "]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	private static Component buttons() {
		return Component.literal(" ")
				.append(button(t("packetdoctor.cmd.lag.btn.top"), "/packetdoctor lag top", t("packetdoctor.cmd.lag.btn.top.hover"))).append(" ")
				.append(button(t("packetdoctor.cmd.lag.btn.mods"), "/packetdoctor lag mods", t("packetdoctor.cmd.lag.btn.mods.hover"))).append(" ")
				.append(button(t("packetdoctor.cmd.lag.btn.places"), "/packetdoctor lag places", t("packetdoctor.cmd.lag.btn.places.hover"))).append(" ")
				.append(button(t("packetdoctor.cmd.lag.btn.things"), "/packetdoctor lag things", t("packetdoctor.cmd.lag.btn.things.hover"))).append(" ")
				.append(button(t("packetdoctor.cmd.lag.btn.report"), "/packetdoctor lag report", t("packetdoctor.cmd.lag.btn.report.hover")));
	}

	/** The top of {@code /packetdoctor lag}: the biggest causes of the last minutes. */
	static void biggest(CommandContext<CommandSourceStack> c, LagMonitor m, int max) {
		if (!PacketDoctorServer.config().profileServer) {
			send(c, Component.literal(t("packetdoctor.cmd.lag.profile_off")).withStyle(ChatFormatting.DARK_GRAY));
			return;
		}
		ProfileData d = m.profile(DEFAULT_MINUTES);
		List<LagReport.Finding> all = LagReport.findings(d, m.census(), c.getSource().getServer());
		send(c, Component.literal(t("packetdoctor.cmd.lag.biggest", window(DEFAULT_MINUTES))).withStyle(ChatFormatting.WHITE));
		if (all.isEmpty()) {
			send(c, Component.literal(" " + t("packetdoctor.cmd.lag.top.none")).withStyle(ChatFormatting.GREEN));
		} else {
			for (int i = 0; i < Math.min(max, all.size()); i++) finding(c, all.get(i), i + 1, true);
		}
		send(c, buttons());
	}

	static int top(CommandContext<CommandSourceStack> c, int minutes) {
		LagMonitor m = monitor(c);
		if (m == null) return 0;
		ProfileData d = m.profile(minutes);
		List<LagReport.Finding> all = LagReport.findings(d, m.census(), c.getSource().getServer());
		send(c, header(t("packetdoctor.cmd.lag.top.title", window(minutes), String.format(java.util.Locale.ROOT, "%.1f", d.msPerTick()))));
		if (all.isEmpty()) send(c, Component.literal(" " + t("packetdoctor.cmd.lag.top.none")).withStyle(ChatFormatting.GREEN));
		for (int i = 0; i < Math.min(12, all.size()); i++) finding(c, all.get(i), i + 1, true);
		send(c, Component.literal(t("packetdoctor.cmd.lag.hover_hint")).withStyle(ChatFormatting.DARK_GRAY));
		send(c, buttons());
		return all.size();
	}

	static int mods(CommandContext<CommandSourceStack> c, int minutes) {
		LagMonitor m = monitor(c);
		if (m == null) return 0;
		ProfileData d = m.profile(minutes);
		send(c, header(t("packetdoctor.cmd.lag.mods.title", window(minutes))));
		int shown = 0;
		for (Map.Entry<String, Long> e : d.mods.top(40)) {
			String mod = e.getKey();
			if (mod.equals(PacketDoctor.MOD_ID) || ModBlame.isPlatform(mod)) continue;
			double pct = d.percent(e.getValue());
			if (pct < 0.3 || shown >= 15) break;
			shown++;
			String what = LagReport.modShort(d, mod);
			MutableComponent line = Component.literal(String.format(java.util.Locale.ROOT, " %6s ", LagReport.pct(pct))).withStyle(color(pct))
					.append(Component.literal(LagReport.ms(d, e.getValue()) + "  ").withStyle(ChatFormatting.DARK_GRAY))
					.append(Component.literal(LagReport.label(mod)).withStyle(ChatFormatting.LIGHT_PURPLE))
					.append(Component.literal("  " + what).withStyle(ChatFormatting.GRAY))
					.withStyle(style -> style.withClickEvent(new ClickEvent.RunCommand("/packetdoctor lag mod " + mod))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal(t("packetdoctor.cmd.lag.click_more")))));
			send(c, line);
		}
		if (shown == 0) send(c, Component.literal(" " + t("packetdoctor.cmd.lag.mods.none")).withStyle(ChatFormatting.GREEN));
		return shown;
	}

	static int mod(CommandContext<CommandSourceStack> c, int minutes) {
		LagMonitor m = monitor(c);
		if (m == null) return 0;
		String mod = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "id");
		if (!FabricLoader.getInstance().isModLoaded(mod)) {
			c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.lag.mod.unknown", mod)));
			return 0;
		}
		ProfileData d = m.profile(minutes);
		send(c, header(t("packetdoctor.cmd.lag.mod.title", LagReport.label(mod), window(minutes))));
		if (d.mods.get(mod) == 0) {
			send(c, Component.literal(" " + t("packetdoctor.cmd.lag.mod.none", LagReport.label(mod))).withStyle(ChatFormatting.GREEN));
			return 0;
		}
		LagReport.Finding f = LagReport.mod(d, m.census(), mod);
		send(c, Component.literal(" " + f.title()).withStyle(color(f.percent())));
		for (String line : f.details()) {
			boolean heading = !line.startsWith(" ");
			send(c, Component.literal("  " + line).withStyle(heading ? ChatFormatting.WHITE : ChatFormatting.GRAY));
		}
		send(c, Component.literal("  " + t("packetdoctor.cmd.lag.what_to_do")).withStyle(ChatFormatting.WHITE));
		for (String a : f.actions()) send(c, Component.literal("   - " + a).withStyle(ChatFormatting.GREEN));
		MutableComponent row = Component.literal(" ");
		if (f.where() != null) {
			row.append(button(t("packetdoctor.cmd.lag.btn.go"), f.where().teleport(), t("packetdoctor.cmd.lag.click_tp"))).append(" ");
		}
		row.append(button(t("packetdoctor.cmd.lag.btn.report"), "/packetdoctor lag report " + minutes, t("packetdoctor.cmd.lag.btn.report.hover")));
		send(c, row);
		return 1;
	}

	static CompletableFuture<Suggestions> modIds(CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
		List<String> ids = new ArrayList<>();
		LagMonitor m = LagMonitor.get();
		if (m != null) {
			for (Map.Entry<String, Long> e : m.profile(60).mods.top(30)) {
				if (!ModBlame.isPlatform(e.getKey()) && !e.getKey().equals(PacketDoctor.MOD_ID)) ids.add(e.getKey());
			}
		}
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (!ids.contains(id) && !ModBlame.isPlatform(id) && mod.getContainingMod().isEmpty()) ids.add(id);
		}
		return SharedSuggestionProvider.suggest(ids, builder);
	}

	static int places(CommandContext<CommandSourceStack> c, int minutes) {
		LagMonitor m = monitor(c);
		if (m == null) return 0;
		ProfileData d = m.profile(minutes);
		List<LagReport.Finding> list = LagReport.places(d, m.census(), c.getSource().getServer(), 0.5, 10);
		send(c, header(t("packetdoctor.cmd.lag.places.title", window(minutes))));
		if (list.isEmpty()) send(c, Component.literal(" " + t("packetdoctor.cmd.lag.places.none")).withStyle(ChatFormatting.GREEN));
		for (int i = 0; i < list.size(); i++) finding(c, list.get(i), i + 1, true);
		if (!list.isEmpty()) send(c, Component.literal(t("packetdoctor.cmd.lag.places.hint")).withStyle(ChatFormatting.DARK_GRAY));
		return list.size();
	}

	static int things(CommandContext<CommandSourceStack> c, int minutes) {
		LagMonitor m = monitor(c);
		if (m == null) return 0;
		ProfileData d = m.profile(minutes);
		List<LagReport.Finding> list = LagReport.things(d, m.census(), 0.5, 12);
		send(c, header(t("packetdoctor.cmd.lag.things.title", window(minutes))));
		if (list.isEmpty()) send(c, Component.literal(" " + t("packetdoctor.cmd.lag.things.none")).withStyle(ChatFormatting.GREEN));
		for (int i = 0; i < list.size(); i++) finding(c, list.get(i), i + 1, false);
		return list.size();
	}

	static int spike(CommandContext<CommandSourceStack> c, int number) {
		LagMonitor m = monitor(c);
		if (m == null) return 0;
		List<LagSpikeInfo> spikes = m.spikes(number);
		ProfileData d = m.spikeProfile(number - 1);
		if (spikes.size() < number || d == null) {
			c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.lag.spike.unknown", number)));
			return 0;
		}
		LagSpikeInfo s = spikes.get(number - 1);
		send(c, header(t("packetdoctor.cmd.lag.spike.title", LagAnalysis.seconds(s.durationMs()), s.ticksLost())));
		send(c, Component.literal(" " + s.summary()).withStyle(ChatFormatting.GRAY));
		List<LagReport.Finding> all = LagReport.findings(d, m.census(), c.getSource().getServer());
		for (int i = 0; i < Math.min(8, all.size()); i++) finding(c, all.get(i), i + 1, true);
		for (String line : s.consoleLines()) send(c, Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY));
		return 1;
	}

	static int report(CommandContext<CommandSourceStack> c, int minutes) {
		LagMonitor m = monitor(c);
		if (m == null) return 0;
		try {
			ProfileData spike = m.spikeProfile(0);
			List<LagSpikeInfo> spikes = m.spikes(1);
			String spikeWhat = spikes.isEmpty() ? null : t("packetdoctor.report.lag.last_spike", LagAnalysis.seconds(spikes.getFirst().durationMs()));
			Path file = LagReport.write(c.getSource().getServer(), m.profile(minutes), window(minutes), "report",
					spikes.isEmpty() ? null : spike, spikeWhat, PacketDoctorServer.config().keepLagReports);
			send(c, header(t("packetdoctor.cmd.lag.report.saved", file.toAbsolutePath())));
			return 1;
		} catch (IOException e) {
			c.getSource().sendFailure(Component.literal(t("packetdoctor.cmd.export_failed", e.getMessage())));
			return 0;
		}
	}
}
