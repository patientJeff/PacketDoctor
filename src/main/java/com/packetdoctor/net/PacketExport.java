package com.packetdoctor.net;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.diagnose.Reports;
import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Writes a packet log to a text file for server admins and modders: a summary, the
 * warnings, totals per packet type, then every remembered packet, oldest first.
 * Directions are from the player's point of view ("to server" = sent by the player).
 */
public final class PacketExport {
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter HUMAN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

	private PacketExport() {
	}

	/**
	 * @param who     whose connection, e.g. "Steve" or "my connection"; used in the file name
	 * @param server  server address or name, if known
	 */
	public static Path write(String who, @Nullable String server, PacketLog log, List<Warning> warnings, int keepReports) throws IOException {
		Path dir = Reports.reportsFolder();
		Files.createDirectories(dir);
		String safe = who.replaceAll("[^A-Za-z0-9_.-]", "_");
		Path file = dir.resolve("packets-" + safe + "-" + FILE_TIME.format(Instant.now()) + ".txt");

		List<PacketRecord> packets = log.all();
		long[] totals = log.totals();
		try (BufferedWriter w = Files.newBufferedWriter(file)) {
			w.write("==== Packet Doctor packet log ====\n");
			w.write("Exported: " + HUMAN_TIME.format(Instant.now()) + "   (Packet Doctor " + PacketDoctor.version() + ")\n");
			w.write("Connection: " + who + "\n");
			if (server != null && !server.isBlank()) w.write("Server: " + server + "\n");
			long start = log.sessionStart();
			w.write("Session started: " + HUMAN_TIME.format(Instant.ofEpochMilli(start))
					+ String.format(Locale.ROOT, " (%d s ago)%n", (System.currentTimeMillis() - start) / 1000));
			w.write("Totals: to player " + PacketNames.count(totals[0]) + " packets (" + PacketNames.bytes(totals[2]) + "), to server "
					+ PacketNames.count(totals[1]) + " packets (" + PacketNames.bytes(totals[3]) + ")\n");
			if (!packets.isEmpty()) {
				PacketRecord first = packets.getFirst();
				PacketRecord last = packets.getLast();
				w.write(String.format(Locale.ROOT, "This file holds the last %s packets, %s to %s (%.1f s)%n",
						PacketNames.count(packets.size()), first.clock(), last.clock(), (last.time - first.time) / 1000.0));
			}
			w.write("Sizes are uncompressed; '-' means unknown (singleplayer packets are never encoded).\n\n");

			w.write("WARNINGS (" + warnings.size() + ")\n");
			for (Warning x : warnings) w.write("- " + x.oneLine() + "\n");
			w.write("\n");

			w.write("PACKET TYPES\n");
			w.write(String.format(Locale.ROOT, "%-10s %10s %12s %10s  %s%n", "Direction", "Count", "Total", "Largest", "Packet"));
			List<PacketLog.TypeStats> stats = log.stats();
			stats.sort(Comparator.comparingLong((PacketLog.TypeStats s) -> s.count).reversed());
			for (PacketLog.TypeStats s : stats) {
				w.write(String.format(Locale.ROOT, "%-10s %10s %12s %10s  %s%n", dir(s.direction), PacketNames.count(s.count),
						s.sizedCount > 0 ? PacketNames.bytes(s.bytes) : "-", s.sizedCount > 0 ? PacketNames.bytes(s.largest) : "-", s.id));
			}
			w.write("\n");

			w.write("PACKETS (oldest first)\n");
			w.write(String.format(Locale.ROOT, "%-12s %-10s %-14s %10s %-8s %s%n", "Time", "Direction", "Phase", "Size", "Flag", "Packet"));
			for (PacketRecord r : packets) {
				Severity flag = r.flag();
				w.write(String.format(Locale.ROOT, "%-12s %-10s %-14s %10s %-8s %s%n", r.clock(), dir(r.direction), r.phase,
						PacketNames.bytes(r.size()), flag == null ? "" : flag.name(), r.id));
			}
		}
		Reports.prune(keepReports);
		return file;
	}

	private static String dir(Direction d) {
		return d == Direction.IN ? "to player" : "to server";
	}
}
