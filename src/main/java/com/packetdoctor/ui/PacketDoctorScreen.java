package com.packetdoctor.ui;

import com.mojang.blaze3d.Blaze3D;
import com.packetdoctor.Config;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.client.LagWatcher;
import com.packetdoctor.client.PacketDoctorClient;
import com.packetdoctor.diagnose.CrashHandler;
import com.packetdoctor.diagnose.Diagnosis;
import com.packetdoctor.diagnose.Reports;
import com.packetdoctor.net.PacketExport;
import com.packetdoctor.net.PacketLog;
import com.packetdoctor.net.PacketMonitor;
import com.packetdoctor.net.PacketNames;
import com.packetdoctor.net.PacketRecord;
import com.packetdoctor.net.Severity;
import com.packetdoctor.net.Warning;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * The main window (default key: K; also Mod Menu's Configure button). Tabs:
 * <ul>
 *   <li><b>Warnings</b> - problems found on this connection, explained.</li>
 *   <li><b>Lag</b> - whether lag right now is the server, the connection or the game, and why.</li>
 *   <li><b>Live</b> - every packet as it goes by, newest first; flagged ones are coloured.</li>
 *   <li><b>Stats</b> - totals per packet type.</li>
 *   <li><b>History</b> - disconnect and crash explanations; click one to open it.</li>
 *   <li><b>Settings</b> - click a line to change it.</li>
 * </ul>
 * Live and Stats can export the packet log to a file.
 */
public final class PacketDoctorScreen extends Screen {
	private enum Tab {
		WARNINGS("packetdoctor.tab.warnings"),
		LAG("packetdoctor.tab.lag"),
		LIVE("packetdoctor.tab.live"),
		STATS("packetdoctor.tab.stats"),
		HISTORY("packetdoctor.tab.history"),
		SETTINGS("packetdoctor.tab.settings");

		final String key;

		Tab(String key) {
			this.key = key;
		}
	}

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final int GREEN = 0xFF9BE39B;
	private static Tab lastTab = Tab.WARNINGS;

	private final @Nullable Screen parent;
	private final TextPanel panel = new TextPanel();
	private Tab tab;
	private boolean frozen;
	private int ticks;
	private Component status = Component.empty();
	private long statusUntil;

	public PacketDoctorScreen(@Nullable Screen parent) {
		this(parent, lastTab);
	}

	private PacketDoctorScreen(@Nullable Screen parent, Tab tab) {
		super(Component.translatable("packetdoctor.title"));
		this.parent = parent;
		this.tab = tab;
	}

	/** Opens straight on the Lag tab. */
	public static PacketDoctorScreen lag(@Nullable Screen parent) {
		return new PacketDoctorScreen(parent, Tab.LAG);
	}

	/** Opens straight on the Settings tab (Mod Menu's Configure button). */
	public static PacketDoctorScreen settings(@Nullable Screen parent) {
		return new PacketDoctorScreen(parent, Tab.SETTINGS);
	}

	@Override
	protected void init() {
		int tabs = Tab.values().length;
		int tw = Math.min(84, (width - 32 - (tabs - 1) * 2) / tabs);
		int tx = (width - (tw * tabs + (tabs - 1) * 2)) / 2;
		for (Tab t : Tab.values()) {
			Button b = Button.builder(Component.translatable(t.key), btn -> switchTo(t)).bounds(tx + t.ordinal() * (tw + 2), 22, tw, 20).build();
			b.active = t != tab;
			addRenderableWidget(b);
		}

		panel.setBounds(16, 46, width - 32, height - 46 - 32);

		List<Button.Builder> bar = new ArrayList<>();
		switch (tab) {
			case LIVE -> {
				bar.add(Button.builder(Component.translatable(frozen ? "packetdoctor.button.resume" : "packetdoctor.button.freeze"), b -> {
					frozen = !frozen;
					b.setMessage(Component.translatable(frozen ? "packetdoctor.button.resume" : "packetdoctor.button.freeze"));
				}));
				bar.add(Button.builder(Component.translatable("packetdoctor.button.export"), b -> export()));
			}
			case STATS -> bar.add(Button.builder(Component.translatable("packetdoctor.button.export"), b -> export()));
			case WARNINGS -> bar.add(Button.builder(Component.translatable("packetdoctor.button.clear"), b -> {
				PacketMonitor.get().warnings().clear();
				rebuild(false);
			}));
			case LAG -> {
			}
			case HISTORY, SETTINGS -> bar.add(Button.builder(Component.translatable("packetdoctor.button.open_folder"), b -> openReports()));
		}
		bar.add(Button.builder(Component.translatable("gui.done"), b -> onClose()));
		int bw = Math.min(150, (width - 32 - (bar.size() - 1) * 4) / bar.size());
		int bx = (width - (bw * bar.size() + (bar.size() - 1) * 4)) / 2;
		for (int i = 0; i < bar.size(); i++) addRenderableWidget(bar.get(i).bounds(bx + i * (bw + 4), height - 26, bw, 20).build());
		rebuild(true);
	}

	private void switchTo(Tab t) {
		tab = lastTab = t;
		panel.setScroll(0);
		rebuildWidgets();
	}

	@Override
	public void tick() {
		ticks++;
		boolean live = (tab == Tab.LIVE || tab == Tab.STATS) && !frozen && ticks % 10 == 0;
		if (live || (tab == Tab.WARNINGS || tab == Tab.LAG) && ticks % 20 == 0) rebuild(true);
	}

	private void rebuild(boolean keepScroll) {
		double scroll = keepScroll ? panel.scroll() : 0;
		panel.clear();
		switch (tab) {
			case WARNINGS -> buildWarnings();
			case LAG -> buildLag();
			case LIVE -> buildLive();
			case STATS -> buildStats();
			case HISTORY -> buildHistory();
			case SETTINGS -> buildSettings();
		}
		panel.setScroll(scroll);
	}

	// --- Tabs -----------------------------------------------------------------------

	private void statusLine() {
		PacketMonitor m = PacketMonitor.get();
		if (m.isConnected()) {
			panel.wrapped(font, Component.translatable("packetdoctor.status.connected", m.server(),
					String.format(Locale.ROOT, "%.0f", m.inboundRate()), String.format(Locale.ROOT, "%.0f", m.outboundRate())), DiagnosisScreen.MUTED, 0);
		} else {
			panel.wrapped(font, Component.translatable("packetdoctor.status.not_connected"), DiagnosisScreen.MUTED, 0);
		}
	}

	private void buildWarnings() {
		statusLine();
		List<Warning> all = PacketMonitor.get().warnings().all();
		panel.blank();
		if (all.isEmpty()) {
			panel.wrapped(font, Component.translatable("packetdoctor.warnings.none"), GREEN, 0);
			panel.wrapped(font, Component.translatable("packetdoctor.warnings.explain"), DiagnosisScreen.MUTED, 0);
			return;
		}
		for (Warning w : all) {
			Component head = Component.literal("[" + w.severity.label() + "] ").withColor(w.severity.color & 0xFFFFFF)
					.append(Component.literal(w.title()).withStyle(ChatFormatting.BOLD).withColor(0xFFFFFF))
					.append(Component.literal("  " + w.clock() + (w.count() > 1 ? "  x" + w.count() : "")).withColor(DiagnosisScreen.MUTED & 0xFFFFFF));
			panel.wrapped(font, head, DiagnosisScreen.TEXT, 0);
			if (w.direction != null) {
				String packet = w.packetName();
				panel.wrapped(font, Component.literal(w.direction.arrow + " " + w.direction.description()
						+ (packet != null ? ": " + packet : "")), w.direction.color, 12);
			}
			panel.wrapped(font, Component.literal(w.detail()), DiagnosisScreen.MUTED, 12);
			panel.wrapped(font, Component.literal(w.advice()), DiagnosisScreen.TEXT, 12);
			String source = w.source();
			if (source != null) panel.wrapped(font, Component.translatable("packetdoctor.warnings.source", source), 0xFFFF8AD8, 12);
			panel.blank();
		}
	}

	private void buildLag() {
		LagWatcher.Snapshot s = LagWatcher.get().snapshot();
		if (!s.connected()) {
			panel.wrapped(font, Component.translatable("packetdoctor.status.not_connected"), DiagnosisScreen.MUTED, 0);
			lagEpisodes();
			return;
		}
		boolean local = s.tpsSource().equals("SINGLEPLAYER");

		// Right now: the verdict, what was noticed, and what to do.
		heading("packetdoctor.lag.ui.now");
		LagWatcher.Cause primary = s.primary();
		if (primary == null) {
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.none"), GREEN, 0);
		} else {
			Severity worst = Severity.INFO;
			for (LagWatcher.Finding f : s.findings()) if (f.cause() == primary && f.severity().ordinal() > worst.ordinal()) worst = f.severity();
			panel.wrapped(font, Component.literal(primary.verdict()).withStyle(ChatFormatting.BOLD), worst.color, 0);
		}
		for (LagWatcher.Finding f : s.findings()) {
			panel.hanging(font, "•", Component.literal(f.text()), f.severity() == Severity.INFO ? DiagnosisScreen.MUTED : f.severity().color, 8, 18);
		}
		if (primary != null) panel.wrapped(font, Component.literal(primary.advice()), DiagnosisScreen.TEXT, 8);

		// The server.
		heading(local ? "packetdoctor.lag.ui.world" : "packetdoctor.lag.ui.server");
		if (s.tps() == null) {
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.tps_unknown"), DiagnosisScreen.MUTED, 8);
		} else {
			double tps = s.tps();
			int color = tps >= s.targetTps() * 0.95 ? GREEN : tps >= s.targetTps() * 0.75 ? Severity.WARNING.color : Severity.DANGER.color;
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.tps", LagWatcher.fmt(tps), LagWatcher.fmt(s.targetTps()),
					Component.translatable("packetdoctor.lag.source." + s.tpsSource().toLowerCase(Locale.ROOT))), color, 8);
		}
		if (s.mspt() != null) {
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.mspt", LagWatcher.fmt(s.mspt()),
					s.msptMax() == null ? "-" : String.format(Locale.ROOT, "%.0f", s.msptMax())), DiagnosisScreen.MUTED, 8);
		}
		if (!s.serverCauses().isEmpty()) {
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.server_causes"), DiagnosisScreen.TEXT, 8);
			for (var c : s.serverCauses()) panel.hanging(font, "-", Component.literal(LagWatcher.causeText(c)), DiagnosisScreen.TEXT, 8, 18);
		}

		// The connection.
		heading("packetdoctor.lag.ui.connection");
		if (local) {
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.ping_local"), DiagnosisScreen.MUTED, 8);
		} else if (s.pingMs() < 0) {
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.ping_unknown"), DiagnosisScreen.MUTED, 8);
		} else {
			int color = s.pingMs() <= 150 && s.jitterMs() <= 40 && s.lostPings() == 0 ? GREEN : s.pingMs() > 600 ? Severity.DANGER.color : Severity.WARNING.color;
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.ping", s.pingMs(), s.jitterMs(), s.lostPings()), color, 8);
		}

		// The game.
		heading("packetdoctor.lag.ui.game");
		int color = s.fps() >= 30 && s.memoryPercent() < 90 ? GREEN : Severity.WARNING.color;
		panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.fps", s.fps(), s.memoryPercent()), color, 8);

		lagEpisodes();
	}

	private void lagEpisodes() {
		heading("packetdoctor.lag.ui.recent");
		List<LagWatcher.Episode> episodes = LagWatcher.get().episodes();
		if (episodes.isEmpty()) {
			panel.wrapped(font, Component.translatable("packetdoctor.lag.ui.no_episodes"), DiagnosisScreen.MUTED, 8);
			return;
		}
		for (LagWatcher.Episode e : episodes) {
			String when = CLOCK.format(Instant.ofEpochMilli(e.start()));
			String length = e.end() == 0 ? Component.translatable("packetdoctor.lag.ui.ongoing").getString() : LagWatcher.seconds(e.end() - e.start());
			panel.hanging(font, when, Component.literal(e.cause().label() + " (" + length + "): " + e.text()), DiagnosisScreen.TEXT, 8, 58);
		}
	}

	private void heading(String key) {
		panel.blank();
		panel.wrapped(font, Component.translatable(key).withStyle(ChatFormatting.BOLD), DiagnosisScreen.HEADING, 0);
	}

	private void buildLive() {
		statusLine();
		panel.blank();
		boolean wide = panel.textWidth() >= 420;
		int cTime = 0, cDir = 70, cPhase = 96, cName = wide ? 160 : 84, cSize = panel.textWidth() - 56;
		TextPanel.Columns header = panel.columns()
				.add(cTime, Component.translatable("packetdoctor.column.time"), DiagnosisScreen.HEADING)
				.add(cDir - 4, Component.translatable("packetdoctor.column.dir"), DiagnosisScreen.HEADING);
		if (wide) header.add(cPhase, Component.translatable("packetdoctor.column.phase"), DiagnosisScreen.HEADING);
		header.add(cName, Component.translatable("packetdoctor.column.packet"), DiagnosisScreen.HEADING)
				.add(cSize, Component.translatable("packetdoctor.column.size"), DiagnosisScreen.HEADING).done();

		List<PacketRecord> recent = PacketMonitor.get().log().recent(400);
		if (recent.isEmpty()) {
			panel.wrapped(font, Component.translatable("packetdoctor.live.empty"), DiagnosisScreen.MUTED, 0);
			return;
		}
		int nameWidth = cSize - cName - 6;
		for (PacketRecord r : recent) {
			Severity flag = r.flag();
			TextPanel.Columns row = panel.columns()
					.add(cTime, r.clock(), DiagnosisScreen.MUTED)
					.add(cDir, r.direction.arrow, r.direction.color);
			if (wide) row.add(cPhase, r.phase, DiagnosisScreen.MUTED);
			row.add(cName, TextPanel.fit(font, PacketNames.describe(r.id), nameWidth), flag != null ? flag.color : DiagnosisScreen.TEXT)
					.add(cSize, PacketNames.bytes(r.size()), DiagnosisScreen.MUTED)
					.done();
		}
	}

	private void buildStats() {
		PacketLog log = PacketMonitor.get().log();
		long[] t = log.totals();
		long seconds = Math.max(1, (System.currentTimeMillis() - log.sessionStart()) / 1000);
		statusLine();
		panel.wrapped(font, Component.translatable("packetdoctor.stats.summary",
				String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60),
				PacketNames.count(t[0]), PacketNames.bytes(t[2]), PacketNames.count(t[1]), PacketNames.bytes(t[3])), DiagnosisScreen.TEXT, 0);
		panel.blank();

		int w = panel.textWidth();
		int cDir = 0, cName = 14, cCount = w - 190, cBytes = w - 125, cMax = w - 60;
		panel.columns().add(cName, Component.translatable("packetdoctor.column.type"), DiagnosisScreen.HEADING)
				.add(cCount, Component.translatable("packetdoctor.column.count"), DiagnosisScreen.HEADING)
				.add(cBytes, Component.translatable("packetdoctor.column.total"), DiagnosisScreen.HEADING)
				.add(cMax, Component.translatable("packetdoctor.column.largest"), DiagnosisScreen.HEADING).done();
		List<PacketLog.TypeStats> stats = log.stats();
		stats.sort(Comparator.comparingLong((PacketLog.TypeStats s) -> s.count).reversed());
		for (int i = 0; i < Math.min(200, stats.size()); i++) {
			PacketLog.TypeStats s = stats.get(i);
			panel.columns()
					.add(cDir, s.direction.arrow, s.direction.color)
					.add(cName, TextPanel.fit(font, PacketNames.describe(s.id), cCount - cName - 6), DiagnosisScreen.TEXT)
					.add(cCount, PacketNames.count(s.count), DiagnosisScreen.TEXT)
					.add(cBytes, s.sizedCount > 0 ? PacketNames.bytes(s.bytes) : "-", DiagnosisScreen.MUTED)
					.add(cMax, s.sizedCount > 0 ? PacketNames.bytes(s.largest) : "-", DiagnosisScreen.MUTED)
					.done();
		}
		if (stats.isEmpty()) panel.wrapped(font, Component.translatable("packetdoctor.live.empty"), DiagnosisScreen.MUTED, 0);
	}

	private void buildHistory() {
		List<Diagnosis> history = PacketDoctorClient.history();
		if (history.isEmpty()) {
			panel.wrapped(font, Component.translatable("packetdoctor.history.empty"), DiagnosisScreen.MUTED, 0);
			return;
		}
		panel.wrapped(font, Component.translatable("packetdoctor.history.hint"), DiagnosisScreen.MUTED, 0);
		panel.blank();
		for (Diagnosis d : history) {
			Runnable open = () -> minecraft.gui.setScreen(new DiagnosisScreen(d, () -> new PacketDoctorScreen(parent, Tab.HISTORY)));
			Component kind = Component.translatable(d.isCrash() ? "packetdoctor.history.crash" : "packetdoctor.history.disconnect");
			Component head = Component.literal("[").append(kind).append("] " + DATE.format(Instant.ofEpochMilli(d.time())) + "  ")
					.withColor(d.severity().color & 0xFFFFFF)
					.append(Component.literal(d.headline()).withColor(0xFFFFFF));
			panel.wrapped(font, head, DiagnosisScreen.TEXT, 0, open);
			panel.wrapped(font, Component.literal(d.sourceLabel()), d.sourceKind().color, 12, open);
			panel.blank();
		}
	}

	private void buildSettings() {
		Config c = PacketDoctorClient.config();
		panel.wrapped(font, Component.translatable("packetdoctor.settings.hint"), DiagnosisScreen.MUTED, 0);
		panel.blank();
		toggle("packetdoctor.settings.toasts", c.toasts, () -> c.toasts = !c.toasts);
		toggle("packetdoctor.settings.toast_info", c.toastInfo, () -> c.toastInfo = !c.toastInfo);
		cycle("packetdoctor.settings.cooldown", c.toastCooldownSeconds + " s", new int[] {10, 30, 60, 120, 300}, c.toastCooldownSeconds,
				v -> c.toastCooldownSeconds = v);
		toggle("packetdoctor.settings.title_button", c.titleScreenButton, () -> c.titleScreenButton = !c.titleScreenButton);
		toggle("packetdoctor.settings.open_crash", c.openCrashExplanation, () -> c.openCrashExplanation = !c.openCrashExplanation);
		toggle("packetdoctor.settings.lag_toasts", c.lagToasts, () -> c.lagToasts = !c.lagToasts);
		toggle("packetdoctor.settings.measure_ping", c.measurePing, () -> c.measurePing = !c.measurePing);
		cycle("packetdoctor.settings.log_size", PacketNames.count(c.logSize), new int[] {10_000, 50_000, 100_000, 200_000}, c.logSize, v -> {
			c.logSize = v;
			PacketMonitor.get().log().resize(v);
		});
		cycle("packetdoctor.settings.keep_reports", String.valueOf(c.keepReports), new int[] {10, 30, 100}, c.keepReports, v -> {
			c.keepReports = v;
			CrashHandler.keepReports = v;
		});
		panel.blank();
		panel.wrapped(font, Component.translatable("packetdoctor.settings.folder", Reports.folder().toAbsolutePath().toString()),
				DiagnosisScreen.MUTED, 0, this::openReports);
	}

	private void toggle(String key, boolean value, Runnable flip) {
		setting(key, Component.translatable(value ? "options.on" : "options.off").withColor((value ? GREEN : 0xFFFF8A8A) & 0xFFFFFF), flip);
	}

	private void cycle(String key, String shown, int[] values, int current, IntConsumer set) {
		setting(key, Component.literal(shown).withColor(0xE3D39B), () -> {
			int next = values[0];
			for (int v : values) {
				if (v > current) {
					next = v;
					break;
				}
			}
			set.accept(next);
		});
	}

	private void setting(String key, Component value, Runnable change) {
		Runnable click = () -> {
			change.run();
			PacketDoctorClient.config().save();
			rebuild(true);
		};
		panel.wrapped(font, Component.literal("▶ ").append(Component.translatable(key)).append(": ").append(value),
				DiagnosisScreen.TEXT, 0, click);
		panel.wrapped(font, Component.translatable(key + ".desc"), DiagnosisScreen.MUTED, 12, click);
		panel.blank();
	}

	// --- Actions ----------------------------------------------------------------------

	private void export() {
		PacketMonitor m = PacketMonitor.get();
		try {
			Path file = PacketExport.write(minecraft.getUser().getName(), m.server(), m.log(), m.warnings().all(),
					PacketDoctorClient.config().keepReports);
			flash(Component.translatable("packetdoctor.status.exported", file.getFileName().toString()));
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.warn("Couldn't export the packet log", e);
			flash(Component.translatable("packetdoctor.status.export_failed"));
		}
	}

	private void openReports() {
		try {
			Files.createDirectories(Reports.reportsFolder());
			Blaze3D.openPath(Reports.reportsFolder());
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.warn("Couldn't open the reports folder", e);
		}
	}

	private void flash(Component message) {
		status = message;
		statusUntil = System.currentTimeMillis() + 4000;
	}

	// --- Screen plumbing ------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title.getString(), width / 2, 8, 0xFFFFFFFF);
		panel.render(g, font, mouseX, mouseY);
		if (System.currentTimeMillis() < statusUntil) {
			g.centeredText(font, status.getString(), width / 2, height - 38, GREEN);
		} else if (tab == Tab.LIVE && frozen) {
			g.text(font, Component.translatable("packetdoctor.live.frozen").getString(), 20, height - 38, 0xFFFFC857);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		return panel.mouseScrolled(mouseX, mouseY, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		return panel.mouseClicked(event.x(), event.y());
	}

	@Override
	public boolean isPauseScreen() {
		return false; // keep packets flowing so the live view stays live in singleplayer
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
