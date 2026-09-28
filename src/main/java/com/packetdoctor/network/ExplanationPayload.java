package com.packetdoctor.network;

import com.packetdoctor.PacketDoctor;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Carries a {@link ServerExplanation} from server to player. The server only sends it to
 * players whose game registered this channel (i.e. that have Packet Doctor); players
 * without the mod never receive it and can join exactly as before.
 */
public record ExplanationPayload(String json) implements CustomPacketPayload {
	public static final Type<ExplanationPayload> TYPE = new Type<>(PacketDoctor.id("explanation"));
	public static final StreamCodec<ByteBuf, ExplanationPayload> CODEC =
			ByteBufCodecs.stringUtf8(256 * 1024).map(ExplanationPayload::new, ExplanationPayload::json);

	@Override
	public Type<ExplanationPayload> type() {
		return TYPE;
	}
}
