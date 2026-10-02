package com.packetdoctor.api;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Packet Doctor's explanation of a server crash, or of the server stopping without
 * shutting down properly (found from the logs on the next start).
 *
 * @param kind          {@code CRASH} (a normal crash report), {@code WATCHDOG} (a tick took too
 *                      long and the server was stopped), {@code OUT_OF_MEMORY}, {@code JVM_CRASH}
 *                      (Java itself crashed) or {@code STOPPED_UNEXPECTEDLY} (killed, or the
 *                      machine went down, with no crash report)
 * @param source        who is most likely responsible, e.g. "A mod: Lithium (lithium)"
 * @param consoleBefore warnings and errors the console printed shortly before
 * @param reportFile    Packet Doctor's text report, if it was saved
 */
public record CrashInfo(long time, String kind, String headline, String summary, String source, List<String> tips,
		List<String> consoleBefore, @Nullable String reportFile) {
}
