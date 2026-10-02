package com.packetdoctor.mixin;

import com.packetdoctor.diagnose.CrashHandler;
import com.packetdoctor.server.perf.LagMonitor;
import net.minecraft.CrashReport;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * A dedicated server's crash, and the timing of every tick for the lag monitor.
 * By the time {@code onServerCrash} runs, the vanilla crash report is saved. The
 * singleplayer server overrides it to crash the player's game instead, which the client
 * side explains. The lag monitor only runs on dedicated servers; elsewhere the tick hooks
 * return at once.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "onServerCrash", at = @At("HEAD"))
	private void packetdoctor$explainCrash(CrashReport report, CallbackInfo ci) {
		CrashHandler.onServerCrash(report);
	}

	@Inject(method = "tickServer", at = @At("HEAD"))
	private void packetdoctor$tickStart(BooleanSupplier haveTime, CallbackInfo ci) {
		LagMonitor.onTickStart();
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void packetdoctor$tickEnd(BooleanSupplier haveTime, CallbackInfo ci) {
		LagMonitor.onTickEnd((MinecraftServer) (Object) this);
	}

	@Inject(method = "doRunTask(Lnet/minecraft/server/TickTask;)V", at = @At("HEAD"))
	private void packetdoctor$taskStart(TickTask task, CallbackInfo ci) {
		LagMonitor.onTaskStart();
	}

	@Inject(method = "doRunTask(Lnet/minecraft/server/TickTask;)V", at = @At("RETURN"))
	private void packetdoctor$taskEnd(TickTask task, CallbackInfo ci) {
		LagMonitor.onTaskEnd();
	}
}
