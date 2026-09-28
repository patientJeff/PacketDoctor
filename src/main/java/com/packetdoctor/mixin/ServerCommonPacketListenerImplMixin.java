package com.packetdoctor.mixin;

import com.packetdoctor.net.ServerMonitor;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server's handler for a joined (or joining) player, shared by the configuration and
 * play phases:
 * <ul>
 *   <li>{@code disconnect} - the server is removing the player; at its start the reason can
 *       still be sent ahead of the disconnect packet</li>
 *   <li>{@code onDisconnect} - the connection is over, for any reason</li>
 *   <li>{@code onPacketError} - the server failed on something the player sent</li>
 * </ul>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
	@Shadow
	@Final
	protected Connection connection;

	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
	private void packetdoctor$onKick(DisconnectionDetails details, CallbackInfo ci) {
		ServerMonitor m = ServerMonitor.get();
		if (m == null) return;
		try {
			m.onKick((ServerCommonPacketListenerImpl) (Object) this, details);
		} catch (RuntimeException e) {
			com.packetdoctor.PacketDoctor.LOGGER.debug("Kick hook failed", e);
		}
	}

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void packetdoctor$onLeave(DisconnectionDetails details, CallbackInfo ci) {
		ServerMonitor m = ServerMonitor.get();
		if (m == null) return;
		try {
			m.onLeave((ServerCommonPacketListenerImpl) (Object) this, connection, details);
		} catch (RuntimeException e) {
			com.packetdoctor.PacketDoctor.LOGGER.debug("Leave hook failed", e);
		}
	}

	@Inject(method = "onPacketError", at = @At("HEAD"))
	private void packetdoctor$onPacketError(Packet<?> packet, Exception cause, CallbackInfo ci) {
		ServerMonitor m = ServerMonitor.get();
		if (m == null) return;
		try {
			m.onPacketError((ServerCommonPacketListenerImpl) (Object) this, packet, cause);
		} catch (RuntimeException e) {
			com.packetdoctor.PacketDoctor.LOGGER.debug("Server packet error hook failed", e);
		}
	}
}
