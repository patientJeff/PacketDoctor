package com.packetdoctor.mixin;

import com.packetdoctor.server.perf.TickContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes which entity is being ticked, for the lag profiler ({@link TickContext}). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "tickNonPassenger", at = @At("HEAD"))
	private void packetdoctor$start(Entity entity, CallbackInfo ci) {
		if (TickContext.enabled) TickContext.entity = entity;
	}

	@Inject(method = "tickNonPassenger", at = @At("RETURN"))
	private void packetdoctor$end(Entity entity, CallbackInfo ci) {
		if (TickContext.enabled) TickContext.entity = null;
	}

	@Inject(method = "tickPassenger", at = @At("HEAD"))
	private void packetdoctor$startPassenger(Entity vehicle, Entity entity, CallbackInfo ci) {
		if (TickContext.enabled) TickContext.entity = entity;
	}

	/** Back to the vehicle, whose own passengers loop continues. */
	@Inject(method = "tickPassenger", at = @At("RETURN"))
	private void packetdoctor$endPassenger(Entity vehicle, Entity entity, CallbackInfo ci) {
		if (TickContext.enabled) TickContext.entity = vehicle;
	}
}
