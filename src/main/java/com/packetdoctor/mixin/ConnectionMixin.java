package com.packetdoctor.mixin;

import com.packetdoctor.net.NetHooks;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches every connection on both sides: packets in and out, network errors, the socket
 * closing, and requests to disconnect. {@link NetHooks} sends each event to the player
 * side or the server side depending on the connection's direction.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void packetdoctor$onRead(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
		NetHooks.inbound((Connection) (Object) this, packet);
	}

	@Inject(method = "sendPacket", at = @At("HEAD"))
	private void packetdoctor$onSend(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		NetHooks.outbound((Connection) (Object) this, packet);
	}

	@Inject(method = "exceptionCaught", at = @At("HEAD"))
	private void packetdoctor$onException(ChannelHandlerContext ctx, Throwable cause, CallbackInfo ci) {
		NetHooks.exception((Connection) (Object) this, cause);
	}

	@Inject(method = "channelInactive", at = @At("HEAD"))
	private void packetdoctor$onInactive(ChannelHandlerContext ctx, CallbackInfo ci) {
		NetHooks.inactive((Connection) (Object) this);
	}

	@Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
	private void packetdoctor$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
		NetHooks.disconnect((Connection) (Object) this, details);
	}
}
