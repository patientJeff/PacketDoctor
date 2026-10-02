package com.packetdoctor.server.perf;

import com.packetdoctor.Tr;

/**
 * What the server was busy with during a slow tick. Each has a plain label and advice:
 * {@code <key>.label} and {@code <key>.advice} in the language file.
 */
public enum LagKind {
	WORLDGEN("packetdoctor.lag.worldgen"),
	CHUNK_LOADING("packetdoctor.lag.chunk_loading"),
	SAVING("packetdoctor.lag.saving"),
	ENTITIES("packetdoctor.lag.entities"),
	MOB_AI("packetdoctor.lag.mob_ai"),
	PATHFINDING("packetdoctor.lag.pathfinding"),
	SPAWNING("packetdoctor.lag.spawning"),
	ENTITY_TRACKING("packetdoctor.lag.entity_tracking"),
	BLOCK_ENTITIES("packetdoctor.lag.block_entities"),
	HOPPERS("packetdoctor.lag.hoppers"),
	REDSTONE("packetdoctor.lag.redstone"),
	SCHEDULED_TICKS("packetdoctor.lag.scheduled_ticks"),
	CHUNK_TICKS("packetdoctor.lag.chunk_ticks"),
	EXPLOSIONS("packetdoctor.lag.explosions"),
	LIGHTING("packetdoctor.lag.lighting"),
	COMMANDS("packetdoctor.lag.commands"),
	PLAYER_PACKETS("packetdoctor.lag.player_packets"),
	PLAYERS("packetdoctor.lag.players"),
	MOD("packetdoctor.lag.mod"),
	GC("packetdoctor.lag.gc"),
	WAITING("packetdoctor.lag.waiting"),
	OTHER("packetdoctor.lag.other");

	private final String key;

	LagKind(String key) {
		this.key = key;
	}

	public String label() {
		return Tr.t(key + ".label");
	}

	/** What an admin can do about it; may mention {@code %s} = the mod, if one was found. */
	public String advice(String mod) {
		return Tr.t(key + ".advice", mod);
	}
}
