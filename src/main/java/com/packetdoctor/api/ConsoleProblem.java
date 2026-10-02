package com.packetdoctor.api;

import org.jspecify.annotations.Nullable;

/**
 * Warnings or errors from the console, grouped: the same problem printed 500 times is one
 * entry with a count, explained in plain words.
 *
 * @param id          the kind of problem, e.g. {@code chunk_corrupt}, {@code save_failed},
 *                    {@code tick_error}, {@code mixin}, {@code datapack}; {@code error} or
 *                    {@code warning} when it isn't a known kind
 * @param severity    {@code INFO}, {@code WARNING} or {@code DANGER}
 * @param explanation what it means
 * @param advice      what to do about it
 * @param mod         the mod it came from, as "Name (id)", if it could be told
 * @param example     one of the actual lines, word for word (shortened)
 */
public record ConsoleProblem(String id, String severity, String title, String explanation, String advice, @Nullable String mod,
		String logger, String example, int count, long firstSeen, long lastSeen) {
}
