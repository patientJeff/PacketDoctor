package com.packetdoctor.diagnose;

import com.packetdoctor.net.Severity;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Recognises kick messages that aren't Minecraft's own: Paper/Spigot, Velocity and
 * BungeeCord proxies, ViaVersion, anti-cheats, ban and VPN plugins, mod loaders. Matching
 * is by lower-case phrases, first match wins, so specific patterns come before general ones.
 *
 * <p>Each pattern's text is {@code <base>.headline}, {@code .summary} and {@code .note}
 * in the language file; its tips are ordinary {@code packetdoctor.tip.*} keys.
 */
final class KickPatterns {
	/** Extra handling some patterns need after their text is applied. */
	enum Special {
		NONE,
		/** Also list installed mods anti-cheats tend to react to. */
		RISKY_MODS,
		/** Blame the mod seen sending too many packets just before. */
		BLAME_RATE,
		/** Blame the mod seen sending bad packets just before. */
		BLAME_OUTGOING,
		/** Explain as a timeout. */
		TIMEOUT
	}

	record KickPattern(String base, Severity severity, SourceKind kind, List<String> phrases, List<String> tips, Special special) {
		boolean matches(String lower) {
			for (String p : phrases) if (lower.contains(p)) return true;
			return false;
		}
	}

	private static KickPattern p(String base, Severity s, SourceKind k, List<String> phrases, List<String> tips) {
		return new KickPattern(base, s, k, phrases, tips, Special.NONE);
	}

	private static KickPattern p(String base, Severity s, SourceKind k, List<String> phrases, List<String> tips, Special special) {
		return new KickPattern(base, s, k, phrases, tips, special);
	}

