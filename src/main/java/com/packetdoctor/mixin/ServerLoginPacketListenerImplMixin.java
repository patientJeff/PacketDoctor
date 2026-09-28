package com.packetdoctor.mixin;

import com.packetdoctor.net.ServerMonitor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records logins the server turns away (whitelist, bans, full, wrong version...). */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
	@Shadow
	public abstract String getUserName();

	@Inject(method = "disconnect", at = @At("HEAD"))
	private void packetdoctor$onRefused(Component reason, CallbackInfo ci) {
		ServerMonitor m = ServerMonitor.get();
		if (m == null) return;
		try {
			m.onRefused(getUserName(), reason);
		} catch (RuntimeException e) {
			com.packetdoctor.PacketDoctor.LOGGER.debug("Login refusal hook failed", e);
		}
	}
}
