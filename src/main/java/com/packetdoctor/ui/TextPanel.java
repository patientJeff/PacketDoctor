package com.packetdoctor.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A scrollable box of text rows. Rows can have several columns and can be clickable.
 * Text is wrapped to the panel width when it's added, so the owning screen rebuilds the
 * content whenever its size changes (vanilla re-runs {@code init()} on resize anyway).
 */
public final class TextPanel {
	public static final int ROW = 11;
	private static final int PAD = 6;
	private static final int SCROLLBAR = 6;

	private record Seg(int dx, FormattedCharSequence text, int color) {
	}

	private record Row(List<Seg> segs, @Nullable Runnable click) {
	}

	private final List<Row> rows = new ArrayList<>();
	private int x;
	private int y;
	private int w;
	private int h;
	private double scroll;

	public void setBounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = Math.max(60, w);
		this.h = Math.max(30, h);
	}

	/** Width available for text inside the padding and scrollbar. */
	public int textWidth() {
		return w - PAD * 2 - SCROLLBAR;
	}

	public void clear() {
		rows.clear();
	}

	public void blank() {
		rows.add(new Row(List.of(), null));
	}

	public void wrapped(Font font, Component text, int color, int indent) {
		wrapped(font, text, color, indent, null);
	}

	public void wrapped(Font font, Component text, int color, int indent, @Nullable Runnable click) {
		for (FormattedCharSequence line : font.split(text, Math.max(40, textWidth() - indent))) {
			rows.add(new Row(List.of(new Seg(indent, line, color)), click));
		}
	}

	/** A bullet or number with the text wrapped beside it, e.g. "1.  Update the mod...". */
	public void hanging(Font font, String bullet, Component text, int color, int indent, int hang) {
		List<FormattedCharSequence> lines = font.split(text, Math.max(40, textWidth() - hang));
		for (int i = 0; i < lines.size(); i++) {
			List<Seg> segs = new ArrayList<>(2);
			if (i == 0) segs.add(new Seg(indent, Component.literal(bullet).getVisualOrderText(), color));
			segs.add(new Seg(hang, lines.get(i), color));
			rows.add(new Row(segs, null));
		}
	}

	/** Starts a row made of columns. */
	public Columns columns() {
		return new Columns();
	}

	public final class Columns {
		private final List<Seg> segs = new ArrayList<>();

		public Columns add(int dx, Component text, int color) {
			segs.add(new Seg(dx, text.getVisualOrderText(), color));
			return this;
		}

		public Columns add(int dx, String text, int color) {
			return add(dx, Component.literal(text), color);
		}

		public void done() {
			done(null);
		}

		public void done(@Nullable Runnable click) {
			rows.add(new Row(List.copyOf(segs), click));
		}
	}

	public double scroll() {
		return scroll;
	}

	public void setScroll(double scroll) {
		this.scroll = scroll;
		clampScroll();
	}

	private int contentHeight() {
		return rows.size() * ROW + PAD;
	}

	private double maxScroll() {
		return Math.max(0, contentHeight() - h);
	}

	private void clampScroll() {
		scroll = Math.clamp(scroll, 0, maxScroll());
	}

	private boolean inside(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		clampScroll();
		g.fill(x, y, x + w, y + h, 0xA0000000);
		g.outline(x, y, w, h, 0x40FFFFFF);
		g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int first = Math.max(0, (int) (scroll / ROW) - 1);
		boolean hoverPanel = inside(mouseX, mouseY);
		for (int i = first; i < rows.size(); i++) {
			int ry = y + PAD / 2 + 1 + i * ROW - (int) scroll;
			if (ry > y + h) break;
			Row row = rows.get(i);
			if (row.click() != null && hoverPanel && mouseY >= ry - 1 && mouseY < ry + ROW - 1) {
				g.fill(x + 2, ry - 1, x + w - SCROLLBAR - 2, ry + ROW - 1, 0x30FFFFFF);
			}
			for (Seg s : row.segs()) g.text(font, s.text(), x + PAD + s.dx(), ry, s.color());
		}
		g.disableScissor();

		double max = maxScroll();
		if (max > 0) {
			int barH = Math.max(16, (int) ((long) h * h / contentHeight()));
			int barY = y + (int) ((h - barH) * (scroll / max));
			g.fill(x + w - SCROLLBAR + 1, y + 1, x + w - 1, y + h - 1, 0x30FFFFFF);
			g.fill(x + w - SCROLLBAR + 1, barY, x + w - 1, barY + barH, 0xB0FFFFFF);
		}
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!inside(mx, my)) return false;
		scroll -= amount * ROW * 3;
		clampScroll();
		return true;
	}

	public boolean mouseClicked(double mx, double my) {
		if (!inside(mx, my)) return false;
		int index = (int) ((my - y - PAD / 2.0 - 1 + scroll + 1) / ROW);
		if (index < 0 || index >= rows.size()) return false;
		Runnable click = rows.get(index).click();
		if (click == null) return false;
		click.run();
		return true;
	}

	/** Cuts {@code text} to fit {@code maxWidth} pixels, adding "..." if needed. */
	public static String fit(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		String dots = "...";
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) + font.width(dots) > maxWidth) end--;
		return text.substring(0, end) + dots;
	}
}
