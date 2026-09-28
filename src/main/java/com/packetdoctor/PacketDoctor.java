package com.packetdoctor;

import com.packetdoctor.network.ExplanationPayload;
import com.packetdoctor.server.PacketDoctorServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint, run on both dedicated servers and players' games. It registers the
 * explanation channel and starts the server side, which also runs inside singleplayer
 * worlds. The player-side features start from {@code PacketDoctorClient}.
 *
 * <p>The mod adds no blocks, items or other registry content, so players without it can
 * join a server that has it, and a player with it can join any server.
 */
public final class PacketDoctor implements ModInitializer {
	public static final String MOD_ID = "packetdoctor";
	public static final Logger LOGGER = LoggerFactory.getLogger("Packet Doctor");

	@Override
	public void onInitialize() {
		// Registered on both sides: the server encodes it, the player's game decodes it.
		PayloadTypeRegistry.clientboundPlay().register(ExplanationPayload.TYPE, ExplanationPayload.CODEC);
		PayloadTypeRegistry.clientboundConfiguration().register(ExplanationPayload.TYPE, ExplanationPayload.CODEC);
		PacketDoctorServer.init();
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("?");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
