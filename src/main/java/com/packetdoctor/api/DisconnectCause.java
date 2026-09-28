package com.packetdoctor.api;

/** Broad reasons a player's connection ended. */
public enum DisconnectCause {
	/** The player quit (or closed the game). */
	LEFT,
	/** Removed by the server: /kick, a mod or plugin, or a vanilla check (flying, spam...). */
	KICKED,
	/** Turned away while logging in (whitelist, ban, full, wrong version...). */
	REFUSED,
	/** The player's game stopped answering. */
	TIMED_OUT,
	/** The connection broke (reset, network drop). */
	LOST_CONNECTION,
	/** The server couldn't read or handle what the player's game sent. */
	BAD_DATA,
	/** The server was shutting down. */
	SERVER_STOPPED
}