	static final List<KickPattern> ALL = List.of(
			p("packetdoctor.kick.throttle", Severity.INFO, SourceKind.SERVER,
					List.of("connection throttled", "logging in too fast", "reconnecting too fast", "before reconnecting",
							"too many connections", "rejoining too fast", "join too fast"),
					List.of("packetdoctor.tip.wait_10s")),
			p("packetdoctor.kick.starting", Severity.INFO, SourceKind.SERVER,
					List.of("still starting", "server is starting", "not finished starting", "server is loading", "not ready yet",
							"still loading"),
					List.of("packetdoctor.tip.wait_restart")),
			p("packetdoctor.kick.whitelist", Severity.INFO, SourceKind.SERVER,
					List.of("whitelist", "white-list", "white list", "not whitelisted", "allowlist"),
					List.of("packetdoctor.tip.ask_owner_add")),
			p("packetdoctor.kick.vpn", Severity.WARNING, SourceKind.SERVER,
					List.of("vpn", "proxy detected", "anti-vpn", "antivpn", "hosting provider", "datacenter", "data center"),
					List.of("packetdoctor.tip.disable_vpn")),
			p("packetdoctor.kick.banned", Severity.DANGER, SourceKind.SERVER,
					List.of("banned", " ban ", "blacklist", "permanently", "suspended"),
					List.of("packetdoctor.tip.appeal", "packetdoctor.tip.no_alts")),
			p("packetdoctor.kick.anticheat", Severity.WARNING, SourceKind.SERVER,
					List.of("anticheat", "anti-cheat", "cheat", "hacked client", "hacking", "unfair advantage", "illegal modification",
							"grimac", "grim ac", "[grim", "vulcan", "spartan", "nocheatplus", "verus", "intave", "karhu", "themis",
							"matrix anticheat", "blatant", "invalid movement"),
					List.of("packetdoctor.tip.anticheat_mods_off", "packetdoctor.tip.anticheat_lag"), Special.RISKY_MODS),
			p("packetdoctor.kick.mods", Severity.WARNING, SourceKind.MOD,
					List.of("registry", "mod rejections", "missing mods", "incompatible fml", "requires forge", "fabric api",
							"mod mismatch", "modded server", "required mods", "you are missing", "unsupported mod", "client mods",
							"not allowed to use", "disallowed mod", "blocked mod", "forbidden mod"),
					List.of("packetdoctor.tip.install_server_mods")),
			p("packetdoctor.kick.version", Severity.WARNING, SourceKind.SERVER,
					List.of("viaversion", "client version", "unsupported client", "unsupported version", "only compatible with",
							"please use", "outdated", "incompatible client", "wrong version", "version is not supported",
							"version not supported"),
					List.of("packetdoctor.tip.use_server_version", "packetdoctor.tip.check_server_site")),
			p("packetdoctor.kick.proxy_down", Severity.WARNING, SourceKind.SERVER,
					List.of("fallback", "could not connect to a default", "unable to connect you to", "server is not available",
							"cannot connect to server", "server is offline", "kicked whilst connecting", "lost connection to server",
							"your connection to", "encountered a problem", "no available servers", "lobby"),
					List.of("packetdoctor.tip.wait_check_status")),
			p("packetdoctor.kick.restart", Severity.INFO, SourceKind.SERVER,
					List.of("restart", "shutting down", "shut down", "maintenance", "server closed", "server is stopping",
							"rebooting"),
					List.of("packetdoctor.tip.wait_rejoin")),
			p("packetdoctor.kick.full", Severity.INFO, SourceKind.SERVER,
					List.of("server is full", "is full", "no free slots", "no slots"),
					List.of("packetdoctor.tip.wait_retry")),
			p("packetdoctor.kick.auth", Severity.WARNING, SourceKind.YOU,
					List.of("not authenticated", "failed to verify username", "invalid session", "authentication", "premium",
							"not logged in"),
					List.of("packetdoctor.tip.restart_launcher", "packetdoctor.tip.logged_in")),
			p("packetdoctor.kick.duplicate", Severity.WARNING, SourceKind.SERVER,
					List.of("logged in from another location", "already connected", "already online", "already logged in",
							"another location"),
					List.of("packetdoctor.tip.close_other_windows", "packetdoctor.tip.secure_account")),
			p("packetdoctor.kick.packet_rate", Severity.DANGER, SourceKind.YOU,
					List.of("too many packets", "packet limit", "packet rate", "sending too many", "sent too many", "packet spam"),
					List.of("packetdoctor.tip.automation_off"), Special.BLAME_RATE),
			p("packetdoctor.kick.spam", Severity.WARNING, SourceKind.YOU,
					List.of("spam", "too fast", "slow down", "flood"),
					List.of("packetdoctor.tip.slow_chat", "packetdoctor.tip.no_auto_messages")),
			p("packetdoctor.kick.idle", Severity.INFO, SourceKind.SERVER,
					List.of("idle", "afk", "inactiv"),
					List.of("packetdoctor.tip.rejoin_move")),
			p("packetdoctor.kick.timeout", Severity.WARNING, SourceKind.NETWORK,
					List.of("timed out", "timeout", "keepalive", "keep-alive", "keep alive"),
					List.of(), Special.TIMEOUT),
			p("packetdoctor.kick.error", Severity.DANGER, SourceKind.SERVER,
					List.of("internal exception", "exception", "error"),
					List.of("packetdoctor.tip.without_optional_mods", "packetdoctor.tip.send_staff_report")),
			p("packetdoctor.kick.packets", Severity.WARNING, SourceKind.YOU,
					List.of("packet"),
					List.of("packetdoctor.tip.automation_off"), Special.BLAME_OUTGOING),
			p("packetdoctor.kick.kicked", Severity.INFO, SourceKind.SERVER,
					List.of("kicked", "removed from"),
					List.of("packetdoctor.tip.rejoin_ask_staff")));

	private KickPatterns() {
	}

	static @Nullable KickPattern match(String text) {
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		for (KickPattern p : ALL) if (p.matches(lower)) return p;
		// "mod" alone is too common; only count it next to a rule word.
		if (lower.contains("mod") && (lower.contains("require") || lower.contains("missing") || lower.contains("install")
				|| lower.contains("not allowed") || lower.contains("forbidden"))) {
			for (KickPattern p : ALL) if (p.base().endsWith(".mods")) return p;
		}
		return null;
	}
}
