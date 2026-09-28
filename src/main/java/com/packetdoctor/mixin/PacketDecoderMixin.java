package com.packetdoctor.mixin;

import com.packetdoctor.net.NetHooks;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Measures incoming packets. The buffer holds exactly one packet (already decompressed),
 * so its size at the start is the packet's size; at the end, the decoded packet is the
 * last entry of {@code out}. Each decoder belongs to one channel and runs on one thread.
 */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
	@Shadow
	@Final
	private ProtocolInfo<?> protocolInfo;

	@Unique
	private int packetdoctor$size;

	@Inject(method = "decode", at = @At("HEAD"))
	private void packetdoctor$measure(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
		packetdoctor$size = input.readableBytes();
	}

	@Inject(method = "decode", at = @At("RETURN"))
	private void packetdoctor$decoded(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
		if (!out.isEmpty()) NetHooks.decoded(protocolInfo, out.getLast(), packetdoctor$size);
	}
}
