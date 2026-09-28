package com.packetdoctor.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The most recent packets (a fixed-size ring buffer) plus running totals per packet
 * type for the current session. Written from the network thread and the game thread,
 * read by the GUI, so everything is synchronized; each call is tiny.
 */
public final class PacketLog {
	/** Totals for one packet type in one direction. */
	public static final class TypeStats {
		public final Direction direction;
		public final String id;
		public long count;
		/** Bytes of the packets whose size is known. */
		public long bytes;
		public long sizedCount;
		public int largest;

		TypeStats(Direction direction, String id) {
			this.direction = direction;
			this.id = id;
		}

		TypeStats copy() {
			TypeStats c = new TypeStats(direction, id);
			c.count = count;
			c.bytes = bytes;
			c.sizedCount = sizedCount;
			c.largest = largest;
			return c;
		}
	}

	private PacketRecord[] ring;
	private int next;
	private int filled;
	private final Map<String, TypeStats> stats = new HashMap<>();
	private long sessionStart = System.currentTimeMillis();
	private long packetsIn;
	private long packetsOut;
	private long bytesIn;
	private long bytesOut;

	public PacketLog(int capacity) {
		ring = new PacketRecord[capacity];
	}

	public synchronized void resize(int capacity) {
		if (capacity == ring.length) return;
		List<PacketRecord> keep = recent(capacity);
		ring = new PacketRecord[capacity];
		next = 0;
		filled = 0;
		for (int i = keep.size() - 1; i >= 0; i--) append(keep.get(i));
	}

	public synchronized void reset() {
		java.util.Arrays.fill(ring, null);
		next = 0;
		filled = 0;
		stats.clear();
		sessionStart = System.currentTimeMillis();
		packetsIn = packetsOut = bytesIn = bytesOut = 0;
	}

	synchronized void add(PacketRecord record) {
		append(record);
		TypeStats s = stats.computeIfAbsent(record.direction + record.id, k -> new TypeStats(record.direction, record.id));
		s.count++;
		if (record.direction == Direction.IN) packetsIn++;
		else packetsOut++;
		if (record.size() >= 0) addSize(s, record.direction, record.size());
	}

	/** Called when the size of an already-logged packet becomes known. */
	synchronized void sized(PacketRecord record, int size) {
		record.setSize(size);
		TypeStats s = stats.get(record.direction + record.id);
		if (s != null) addSize(s, record.direction, size);
	}

	private void addSize(TypeStats s, Direction direction, int size) {
		s.bytes += size;
		s.sizedCount++;
		s.largest = Math.max(s.largest, size);
		if (direction == Direction.IN) bytesIn += size;
		else bytesOut += size;
	}

	private void append(PacketRecord record) {
		ring[next] = record;
		next = (next + 1) % ring.length;
		if (filled < ring.length) filled++;
	}

	/** Up to {@code max} packets, newest first. */
	public synchronized List<PacketRecord> recent(int max) {
		int n = Math.min(max, filled);
		List<PacketRecord> out = new ArrayList<>(n);
		for (int i = 1; i <= n; i++) {
			out.add(ring[Math.floorMod(next - i, ring.length)]);
		}
		return out;
	}

	/** Every remembered packet, oldest first. */
	public synchronized List<PacketRecord> all() {
		List<PacketRecord> out = recent(filled);
		java.util.Collections.reverse(out);
		return out;
	}

	public synchronized List<TypeStats> stats() {
		List<TypeStats> out = new ArrayList<>(stats.size());
		for (TypeStats s : stats.values()) out.add(s.copy());
		return out;
	}

	public synchronized long sessionStart() {
		return sessionStart;
	}

	public synchronized long[] totals() {
		return new long[] {packetsIn, packetsOut, bytesIn, bytesOut};
	}
}
