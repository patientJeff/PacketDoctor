package com.packetdoctor.net;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;

/** Receives the low-level connection events captured by the mixins. */
public interface ConnectionObserver {
	void onInbound(Connection connection, Packet<?> packet);

	void onOutbound(Connection connection, Packet<?> packet);

	void onDecoded(ProtocolInfo<?> protocol, Object packet, int size);

	void onEncoded(ProtocolInfo<?> protocol, Object packet, int size);

	void onNetworkException(Connection connection, Throwable cause);

	void onChannelInactive(Connection connection);

	void onDisconnect(Connection connection, DisconnectionDetails details);
}
