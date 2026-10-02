package com.packetdoctor.network;

import com.packetdoctor.PacketDoctor;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Carries a {@link ServerStatus} from server to player. Like the explanation, it is only sent
 * to players whose game registered this channel; players without the mod never get it.
 */
public record StatusPayload(String json) implements CustomPacketPayload {
	public static final Type<StatusPayload> TYPE = new Type<>(PacketDoctor.id("status"));
	public static final StreamCodec<ByteBuf, StatusPayload> CODEC =
			ByteBufCodecs.stringUtf8(16 * 1024).map(StatusPayload::new, StatusPayload::json);

	@Override
	public Type<StatusPayload> type() {
		return TYPE;
	}
}
