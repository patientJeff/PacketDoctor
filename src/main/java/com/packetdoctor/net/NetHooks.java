package com.packetdoctor.net;

import com.packetdoctor.PacketDoctor;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jspecify.annotations.Nullable;

/**
 * Routes connection events to the right side. A connection that receives clientbound
 * packets is the player's own game connection ({@link PacketMonitor}); one that receives
 * serverbound packets is the server's connection to a player ({@link ServerMonitor}).
 *
 * <p>The client observer is only set by the client entrypoint, so client-only classes are
 * never loaded on a dedicated server. Every call is guarded: a bug here must never break
 * the connection being watched.
 */
public final class NetHooks {
	private static volatile @Nullable ConnectionObserver client;
	private static volatile @Nullable ConnectionObserver server;

	private NetHooks() {
	}

	public static void setClient(ConnectionObserver observer) {
		client = observer;
	}

	public static void setServer(ConnectionObserver observer) {
		server = observer;
	}

	private static @Nullable ConnectionObserver of(Connection connection) {
		return connection.getReceiving() == PacketFlow.CLIENTBOUND ? client : server;
	}

	public static void inbound(Connection connection, Packet<?> packet) {
		ConnectionObserver o = of(connection);
		if (o == null) return;
		try {
			o.onInbound(connection, packet);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Inbound hook failed", e);
		}
	}

	public static void outbound(Connection connection, Packet<?> packet) {
		ConnectionObserver o = of(connection);
		if (o == null) return;
		try {
			o.onOutbound(connection, packet);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Outbound hook failed", e);
		}
	}

	/** Decoder: clientbound packets are decoded by the player's game, serverbound by the server. */
	public static void decoded(ProtocolInfo<?> protocol, Object packet, int size) {
		ConnectionObserver o = protocol.flow() == PacketFlow.CLIENTBOUND ? client : server;
		if (o == null) return;
		try {
			o.onDecoded(protocol, packet, size);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Decoder hook failed", e);
		}
	}

	/** Encoder: serverbound packets are encoded by the player's game, clientbound by the server. */
	public static void encoded(ProtocolInfo<?> protocol, Object packet, int size) {
		ConnectionObserver o = protocol.flow() == PacketFlow.SERVERBOUND ? client : server;
		if (o == null) return;
		try {
			o.onEncoded(protocol, packet, size);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Encoder hook failed", e);
		}
	}

	public static void exception(Connection connection, Throwable cause) {
		ConnectionObserver o = of(connection);
		if (o == null) return;
		try {
			o.onNetworkException(connection, cause);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Exception hook failed", e);
		}
	}

	public static void inactive(Connection connection) {
		ConnectionObserver o = of(connection);
		if (o == null) return;
		try {
			o.onChannelInactive(connection);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Channel inactive hook failed", e);
		}
	}

	public static void disconnect(Connection connection, DisconnectionDetails details) {
		ConnectionObserver o = of(connection);
		if (o == null) return;
		try {
			o.onDisconnect(connection, details);
		} catch (RuntimeException e) {
			PacketDoctor.LOGGER.debug("Disconnect hook failed", e);
		}
	}
}
