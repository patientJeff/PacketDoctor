package com.packetdoctor.mixin;

import com.packetdoctor.diagnose.CrashHandler;
import net.minecraft.CrashReport;
import net.minecraft.server.dedicated.ServerWatchdog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A tick took longer than {@code max-tick-time}: the watchdog writes a crash report and
 * halts the JVM without the usual crash handling, so the explanation is made here, while
 * the stuck tick is still running and the lag monitor can say what it is doing.
 */
@Mixin(ServerWatchdog.class)
public abstract class ServerWatchdogMixin {
	@Inject(method = "createWatchdogCrashReport", at = @At("RETURN"))
	private static void packetdoctor$explainWatchdog(String title, long threadId, CallbackInfoReturnable<CrashReport> cir) {
		CrashHandler.onServerCrash(cir.getReturnValue());
	}
}
