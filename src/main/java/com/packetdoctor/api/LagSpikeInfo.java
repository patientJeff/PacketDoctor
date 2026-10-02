package com.packetdoctor.api;

import java.util.List;

/**
 * A lag spike: one or more slow ticks in a row, with what the server was doing.
 *
 * @param time          when it started (epoch millis)
 * @param durationMs    how long the slow ticks took in total
 * @param ticksLost     roughly how many ticks the server fell behind
 * @param slowTicks     how many slow ticks it covered
 * @param summary       one or two plain sentences, e.g. "The server froze for 1.8 s ... generating new terrain"
 * @param causes        what the time went to, biggest first (empty if nothing could be sampled)
 * @param consoleLines  warnings and errors the console printed during it
 * @param topEntities   the most common entity types at the time, e.g. "minecraft:zombie x320"
 */
public record LagSpikeInfo(long time, long durationMs, int ticksLost, int slowTicks, String summary, List<LagCause> causes,
		List<String> consoleLines, List<String> topEntities) {
}
