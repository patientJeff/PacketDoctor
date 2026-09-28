package com.packetdoctor.diagnose;

import com.packetdoctor.net.Severity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A plain-language explanation of a disconnect or crash. Plain data only, so it can be
 * written to disk as JSON (a crash is explained on the next launch) and as a text report.
 *
 * @param kind            "disconnect" or "crash"
 * @param headline        one sentence, e.g. "The server kicked you for sending too many packets"
 * @param summary         what happened, in a few plain sentences
 * @param sourceKind      broad category of who is responsible
 * @param source          the specific culprit, e.g. "Sodium (sodium)" or "play.example.net"
 * @param sourceNote      why we think so, and how sure we are
 * @param tips            things to try, most useful first
 * @param warnings        warnings seen shortly before, one line each
 * @param original        the game's own message, word for word
 * @param technical       details for modders and server admins
 * @param reportFile      where the text report was saved, if it was
 */
public record Diagnosis(
		String kind,
		long time,
		Severity severity,
		String headline,
		String summary,
		SourceKind sourceKind,
		@Nullable String source,
		String sourceNote,
		List<String> tips,
		List<String> warnings,
		String original,
		String technical,
		@Nullable String reportFile) {

	public Diagnosis withReportFile(@Nullable String file) {
		return new Diagnosis(kind, time, severity, headline, summary, sourceKind, source, sourceNote, tips, warnings,
				original, technical, file);
	}

	public boolean isCrash() {
		return "crash".equals(kind);
	}

	/** Who is responsible, as one label: "A mod: Sodium (sodium)". */
	public String sourceLabel() {
		return source == null || source.isBlank() ? sourceKind.label() : sourceKind.label() + ": " + source;
	}

	static Builder builder(String kind) {
		return new Builder(kind);
	}

	/** Fluent builder used by the diagnosers. */
	static final class Builder {
		private final String kind;
		private Severity severity = Severity.WARNING;
		private String headline = "";
		private String summary = "";
		private SourceKind sourceKind = SourceKind.UNKNOWN;
		private @Nullable String source;
		private String sourceNote = "";
		private final List<String> tips = new ArrayList<>();
		private final List<String> warnings = new ArrayList<>();
		private String original = "";
		private String technical = "";

		private Builder(String kind) {
			this.kind = kind;
		}

		Builder severity(Severity severity) {
			this.severity = severity;
			return this;
		}

		Builder headline(String headline) {
			this.headline = headline;
			return this;
		}

		Builder summary(String summary) {
			this.summary = summary;
			return this;
		}

		Builder source(SourceKind kind, @Nullable String source, String note) {
			this.sourceKind = kind;
			this.source = source;
			this.sourceNote = note;
			return this;
		}

		Builder tip(String tip) {
			if (!tips.contains(tip)) tips.add(tip);
			return this;
		}

		Builder tipFirst(String tip) {
			tips.remove(tip);
			tips.addFirst(tip);
			return this;
		}

		Builder warnings(List<String> lines) {
			warnings.addAll(lines);
			return this;
		}

		Builder original(String original) {
			this.original = original;
			return this;
		}

		Builder technical(String technical) {
			this.technical = technical;
			return this;
		}

		SourceKind sourceKind() {
			return sourceKind;
		}

		String summary() {
			return summary;
		}

		String sourceNote() {
			return sourceNote;
		}

		Diagnosis build() {
			return new Diagnosis(kind, System.currentTimeMillis(), severity, headline, summary, sourceKind, source,
					sourceNote, List.copyOf(tips), List.copyOf(warnings), original, technical, null);
		}
	}
}
