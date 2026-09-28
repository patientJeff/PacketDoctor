package com.packetdoctor.mixin;

import com.packetdoctor.net.NetHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures outgoing packets once they have been written (before compression). */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
	@Shadow
	@Final
	private ProtocolInfo<?> protocolInfo;

	@Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
			at = @At("RETURN"))
	private void packetdoctor$encoded(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
		NetHooks.encoded(protocolInfo, packet, output.readableBytes());
	}
}
