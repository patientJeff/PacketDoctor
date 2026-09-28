package com.packetdoctor.api;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Why a player left or was removed, as the server saw it.
 *
 * @param uuid              the player's id; null for a login refused before the id was known
 * @param cause             the broad category
 * @param reason            the disconnect message, as the player saw it
 * @param reasonKey         Minecraft's translation key for the message, when it is a vanilla one
 * @param kickerKind        for kicks: {@code MOD}, {@code OPERATOR} or {@code SERVER}
 * @param kicker            for kicks by a mod: the mod, as "Name (id)"
 * @param kickReason        extra reason a mod passed through {@link PacketDoctorApi#kick} or {@link PacketDoctorApi#setKickReason}
 * @param serverError       an error the server hit on this player's data, if that's what ended it
 * @param summary           one plain sentence for admins, e.g. "Kicked by My Mod (mymod): Spamming"
 * @param warnings          what Packet Doctor noticed about the player shortly before
 * @param explanationSent   whether the player's game (with Packet Doctor) was sent the full explanation
 */
public record DisconnectInfo(
		@Nullable UUID uuid,
		String name,
		long time,
		DisconnectCause cause,
		String reason,
		@Nullable String reasonKey,
		@Nullable String kickerKind,
		@Nullable String kicker,
		@Nullable String kickReason,
		@Nullable String serverError,
		String summary,
		List<WarningInfo> warnings,
		boolean explanationSent) {
}
