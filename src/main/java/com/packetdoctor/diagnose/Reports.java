package com.packetdoctor.diagnose;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.Tr;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Saves explanations as readable text files in {@code <game or server folder>/packetdoctor/reports},
 * and carries crash explanations over to the next start via JSON files.
 */
public final class Reports {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter HUMAN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	/** The client's crash, shown on the title screen next time. */
	public static final String CLIENT_CRASH = "last-crash.json";
	/** The dedicated server's crash, reported in the console and through the API next time. */
	public static final String SERVER_CRASH = "last-server-crash.json";

	private Reports() {
	}

	public static Path folder() {
		return FabricLoader.getInstance().getGameDir().resolve(PacketDoctor.MOD_ID);
	}

	public static Path reportsFolder() {
		return folder().resolve("reports");
	}

	/** Writes the text report and returns the diagnosis with its file path filled in. */
	public static Diagnosis save(Diagnosis d, int keep) {
		try {
			Path dir = reportsFolder();
			Files.createDirectories(dir);
			Path file = dir.resolve(d.kind() + "-" + FILE_TIME.format(Instant.ofEpochMilli(d.time())) + ".txt");
			Diagnosis saved = d.withReportFile(file.toAbsolutePath().toString());
			Files.writeString(file, render(saved));
			prune(keep);
			return saved;
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.warn("Couldn't save report", e);
			return d;
		}
	}

	/** Deletes the oldest report files beyond {@code keep}. */
	public static void prune(int keep) {
		Path dir = reportsFolder();
		if (!Files.isDirectory(dir)) return;
		try {
			List<Path> files;
			try (Stream<Path> list = Files.list(dir)) {
				files = new ArrayList<>(list.filter(p -> p.getFileName().toString().endsWith(".txt")).toList());
			}
			if (files.size() <= keep) return;
			files.sort(Comparator.comparing(p -> {
				try {
					return Files.getLastModifiedTime(p).toMillis();
				} catch (IOException e) {
					return 0L;
				}
			}));
			for (int i = 0; i < files.size() - keep; i++) Files.deleteIfExists(files.get(i));
		} catch (IOException e) {
			PacketDoctor.LOGGER.warn("Couldn't clean up old reports", e);
		}
	}

	public static void savePendingCrash(String fileName, Diagnosis d) {
		try {
			Files.createDirectories(folder());
			try (Writer w = Files.newBufferedWriter(folder().resolve(fileName))) {
				GSON.toJson(d, w);
			}
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.warn("Couldn't save crash explanation", e);
		}
	}

	/** A crash explanation saved by the previous run, if there is one. */
	public static @Nullable Diagnosis loadPendingCrash(String fileName) {
		Path file = folder().resolve(fileName);
		if (!Files.exists(file)) return null;
		try (Reader r = Files.newBufferedReader(file)) {
			Diagnosis d = GSON.fromJson(r, Diagnosis.class);
			return d != null && d.headline() != null && d.tips() != null ? d : null;
		} catch (IOException | RuntimeException e) {
			PacketDoctor.LOGGER.warn("Couldn't read {}", file, e);
			return null;
		}
	}

	public static void clearPendingCrash(String fileName) {
		try {
			Files.deleteIfExists(folder().resolve(fileName));
		} catch (IOException e) {
			PacketDoctor.LOGGER.warn("Couldn't delete {}", fileName, e);
		}
	}

	/** The full plain-text report, for the file and the clipboard. */
	public static String render(Diagnosis d) {
		StringBuilder sb = new StringBuilder();
		sb.append("==== ").append(Tr.t(d.isCrash() ? "packetdoctor.report.crash_title" : "packetdoctor.report.disconnect_title")).append(" ====\n");
		sb.append(Tr.t("packetdoctor.report.time", HUMAN_TIME.format(Instant.ofEpochMilli(d.time())))).append('\n');
		sb.append(Tr.t("packetdoctor.report.version", PacketDoctor.version())).append("\n\n");

		sb.append(d.headline()).append("\n\n");
		sb.append(Tr.t("packetdoctor.section.what")).append('\n').append(d.summary()).append("\n\n");
		sb.append(Tr.t("packetdoctor.section.source")).append('\n').append(d.sourceLabel()).append('\n');
		if (!d.sourceNote().isBlank()) sb.append(d.sourceNote()).append('\n');
		sb.append('\n');
		if (!d.tips().isEmpty()) {
			sb.append(Tr.t("packetdoctor.section.tips")).append('\n');
			for (int i = 0; i < d.tips().size(); i++) sb.append(i + 1).append(". ").append(d.tips().get(i)).append('\n');
			sb.append('\n');
		}
		if (!d.warnings().isEmpty()) {
			sb.append(Tr.t("packetdoctor.section.warnings")).append('\n');
			for (String w : d.warnings()) sb.append("- ").append(w).append('\n');
			sb.append('\n');
		}
		if (!d.original().isBlank()) sb.append(Tr.t("packetdoctor.section.original")).append('\n').append(d.original()).append("\n\n");
		if (!d.technical().isBlank()) sb.append(Tr.t("packetdoctor.section.technical")).append('\n').append(d.technical()).append('\n');
		return sb.toString();
	}
}
