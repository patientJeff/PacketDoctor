package com.packetdoctor.api;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Packet Doctor's explanation of a server crash.
 *
 * @param source     who is most likely responsible, e.g. "A mod: Sodium (sodium)"
 * @param reportFile Packet Doctor's text report, if it was saved
 */
public record CrashInfo(long time, String headline, String summary, String source, List<String> tips, @Nullable String reportFile) {
}
