package com.packetdoctor.mixin;

import com.packetdoctor.diagnose.CrashHandler;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * Every crash of the player's game goes through {@code Minecraft.saveReport}. Hooking its
 * return means the vanilla crash report is already on disk, so the explanation links to it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "saveReport(Ljava/io/File;Lnet/minecraft/CrashReport;I)I", at = @At("RETURN"))
	private static void packetdoctor$explainCrash(File gameDirectory, CrashReport crash, int exitCode, CallbackInfoReturnable<Integer> cir) {
		CrashHandler.onClientCrash(crash);
	}
}
