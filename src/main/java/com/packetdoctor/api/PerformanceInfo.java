package com.packetdoctor.api;

import java.util.List;

/**
 * How the server is running right now.
 *
 * @param tps10s        ticks per second over the last 10 seconds (the target is {@code targetTps})
 * @param tps1m         over the last minute
 * @param tps5m         over the last 5 minutes
 * @param msptAverage   average milliseconds per tick over the last minute (50 = full at 20 TPS)
 * @param msptMax       slowest tick in the last minute, in milliseconds
 * @param overloaded    true while TPS has stayed low for a while
 * @param causes        what slow ticks in the last minute were spent on, biggest first
 * @param loadedChunks  chunks loaded in all dimensions
 * @param entities      entities in all dimensions
 * @param topEntities   the most common entity types, e.g. "minecraft:item x812"
 */
public record PerformanceInfo(long time, double tps10s, double tps1m, double tps5m, double targetTps, double msptAverage,
		double msptMax, boolean overloaded, List<LagCause> causes, int loadedChunks, int entities, List<String> topEntities,
		int players) {
}
