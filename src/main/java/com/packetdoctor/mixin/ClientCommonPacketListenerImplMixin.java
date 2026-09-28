package com.packetdoctor.mixin;

import com.packetdoctor.net.PacketMonitor;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Catches the player's game failing while handling a packet, which is followed by a disconnect. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
	@Inject(method = "onPacketError", at = @At("HEAD"))
	private void packetdoctor$onPacketError(Packet<?> packet, Exception cause, CallbackInfo ci) {
		PacketMonitor.get().onPacketError(packet, cause);
	}
}
