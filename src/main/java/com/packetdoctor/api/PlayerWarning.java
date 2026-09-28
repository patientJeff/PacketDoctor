package com.packetdoctor.api;

import java.util.UUID;

/** A warning together with the player it is about. */
public record PlayerWarning(UUID player, String playerName, WarningInfo warning) {
}
