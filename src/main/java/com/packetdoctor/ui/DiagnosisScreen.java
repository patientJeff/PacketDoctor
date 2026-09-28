package com.packetdoctor.ui;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.diagnose.Diagnosis;
import com.packetdoctor.diagnose.Reports;
import com.packetdoctor.net.Severity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.Blaze3D;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

/**
 * Shows one {@link Diagnosis}: the headline, what happened, where it came from, what to
 * try, and (on request) the technical details. Opened from the disconnect screen, from
 * the history tab, and automatically after a crash.
 */
public final class DiagnosisScreen extends Screen {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	static final int HEADING = 0xFFA9B8D0;
	static final int TEXT = 0xFFE8E8E8;
	static final int MUTED = 0xFF9A9A9A;

	private final Diagnosis diagnosis;
	private final Supplier<Screen> back;
	private final TextPanel panel = new TextPanel();
	private boolean showTechnical;
	private Component status = Component.empty();
	private long statusUntil;

	public DiagnosisScreen(Diagnosis diagnosis, Supplier<Screen> back) {
		super(Component.translatable(diagnosis.isCrash() ? "packetdoctor.diagnosis.crash_title" : "packetdoctor.diagnosis.disconnect_title"));
		this.diagnosis = diagnosis;
		this.back = back;
	}

	@Override
	protected void init() {
		double scroll = panel.scroll();
		panel.setBounds(16, 28, width - 32, height - 28 - 34);
		build();
		panel.setScroll(scroll);

		int bw = Math.min(120, (width - 40) / 4);
		int gap = 4;
		int x = (width - (bw * 4 + gap * 3)) / 2;
		int y = height - 27;
		addRenderableWidget(Button.builder(technicalLabel(), b -> {
			showTechnical = !showTechnical;
			b.setMessage(technicalLabel());
			double keep = panel.scroll();
			build();
			panel.setScroll(keep);
		}).bounds(x, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("packetdoctor.button.copy"), b -> {
			minecraft.keyboardHandler.setClipboard(Reports.render(diagnosis));
			flash(Component.translatable("packetdoctor.status.copied"));
		}).bounds(x + (bw + gap), y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("packetdoctor.button.open_folder"), b -> openReports())
				.bounds(x + 2 * (bw + gap), y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(x + 3 * (bw + gap), y, bw, 20).build());
	}

	private Component technicalLabel() {
		return Component.translatable(showTechnical ? "packetdoctor.button.hide_technical" : "packetdoctor.button.show_technical");
	}

	private void build() {
		Diagnosis d = diagnosis;
		panel.clear();
		panel.wrapped(font, Component.literal(d.headline()).withStyle(ChatFormatting.BOLD), d.severity().color, 0);
		panel.wrapped(font, Component.literal(d.severity().label() + "  ·  " + TIME.format(Instant.ofEpochMilli(d.time()))), MUTED, 0);

		heading("packetdoctor.section.what");
		panel.wrapped(font, Component.literal(d.summary()), TEXT, 8);

		heading("packetdoctor.section.source");
		panel.hanging(font, "●", Component.literal(d.sourceLabel()).withStyle(ChatFormatting.BOLD), d.sourceKind().color, 8, 18);
		if (!d.sourceNote().isBlank()) panel.wrapped(font, Component.literal(d.sourceNote()), MUTED, 18);

		if (!d.tips().isEmpty()) {
			heading("packetdoctor.section.tips");
			for (int i = 0; i < d.tips().size(); i++) {
				panel.hanging(font, (i + 1) + ".", Component.literal(d.tips().get(i)), TEXT, 8, 22);
			}
		}

		if (!d.warnings().isEmpty()) {
			heading("packetdoctor.section.warnings");
			for (String w : d.warnings()) panel.hanging(font, "-", Component.literal(w), colorFor(w), 8, 18);
		}

		if (!d.original().isBlank()) {
			heading("packetdoctor.section.original");
			panel.wrapped(font, Component.literal("\"" + d.original() + "\""), MUTED, 8);
		}

		if (d.reportFile() != null) {
			panel.blank();
			panel.wrapped(font, Component.translatable("packetdoctor.saved_to", d.reportFile()).withStyle(ChatFormatting.UNDERLINE),
					MUTED, 0, this::openReports);
		}

		if (showTechnical && !d.technical().isBlank()) {
			heading("packetdoctor.section.technical");
			for (String line : d.technical().split("\n")) {
				if (line.isEmpty()) panel.blank();
				else panel.wrapped(font, Component.literal(line.replace("\t", "    ")), MUTED, 8);
			}
		}
	}

	private void heading(String key) {
		panel.blank();
		panel.wrapped(font, Component.translatable(key).withStyle(ChatFormatting.BOLD), HEADING, 0);
	}

	/** Colour of a report line such as "[Danger] 12:03:01 ..." (possibly after a "[Server]" prefix). */
	static int colorFor(String warningLine) {
		String head = warningLine.length() > 40 ? warningLine.substring(0, 40) : warningLine;
		for (Severity s : Severity.values()) {
			if (head.contains("[" + s.label() + "]")) return s.color;
		}
		return TEXT;
	}

	private void openReports() {
		try {
			Files.createDirectories(Reports.reportsFolder());
			Blaze3D.openPath(Reports.reportsFolder());
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.warn("Couldn't open the reports folder", e);
			flash(Component.literal(Reports.reportsFolder().toString()));
		}
	}

	private void flash(Component message) {
		status = message;
		statusUntil = System.currentTimeMillis() + 2500;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title.getString(), width / 2, 10, 0xFFFFFFFF);
		panel.render(g, font, mouseX, mouseY);
		if (System.currentTimeMillis() < statusUntil) {
			g.centeredText(font, status.getString(), width / 2, height - 38, 0xFF9BE39B);
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
	public void onClose() {
		minecraft.gui.setScreen(back.get());
	}
}
