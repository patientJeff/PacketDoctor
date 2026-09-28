package com.packetdoctor.net;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One packet in the live log. The size is filled in by the encoder/decoder hooks and
 * stays -1 for in-memory (singleplayer) connections, where packets are never serialized.
 */
public final class PacketRecord {
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

	public final long time;
	public final Direction direction;
	public final String phase;
	/** Full packet id, e.g. {@code minecraft:level_chunk_with_light}. */
	public final String id;
	private volatile int size;
	private volatile @Nullable Severity flag;

	PacketRecord(long time, Direction direction, String phase, String id, int size) {
		this.time = time;
		this.direction = direction;
		this.phase = phase;
		this.id = id;
		this.size = size;
	}

	public int size() {
		return size;
	}

	void setSize(int size) {
		this.size = size;
	}

	public @Nullable Severity flag() {
		return flag;
	}

	void flag(Severity severity) {
		Severity current = flag;
		if (current == null || severity.atLeast(current)) flag = severity;
	}

	public String clock() {
		return CLOCK.format(Instant.ofEpochMilli(time));
	}

	public String oneLine() {
		return clock() + " " + (direction == Direction.IN ? "IN " : "OUT") + " " + phase + " " + id
				+ (size >= 0 ? " " + PacketNames.bytes(size) : "")
				+ (flag != null ? " [" + flag.label() + "]" : "");
	}
}
