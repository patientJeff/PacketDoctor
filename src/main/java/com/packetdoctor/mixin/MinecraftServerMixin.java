package com.packetdoctor.mixin;

import com.packetdoctor.diagnose.CrashHandler;
import net.minecraft.CrashReport;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dedicated server's crash. By the time {@code onServerCrash} runs, the vanilla crash
 * report is saved. The singleplayer server overrides this method to crash the player's
 * game instead, which the client side explains.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "onServerCrash", at = @At("HEAD"))
	private void packetdoctor$explainCrash(CrashReport report, CallbackInfo ci) {
		CrashHandler.onServerCrash(report);
	}
}
