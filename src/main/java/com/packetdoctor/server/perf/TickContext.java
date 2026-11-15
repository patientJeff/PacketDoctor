package com.packetdoctor.server.perf;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Which block entity or entity the server thread is ticking right now, so a lag sample
 * can say not just "block entities" but "the hopper at 120, 64, -30".
 *
 * <p>Written by the server thread around every block entity and entity tick (two plain
 * field writes, so it costs next to nothing), and read by the sampler right after it took
 * a stack trace. Taking a stack trace pauses the thread, which makes its writes visible.
 * The sampler also checks the stack really is inside such a tick before trusting these.
 */
public final class TickContext {
	/** On only while the lag monitor runs (the hooks are only applied on dedicated servers). */
	public static volatile boolean enabled;
	public static @Nullable BlockEntity blockEntity;
	public static @Nullable Entity entity;

	private TickContext() {
	}
}
