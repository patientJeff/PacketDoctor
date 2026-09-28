package com.packetdoctor.net;

import com.packetdoctor.diagnose.ModBlame;
import com.packetdoctor.network.ExplanationPayload;
import com.packetdoctor.network.ServerExplanation;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.timeout.TimeoutException;
import net.minecraft.network.SkipPacketException;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The checks. Each one looks for something known to cause lag, crashes, kicks or bans.
 * Limits are the real ones from Minecraft 26.3's code (or, for servers, the common
 * Paper/Spigot defaults), not guesses.
 *
 * <p>"Player-sent" checks run on both sides through a {@link RuleSink}; the rest only make
 * sense on one side and say so in their names.
 */
final class PacketRules {
	private static final int KB = 1024;
	private static final int MB = 1024 * 1024;
	/** Minecraft refuses packets larger than this once decompressed. */
	private static final int PROTOCOL_LIMIT = 8 * MB;
	/** Positions beyond this are rejected by the server ("Invalid move player packet"). */
	private static final double WORLD_LIMIT = 3.0E7;

	private static final Set<String> ITEM_TYPES = Set.of("container_set_content", "container_set_slot",
			"set_player_inventory", "set_cursor_item", "set_equipment", "set_entity_data", "merchant_offers",
			"block_entity_data");
	private static final Set<String> TEXT_TYPES = Set.of("system_chat", "player_chat", "disguised_chat",
			"set_title_text", "set_subtitle_text", "set_action_bar_text", "tab_list", "show_dialog");
	private static final List<String> MOVE_TYPES = List.of("move_player_pos", "move_player_pos_rot",
			"move_player_rot", "move_player_status_only");
	private static final List<String> CHAT_TYPES = List.of("system_chat", "player_chat", "disguised_chat");
	private static final List<String> SOUND_TYPES = List.of("sound", "sound_entity");

	/**
	 * Per-second limits for single packet types a player sends. The explanation of each is
	 * {@code packetdoctor.warn.limit.<path>} (player wording) / {@code packetdoctor.swarn.limit.<path>}.
	 */
	record OutLimit(String path, int perSecond) {
	}

	static final List<OutLimit> OUT_LIMITS = List.of(
			new OutLimit("place_recipe", 10), // Paper/Spigot allow about 5 per second
			new OutLimit("container_click", 60),
			new OutLimit("interact", 40),
			new OutLimit("use_item_on", 60),
			new OutLimit("use_item", 60),
			new OutLimit("swing", 60),
			new OutLimit("player_action", 80),
			new OutLimit("set_carried_item", 60));

	private PacketRules() {
	}

	// --- Packets the server sends (checked on both sides) -----------------------------

