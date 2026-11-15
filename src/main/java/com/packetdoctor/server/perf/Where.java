package com.packetdoctor.server.perf;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * The block entity or entity a lag sample landed in, and where it was.
 *
 * @param entity true for an entity, false for a block entity
 * @param type   its registry id, e.g. {@code minecraft:hopper}
 * @param dim    the dimension id, e.g. {@code minecraft:overworld}
 * @param id     an entity's UUID; null for block entities (their position identifies them)
 * @param player the nearest online player, if one is close (see {@link PlayerSpots})
 */
public record Where(boolean entity, String type, String dim, int x, int y, int z, @Nullable String id, @Nullable String player) {
	public int chunkX() {
		return x >> 4;
	}

	public int chunkZ() {
		return z >> 4;
	}

	/** One chunk: "minecraft:overworld 12 -4". */
	public String spotKey() {
		return dim + " " + chunkX() + " " + chunkZ();
	}

	/** One block entity (by position) or entity (by UUID). */
	public String singleKey() {
		return entity ? "E " + id : "B " + dim + " " + x + " " + y + " " + z;
	}

	/** "B minecraft:hopper" or "E minecraft:villager". */
	public String thingKey() {
		return (entity ? "E " : "B ") + type;
	}

	public String namespace() {
		int colon = type.indexOf(':');
		return colon > 0 ? type.substring(0, colon) : "minecraft";
	}

	/** "the Overworld", "the Nether", "the End" or the id for modded dimensions. */
	public String dimName() {
		return dimName(dim);
	}

	public static String dimName(String dim) {
		return switch (dim) {
			case "minecraft:overworld" -> "the Overworld";
			case "minecraft:the_nether" -> "the Nether";
			case "minecraft:the_end" -> "the End";
			default -> dim;
		};
	}

	/** "x 120, y 64, z -30 in the Overworld". */
	public String place() {
		return String.format(Locale.ROOT, "x %d, y %d, z %d in %s", x, y, z, dimName());
	}

	/** A command that takes an operator there. */
	public String teleport() {
		return "/execute in " + dim + " run tp @s " + x + " " + (y + 1) + " " + z;
	}
}
