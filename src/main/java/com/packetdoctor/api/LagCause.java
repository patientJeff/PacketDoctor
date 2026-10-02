package com.packetdoctor.api;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One thing a slow server spent its time on, found by sampling what the server thread was
 * doing during slow ticks.
 *
 * @param kind     what it was busy with, e.g. {@code WORLDGEN}, {@code ENTITIES}, {@code HOPPERS},
 *                 {@code MOD}, {@code GC} (garbage collection) or {@code WAITING}
 * @param label    the same in plain words, e.g. "Generating new terrain"
 * @param percent  share of the slow time, 0-100
 * @param mod      the mod whose code was running, as "Name (id)", if one stood out
 * @param examples the most common things involved, e.g. entity or block names ("Villager", "Hopper")
 * @param advice   what an admin can do about it
 */
public record LagCause(String kind, String label, double percent, @Nullable String mod, List<String> examples, String advice) {
}
