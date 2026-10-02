package com.packetdoctor.api;

import org.jspecify.annotations.Nullable;

/**
 * One line the server printed to its console/log.
 *
 * @param level  {@code INFO}, {@code WARN}, {@code ERROR} or {@code FATAL}
 * @param logger who printed it (often a class or mod name)
 * @param error  the attached exception, shortened, if there was one
 * @param mod    the mod it came from, as "Name (id)", if it could be told
 */
public record ConsoleLine(long time, String level, String logger, String thread, String message, @Nullable String error,
		@Nullable String mod) {
}
