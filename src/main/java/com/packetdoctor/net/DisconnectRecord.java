package com.packetdoctor.net;

import com.packetdoctor.network.ServerExplanation;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.nio.file.Path;

/**
 * Everything known at the moment the connection ended, captured before the game tears
 * the session down, so the disconnect screen can explain it afterwards.
 *
 * @param stack           who asked for the disconnect (our own frames included)
 * @param networkError    last low-level network error on this connection, if recent
 * @param packetError     last error while the game handled a packet, if recent
 * @param failedPacket    id of the packet that failed to be handled
 * @param silenceMs       how long the server had been quiet before the end
 * @param outboundPeak    busiest second of outgoing packets just before, as "N/s: top types"
 * @param transferTarget  host:port the server was transferring us to, if any
 * @param serverExplanation what a Packet Doctor server said about the disconnect, if it has the mod
 * @param report          the vanilla debug report file, if the game wrote one
 */
public record DisconnectRecord(
		long time,
		int connectionId,
		Component reason,
		Trigger trigger,
		StackTraceElement[] stack,
		@Nullable Throwable networkError,
		@Nullable Throwable packetError,
		@Nullable String failedPacket,
		long silenceMs,
		List<PacketRecord> lastPackets,
		List<Warning> recentWarnings,
		@Nullable String outboundPeak,
		int outboundPeakRate,
		String server,
		@Nullable String serverBrand,
		@Nullable String transferTarget,
		@Nullable ServerExplanation serverExplanation,
		Optional<Path> report) {

	/** What made the connection end, as far as the call stack shows. */
	public enum Trigger {
		/** The server sent a disconnect/kick packet. */
		SERVER_KICK,
		/** A network-level error (bad data, reset connection...). */
		NETWORK_ERROR,
		/** No data from the server for 30 seconds. */
		TIMEOUT,
		/** The socket closed without a disconnect packet. */
		CONNECTION_CLOSED,
		/** The game failed while handling a packet. */
		PACKET_ERROR,
		/** The player chose to leave. */
		PLAYER,
		/** A mod's code called disconnect. */
		MOD,
		/** Minecraft's own client code decided (chat validation, invalid packet...). */
		CLIENT,
		UNKNOWN
	}
}
