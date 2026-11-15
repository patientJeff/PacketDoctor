package com.packetdoctor.mixin;

import com.packetdoctor.server.perf.TickContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes which block entity is being ticked, for the lag profiler ({@link TickContext}). */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BoundTickingBlockEntityMixin {
	@Shadow
	@Final
	private BlockEntity blockEntity;

	@Inject(method = "tick", at = @At("HEAD"))
	private void packetdoctor$start(CallbackInfo ci) {
		if (TickContext.enabled) TickContext.blockEntity = this.blockEntity;
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void packetdoctor$end(CallbackInfo ci) {
		if (TickContext.enabled) TickContext.blockEntity = null;
	}
}