	/** Size checks for a packet going to the player. */
	static void toPlayerSize(RuleSink s, PacketRecord record, int size) {
		String path = PacketNames.path(record.id);
		String what = PacketNames.describe(record.id);
		if (size >= 6 * MB) {
			s.warn(s.w("in.size.huge", Severity.DANGER).key("in.size.huge." + path)
					.detail(what, PacketNames.bytes(size), PacketNames.bytes(PROTOCOL_LIMIT))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		} else if (path.equals("level_chunk_with_light") && size >= MB) {
			s.warn(s.w("in.size.chunk", Severity.WARNING).detail(PacketNames.bytes(size))
					.packet(Direction.IN, record.id).source("packetdoctor.source.world_data"), record);
		} else if (ITEM_TYPES.contains(path) && size >= 256 * KB) {
			s.warn(s.w("in.size.items", size >= MB ? Severity.DANGER : Severity.WARNING).key("in.size.items." + path)
					.detail(what, PacketNames.bytes(size))
					.packet(Direction.IN, record.id).source("packetdoctor.source.item_data"), record);
		} else if (TEXT_TYPES.contains(path) && size >= 64 * KB) {
			s.warn(s.w("in.size.text", Severity.WARNING).key("in.size.text." + path)
					.detail(what, PacketNames.bytes(size))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		} else if (size >= 2 * MB) {
			s.warn(s.w("in.size.big", Severity.WARNING).key("in.size.big." + path)
					.detail(what, PacketNames.bytes(size))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		} else if (path.equals("custom_payload") && size >= 512 * KB) {
			s.warn(s.w("in.size.payload", Severity.WARNING).detail(PacketNames.bytes(size))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		}
	}

	/** Overall rate of packets going to the player. */
	static void toPlayerRate(RuleSink s, RateTracker.Second sec) {
		if (sec.total() >= 5000) {
			s.warn(s.w("in.rate.total", Severity.WARNING).detail(PacketNames.count(sec.total()), sec.top(3))
					.source("packetdoctor.source.server"), null);
		}
	}

	// --- Packets from the server: only the player's game can judge these ---------------

	static void clientInbound(PacketMonitor m, Packet<?> packet, PacketRecord record) {
		String path = PacketNames.path(record.id);
		if (path.equals("respawn") || path.equals("login")) m.resetMovement();
		switch (packet) {
			case ClientboundPlayerPositionPacket p -> teleport(m, p, record);
			case ClientboundExplodePacket p -> explosion(m, p, record);
			case ClientboundLevelParticlesPacket p -> particles(m, p, record);
			case ClientboundSetEntityMotionPacket p -> motion(m, p, record);
			case ClientboundTransferPacket p -> {
				m.noteTransfer(p.host() + ":" + p.port());
				m.warn(m.w("in.transfer", Severity.INFO).detail(p.host() + ":" + p.port()).packet(Direction.IN, record.id), record);
			}
			case ClientboundCustomPayloadPacket p when p.payload() instanceof ExplanationPayload e -> {
				ServerExplanation explanation = ServerExplanation.fromJson(e.json());
				if (explanation != null) m.onServerExplanation(explanation);
			}
			case ClientboundCustomPayloadPacket p when p.payload() instanceof DiscardedPayload d -> {
				String channel = d.id().toString();
				m.warn(m.w("in.channel", Severity.INFO).key("in.channel." + channel)
						.detail(channel).advice(d.id().getNamespace())
						.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
			}
			default -> {
			}
		}
	}

	private static void teleport(PacketMonitor m, ClientboundPlayerPositionPacket p, PacketRecord record) {
		m.resetMovement();
		PositionMoveRotation change = p.change();
		Vec3 pos = change.position();
		if (!finite(pos) || !finite(change.deltaMovement()) || !Float.isFinite(change.yRot()) || !Float.isFinite(change.xRot())) {
			m.warn(m.w("in.teleport.invalid", Severity.DANGER).detail(fmt(pos))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
			return;
		}
		boolean absX = !p.relatives().contains(Relative.X);
		boolean absZ = !p.relatives().contains(Relative.Z);
		if (absX && Math.abs(pos.x) > WORLD_LIMIT || absZ && Math.abs(pos.z) > WORLD_LIMIT) {
			m.warn(m.w("in.teleport.edge", Severity.WARNING).detail(fmt(pos))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		}
	}

	private static void explosion(PacketMonitor m, ClientboundExplodePacket p, PacketRecord record) {
		if (!finite(p.center()) || !Float.isFinite(p.radius())) {
			m.warn(m.w("in.explode.invalid", Severity.DANGER).detail(fmt(p.center()), p.radius())
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
			return;
		}
		p.playerKnockback().ifPresent(kb -> {
			if (!finite(kb)) {
				m.warn(m.w("in.explode.kb_invalid", Severity.DANGER).detail(fmt(kb))
						.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
			} else if (kb.length() > 10) {
				m.warn(m.w("in.explode.kb", kb.length() > 50 ? Severity.DANGER : Severity.WARNING)
						.detail(String.format(Locale.ROOT, "%.1f", kb.length()), String.format(Locale.ROOT, "%.0f", kb.length() * 20))
						.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
			}
		});
		if (p.radius() > 128) {
			m.warn(m.w("in.explode.radius", Severity.WARNING).detail(String.format(Locale.ROOT, "%.0f", p.radius()))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		}
	}

	private static void particles(PacketMonitor m, ClientboundLevelParticlesPacket p, PacketRecord record) {
		m.addParticles(Math.max(0, p.count()));
		if (p.count() >= 10_000) {
			m.warn(m.w("in.particles.count", p.count() >= 100_000 ? Severity.DANGER : Severity.WARNING)
					.detail(PacketNames.count(p.count()))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		}
		if (!Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())) {
			m.warn(m.w("in.particles.invalid", Severity.WARNING)
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		}
	}

	private static void motion(PacketMonitor m, ClientboundSetEntityMotionPacket p, PacketRecord record) {
		if (p.id() != m.ownEntityId()) return;
		Vec3 v = p.movement();
		if (!finite(v)) {
			m.warn(m.w("in.motion.invalid", Severity.DANGER).detail(fmt(v))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		} else if (v.length() > 10) {
			m.warn(m.w("in.motion.fast", Severity.WARNING).detail(String.format(Locale.ROOT, "%.0f", v.length() * 20))
					.packet(Direction.IN, record.id).source("packetdoctor.source.server"), record);
		}
	}

	static void clientInboundRate(PacketMonitor m, RateTracker.Second s) {
		toPlayerRate(m, s);
		int particlePackets = s.count("level_particles");
		if (particlePackets >= 1000 || s.extra() >= 200_000) {
			m.warn(m.w("in.rate.particles", Severity.WARNING).detail(PacketNames.count(particlePackets), PacketNames.count(s.extra()))
					.packet(Direction.IN, "minecraft:level_particles").source("packetdoctor.source.server"), null);
		}
		int sounds = s.countAll(SOUND_TYPES);
		if (sounds >= 300) {
			m.warn(m.w("in.rate.sound", Severity.WARNING).detail(PacketNames.count(sounds))
					.packet(Direction.IN, "minecraft:sound").source("packetdoctor.source.server"), null);
		}
		int explosions = s.count("explode");
		if (explosions >= 50) {
			m.warn(m.w("in.rate.explode", Severity.WARNING).detail(PacketNames.count(explosions))
					.packet(Direction.IN, "minecraft:explode").source("packetdoctor.source.server"), null);
		}
		int spawns = s.count("add_entity");
		if (spawns >= 800) {
			m.warn(m.w("in.rate.entities", Severity.WARNING).detail(PacketNames.count(spawns))
					.packet(Direction.IN, "minecraft:add_entity").source("packetdoctor.source.server"), null);
		}
		int chat = s.countAll(CHAT_TYPES);
		if (chat >= 60) {
			m.warn(m.w("in.rate.chat", Severity.WARNING).detail(PacketNames.count(chat))
					.packet(Direction.IN, "minecraft:system_chat").source("packetdoctor.source.server"), null);
		}
	}

	// --- Packets the player's game sends (checked on both sides) ------------------------

	static void fromPlayer(RuleSink s, Packet<?> packet, PacketRecord record) {
		switch (packet) {
			case ServerboundMovePlayerPacket p -> movement(s, p, record);
			case ServerboundChatPacket p -> {
				if (p.message().length() > 256) {
					s.warn(blamed(s, s.w("out.chat.long", Severity.DANGER).detail(p.message().length())
							.packet(Direction.OUT, record.id)), record);
				}
				illegalCharacters(s, p.message(), record);
			}
			case ServerboundChatCommandPacket p -> illegalCharacters(s, p.command(), record);
			case ServerboundChatCommandSignedPacket p -> illegalCharacters(s, p.command(), record);
			case ServerboundEditBookPacket p -> book(s, p, record);
			case ServerboundSignUpdatePacket p -> sign(s, p, record);
			default -> {
			}
		}
	}

	private static void movement(RuleSink s, ServerboundMovePlayerPacket p, PacketRecord record) {
		if (p.hasPosition()) {
			double x = p.getX(0), y = p.getY(0), z = p.getZ(0);
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				s.warn(blamed(s, s.w("out.move.invalid", Severity.DANGER).packet(Direction.OUT, record.id)), record);
				return;
			}
			if (Math.abs(x) >= WORLD_LIMIT || Math.abs(z) >= WORLD_LIMIT || Math.abs(y) >= 2.0E7) {
				s.warn(blamed(s, s.w("out.move.edge", Severity.DANGER).detail(fmt(new Vec3(x, y, z)))
						.packet(Direction.OUT, record.id)), record);
				return;
			}
			double moved = s.moved(x, y, z);
			if (moved > 10) {
				s.warn(blamed(s, s.w("out.move.far", Severity.WARNING).detail(String.format(Locale.ROOT, "%.1f", moved))
						.packet(Direction.OUT, record.id)), record);
			}
		}
		if (p.hasRotation() && (!Float.isFinite(p.getYRot(0)) || !Float.isFinite(p.getXRot(0)))) {
			s.warn(blamed(s, s.w("out.move.rotation", Severity.DANGER).packet(Direction.OUT, record.id)), record);
		}
	}

	private static void illegalCharacters(RuleSink s, String text, PacketRecord record) {
		for (int i = 0; i < text.length(); ) {
			int c = text.codePointAt(i);
			if (!StringUtil.isAllowedChatCharacter(c)) {
				s.warn(blamed(s, s.w("out.chat.illegal", Severity.DANGER)
						.detail(String.format(Locale.ROOT, "U+%04X", c), i + 1)
						.packet(Direction.OUT, record.id)), record);
				return;
			}
			i += Character.charCount(c);
		}
	}

	private static void book(RuleSink s, ServerboundEditBookPacket p, PacketRecord record) {
		int longest = 0;
		for (String page : p.pages()) longest = Math.max(longest, page.length());
		int title = p.title().map(String::length).orElse(0);
		if (p.pages().size() > 100 || longest > 1024 || title > 32) {
			s.warn(blamed(s, s.w("out.book", Severity.DANGER).detail(p.pages().size(), longest, title)
					.packet(Direction.OUT, record.id)), record);
		}
	}

	private static void sign(RuleSink s, ServerboundSignUpdatePacket p, PacketRecord record) {
		for (String line : p.lines()) {
			if (line.length() > 384) {
				s.warn(blamed(s, s.w("out.sign", Severity.DANGER).detail(line.length()).packet(Direction.OUT, record.id)), record);
				return;
			}
		}
	}

	/** Size of a packet the player's game sent. */
	static void fromPlayerSize(RuleSink s, PacketRecord record, int size) {
		if (size >= 256 * KB) {
			s.warn(s.w("out.size", size >= MB ? Severity.DANGER : Severity.WARNING).key("out.size." + PacketNames.path(record.id))
					.detail(PacketNames.describe(record.id), PacketNames.bytes(size))
					.packet(Direction.OUT, record.id), record);
		}
	}

	/** Rates of what the player's game sends, per finished second. */
	static void fromPlayerRate(RuleSink s, RateTracker.Second sec) {
		if (sec.total() >= 200) {
			Severity severity = sec.total() >= 400 ? Severity.DANGER : Severity.WARNING;
			String busiest = sec.busiest();
			s.warn(s.w("out.rate.total", severity).key("out.rate.total." + severity.name())
					.detail(PacketNames.count(sec.total()), sec.top(3))
					.mod(busiest == null ? null : s.blameForType(busiest)), null);
		}
		int moves = sec.countAll(MOVE_TYPES);
		if (moves >= 45) {
			s.warn(s.w("out.rate.move", Severity.WARNING).detail(moves)
					.packet(Direction.OUT, "minecraft:move_player_pos").mod(s.blameForType("move_player_pos")), null);
		}
		for (OutLimit limit : OUT_LIMITS) {
			int n = sec.count(limit.path());
			if (n < limit.perSecond()) continue;
			s.warn(s.w("out.rate.limit", Severity.WARNING).key("out.rate." + limit.path())
					.title(PacketNames.friendly(limit.path())).detail(n).adviceFrom("limit." + limit.path())
					.packet(Direction.OUT, "minecraft:" + limit.path()).mod(s.blameForType(limit.path())), null);
		}
	}

	// --- Only the player's game: chat spam and its own network errors -----------------

	static void clientSpam(PacketMonitor m, Packet<?> packet, PacketRecord record) {
		if (!(packet instanceof ServerboundChatPacket || packet instanceof ServerboundChatCommandPacket
				|| packet instanceof ServerboundChatCommandSignedPacket)) {
			return;
		}
		double level = m.bumpSpam(record.time);
		if (level > 200) {
			m.warn(m.w("out.chat.spam", Severity.DANGER).detail(Math.round(level / 20)).packet(Direction.OUT, record.id), record);
		} else if (level > 140) {
			m.warn(m.w("out.chat.spam_soon", Severity.WARNING).detail(Math.round(level / 20)).packet(Direction.OUT, record.id), record);
		}
	}

	static void clientNetworkError(PacketMonitor m, Throwable cause) {
		String message = shortMessage(cause);
		ModBlame.Culprit mod = ModBlame.prime(ModBlame.blame(cause, true));
		String modLabel = mod == null ? null : mod.label();
		if (cause instanceof SkipPacketException) {
			Warning.Builder b = m.w("net.skipped", Severity.WARNING).detail(message).mod(modLabel);
			if (modLabel == null) b.source("packetdoctor.source.mismatch");
			m.warn(b, null);
		} else if (cause instanceof TimeoutException) {
			m.warn(m.w("net.timeout", Severity.DANGER).source("packetdoctor.source.server_or_network"), null);
		} else if (cause instanceof DecoderException) {
			m.warn(m.w("net.decode", Severity.DANGER).detail(message).mod(modLabel), null);
		} else if (cause instanceof EncoderException) {
			m.warn(m.w("net.encode", Severity.DANGER).detail(message).mod(modLabel), null);
		} else if (cause instanceof IOException) {
			m.warn(m.w("net.io", Severity.WARNING).detail(message).source("packetdoctor.source.network"), null);
		} else {
			m.warn(m.w("net.error", Severity.DANGER).detail(message).mod(modLabel), null);
		}
	}

	static void clientPacketError(PacketMonitor m, String packetId, Throwable cause) {
		ModBlame.Culprit mod = ModBlame.prime(ModBlame.blame(cause, true));
		m.warn(m.w("net.handle", Severity.DANGER).detail(PacketNames.describe(packetId), shortMessage(cause))
				.packet(Direction.IN, packetId).mod(mod == null ? null : mod.label()), null);
	}

	// --- Helpers --------------------------------------------------------------------

	/** Asks the sink who sent it (only the player's game can tell, and only the first time). */
	private static Warning.Builder blamed(RuleSink s, Warning.Builder builder) {
		return builder.mod(s.blameHere(builder.build().key));
	}

	static boolean finite(Vec3 v) {
		return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
	}

	static String fmt(Vec3 v) {
		return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", v.x, v.y, v.z);
	}

	/** "DecoderException: Failed to decode ..." with the root cause, kept short. */
	static String shortMessage(@Nullable Throwable t) {
		if (t == null) return "";
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		String s = root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
		if (root != t && t.getMessage() != null && !t.getMessage().contains(root.getClass().getSimpleName())) {
			s = t.getClass().getSimpleName() + ": " + t.getMessage() + " <- " + s;
		}
		return s.length() > 300 ? s.substring(0, 297) + "..." : s;
	}
}
