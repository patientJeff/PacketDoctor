package com.packetdoctor.diagnose;

import com.packetdoctor.net.DisconnectRecord;
import com.packetdoctor.net.PacketMonitor;
import com.packetdoctor.net.PacketNames;
import com.packetdoctor.net.PacketRecord;
import com.packetdoctor.net.Severity;
import com.packetdoctor.net.Warning;
import com.packetdoctor.net.WarningData;
import com.packetdoctor.network.ServerExplanation;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.packetdoctor.Tr.t;

/**
 * Turns a disconnect into a {@link Diagnosis}. It combines: the reason the game shows
 * (usually a translation key such as {@code multiplayer.disconnect.flying}), who asked for
 * the disconnect ({@link DisconnectRecord.Trigger}), what was happening just before
 * (errors, warnings, rates), and, when the server runs Packet Doctor too, what the
 * server said about it.
 *
 * <p>All text is translated; each explanation is {@code <base>.headline}, {@code .summary}
 * and {@code .note}. The technical section stays English, as it is for modders and admins.
 */
public final class DisconnectDiagnoser {
	private static final Pattern PACKET_ID = Pattern.compile("(?:clientbound|serverbound|play|configuration|login)/(minecraft:[a-z0-9_/]+)");
	private static final Pattern CHANNEL = Pattern.compile("\\b([a-z0-9_.-]{2,64}):([a-z0-9_./-]{2,128})\\b");

	private DisconnectDiagnoser() {
	}

	public static Diagnosis diagnose(Component title, DisconnectionDetails details, @Nullable DisconnectRecord r) {
		Component reason = details.reason();
		Reason why = Reason.of(reason);
		Diagnosis.Builder b = Diagnosis.builder("disconnect").original(reason.getString());
		if (r != null) b.warnings(PacketMonitor.lines(r.recentWarnings(), 12));

		String titleKey = Reason.of(title).key;
		if (r == null) {
			if (titleKey.startsWith("connect.failed") || titleKey.equals("mco.connect.failed")) connectFailure(b, why);
			else if (why.key.isEmpty()) customKick(b, why, null);
			else if (!catalog(b, why, null, false)) generic(b, why);
		} else {
			switch (r.trigger()) {
				case SERVER_KICK -> {
					if (why.key.isEmpty() || !catalog(b, why, r, false)) customKick(b, why, r);
				}
				case TIMEOUT -> timeout(b, r);
				case NETWORK_ERROR -> networkError(b, r, why);
				case CONNECTION_CLOSED -> closed(b, r);
				case PACKET_ERROR -> packetError(b, r);
				case MOD -> modDisconnect(b, r, why);
				case CLIENT -> {
					if (!catalog(b, why, r, true)) generic(b, why);
				}
				default -> {
					if (why.key.isEmpty() || !catalog(b, why, r, false)) generic(b, why);
				}
			}
			if (r.serverExplanation() != null) serverSaid(b, r.serverExplanation(), why);
			mentionRecentProblems(b, r);
		}
		details.report().ifPresent(p -> b.tip(t("packetdoctor.tip.vanilla_report", p.toAbsolutePath())));
		b.tip(t("packetdoctor.tip.send_report"));
		b.technical(technical(title, details, r));
		return b.build();
	}

	// --- The reason text ------------------------------------------------------------

	/** The translation key and arguments behind a reason, unwrapping "disconnect.genericReason". */
	private record Reason(String key, Object[] args, String text) {
		static Reason of(Component c) {
			String text = c.getString();
			if (c.getContents() instanceof TranslatableContents tc) {
				if (tc.getKey().equals("disconnect.genericReason") && tc.getArgs().length == 1) {
					Object arg = tc.getArgs()[0];
					if (arg instanceof Component inner) return of(inner);
					return new Reason("", new Object[0], String.valueOf(arg));
				}
				return new Reason(tc.getKey(), tc.getArgs(), text);
			}
			return new Reason("", new Object[0], text);
		}

		String arg(int i) {
			if (i >= args.length) return "";
			return args[i] instanceof Component c ? c.getString() : String.valueOf(args[i]);
		}
	}

	/** Sets severity, headline, summary (with arguments) and the source note from {@code base}. */
	private static void explain(Diagnosis.Builder b, String base, Severity severity, SourceKind kind, @Nullable String source,
			Object... summaryArgs) {
		b.severity(severity).headline(t(base + ".headline")).summary(t(base + ".summary", summaryArgs))
				.source(kind, source, t(base + ".note"));
	}

	private static void tips(Diagnosis.Builder b, String... keys) {
		for (String k : keys) b.tip(t(k));
	}

	// --- Known vanilla reasons ------------------------------------------------------

	/** Explains a vanilla reason key. Returns false for keys it doesn't know. */
	private static boolean catalog(Diagnosis.Builder b, Reason why, @Nullable DisconnectRecord r, boolean byClient) {
		String transfer = r != null && r.transferTarget() != null ? " (" + r.transferTarget() + ")" : "";
		switch (why.key) {
			case "multiplayer.disconnect.kicked" -> {
				explain(b, "packetdoctor.dc.kicked", Severity.INFO, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.rejoin_ask_staff");
			}
			case "multiplayer.disconnect.banned", "multiplayer.disconnect.banned.reason" -> {
				explain(b, "packetdoctor.dc.banned", Severity.DANGER, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.appeal", "packetdoctor.tip.no_alts");
			}
			case "multiplayer.disconnect.banned_ip.reason", "multiplayer.disconnect.ip_banned" -> {
				explain(b, "packetdoctor.dc.banned_ip", Severity.DANGER, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.appeal", "packetdoctor.tip.no_alts");
			}
			case "multiplayer.disconnect.not_whitelisted" -> {
				explain(b, "packetdoctor.dc.whitelist", Severity.INFO, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.whitelist_add", "packetdoctor.tip.right_account");
			}
			case "multiplayer.disconnect.server_full" -> {
				explain(b, "packetdoctor.dc.full", Severity.INFO, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.wait_retry");
			}
			case "multiplayer.disconnect.name_taken", "multiplayer.disconnect.duplicate_login" -> {
				explain(b, "packetdoctor.dc.duplicate", Severity.WARNING, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.close_other_windows", "packetdoctor.tip.secure_account");
			}
			case "multiplayer.disconnect.idling" -> {
				explain(b, "packetdoctor.dc.idle", Severity.INFO, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.rejoin_move");
			}
			case "multiplayer.disconnect.flying" -> {
				explain(b, "packetdoctor.dc.flying", Severity.WARNING, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.movement_mods_off", "packetdoctor.tip.lag_false_alarm");
				riskyMods(b);
			}
			case "multiplayer.disconnect.invalid_player_movement", "multiplayer.disconnect.invalid_vehicle_movement" -> {
				explain(b, "packetdoctor.dc.invalid_move", Severity.DANGER, SourceKind.YOU, null);
				blameFromWarnings(b, r, "out.move");
				tips(b, "packetdoctor.tip.remove_movement_mods");
			}
			case "multiplayer.disconnect.illegal_characters" -> {
				explain(b, "packetdoctor.dc.illegal_chars", Severity.WARNING, SourceKind.YOU, null);
				blameFromWarnings(b, r, "out.chat.illegal");
				tips(b, "packetdoctor.tip.retype");
			}
			case "disconnect.spam" -> {
				explain(b, "packetdoctor.dc.spam", Severity.WARNING, SourceKind.YOU, null);
				tips(b, "packetdoctor.tip.slow_chat", "packetdoctor.tip.no_auto_messages");
			}
			case "disconnect.exceeded_packet_rate" -> {
				String peak = r != null && r.outboundPeak() != null
						? " " + t("packetdoctor.dc.packet_rate.peak", r.outboundPeakRate(), r.outboundPeak()) : "";
				explain(b, "packetdoctor.dc.packet_rate", Severity.DANGER, SourceKind.YOU, null, peak);
				blameFromWarnings(b, r, "out.rate");
				tips(b, "packetdoctor.tip.automation_off");
			}
			case "multiplayer.disconnect.outdated_client", "multiplayer.disconnect.outdated_server", "multiplayer.disconnect.incompatible" -> {
				String wanted = why.arg(0).isEmpty() ? "" : " " + t("packetdoctor.dc.version.asks", why.arg(0));
				explain(b, "packetdoctor.dc.version", Severity.WARNING, SourceKind.SERVER, null, wanted);
				tips(b, "packetdoctor.tip.use_server_version", "packetdoctor.tip.check_server_site");
			}
			case "multiplayer.disconnect.invalid_entity_attacked" -> {
				explain(b, "packetdoctor.dc.invalid_attack", Severity.WARNING, SourceKind.MOD, null);
				riskyMods(b);
				tips(b, "packetdoctor.tip.combat_mods_off");
			}
			case "multiplayer.disconnect.chat_validation_failed", "multiplayer.disconnect.out_of_order_chat",
					"multiplayer.disconnect.expired_public_key", "multiplayer.disconnect.invalid_public_key_signature",
					"multiplayer.disconnect.invalid_public_key_signature.new", "multiplayer.disconnect.unsigned_chat",
					"multiplayer.disconnect.too_many_pending_chats", "multiplayer.disconnect.bad_chat_index" -> {
				explain(b, "packetdoctor.dc.chat_signing", Severity.WARNING, byClient ? SourceKind.MINECRAFT : SourceKind.SERVER, null,
						t(byClient ? "packetdoctor.word.your_game" : "packetdoctor.word.the_server"));
				tips(b, "packetdoctor.tip.sync_clock", "packetdoctor.tip.restart_launcher", "packetdoctor.tip.chat_signing_mods");
			}
			case "multiplayer.disconnect.missing_tags" -> {
				explain(b, "packetdoctor.dc.missing_tags", Severity.WARNING, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.tell_admin");
			}
			case "multiplayer.disconnect.code_of_conduct" -> {
				explain(b, "packetdoctor.dc.coc", Severity.INFO, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.accept_coc");
			}
			case "multiplayer.disconnect.transfers_disabled" -> {
				explain(b, "packetdoctor.dc.transfers_disabled", Severity.INFO, SourceKind.SERVER, null, transfer);
				tips(b, "packetdoctor.tip.tell_owners_transfer");
			}
			case "multiplayer.disconnect.slow_login" -> {
				explain(b, "packetdoctor.dc.slow_login", Severity.WARNING, SourceKind.NETWORK, null);
				tips(b, "packetdoctor.tip.try_again_many_mods", "packetdoctor.tip.check_speed");
			}
			case "multiplayer.disconnect.authservers_down", "disconnect.loginFailedInfo.serversUnavailable" -> {
				explain(b, "packetdoctor.dc.auth_down", Severity.INFO, SourceKind.NETWORK, t("packetdoctor.who.login_servers"));
				tips(b, "packetdoctor.tip.wait_minutes");
			}
			case "multiplayer.disconnect.unverified_username", "disconnect.loginFailedInfo.invalidSession" -> {
				explain(b, "packetdoctor.dc.unverified", Severity.WARNING, SourceKind.YOU, t("packetdoctor.who.login_session"));
				tips(b, "packetdoctor.tip.restart_launcher", "packetdoctor.tip.logged_in");
			}
			case "disconnect.loginFailedInfo.userBanned" -> {
				explain(b, "packetdoctor.dc.account_banned", Severity.DANGER, SourceKind.SERVER, t("packetdoctor.who.mojang"));
				tips(b, "packetdoctor.tip.mojang_appeal");
			}
			case "disconnect.loginFailedInfo.insufficientPrivileges" -> {
				explain(b, "packetdoctor.dc.no_multiplayer", Severity.WARNING, SourceKind.YOU, t("packetdoctor.who.account_settings"));
				tips(b, "packetdoctor.tip.xbox_settings");
			}
			case "disconnect.loginFailedInfo" -> {
				explain(b, "packetdoctor.dc.login_failed", Severity.WARNING, SourceKind.NETWORK, null, why.arg(0));
				tips(b, "packetdoctor.tip.restart_launcher");
			}
			case "multiplayer.disconnect.server_shutdown" -> {
				explain(b, "packetdoctor.dc.shutdown", Severity.INFO, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.wait_rejoin");
			}
			case "disconnect.transfer" -> {
				explain(b, "packetdoctor.dc.transferred", Severity.INFO, SourceKind.SERVER, null, transfer);
				tips(b, "packetdoctor.tip.rejoin_original");
			}
			case "multiplayer.disconnect.configuration_error" -> {
				explain(b, "packetdoctor.dc.config_error", Severity.WARNING, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.match_mods");
			}
			case "multiplayer.disconnect.invalid_player_data" -> {
				explain(b, "packetdoctor.dc.player_data", Severity.WARNING, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.admin_player_data");
			}
			case "multiplayer.disconnect.unexpected_query_response" -> {
				explain(b, "packetdoctor.dc.query_response", Severity.WARNING, SourceKind.MOD, null);
				tips(b, "packetdoctor.tip.match_mods");
			}
			case "multiplayer.disconnect.invalid_packet", "disconnect.packetError" -> {
				explain(b, "packetdoctor.dc.invalid_packet", Severity.DANGER, SourceKind.SERVER, null);
				tips(b, "packetdoctor.tip.match_version_mods");
			}
			case "multiplayer.disconnect.generic", "disconnect.endOfStream", "disconnect.lost" -> {
				if (r == null) return false;
				closed(b, r);
			}
			case "disconnect.timeout" -> {
				if (r != null) {
					timeout(b, r);
				} else {
					explain(b, "packetdoctor.dc.timeout", Severity.WARNING, SourceKind.NETWORK, null, "");
					timeoutTips(b);
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	// --- Kick messages that aren't vanilla (plugins, proxies, anti-cheats) ----------

	private static void customKick(Diagnosis.Builder b, Reason why, @Nullable DisconnectRecord r) {
		KickPatterns.KickPattern p = KickPatterns.match(why.text);
		if (p == null) {
			explain(b, "packetdoctor.dc.custom", Severity.INFO, SourceKind.SERVER, null, why.text);
			tips(b, "packetdoctor.tip.read_message");
			return;
		}
		if (p.special() == KickPatterns.Special.TIMEOUT && r != null) {
			timeout(b, r);
			return;
		}
		explain(b, p.base(), p.severity(), p.kind(), null);
		for (String tip : p.tips()) b.tip(t(tip));
		switch (p.special()) {
			case RISKY_MODS -> riskyMods(b);
			case BLAME_RATE -> blameFromWarnings(b, r, "out.rate");
			case BLAME_OUTGOING -> blameFromWarnings(b, r, "out.");
			case TIMEOUT -> timeoutTips(b);
			default -> {
			}
		}
	}

	// --- What a Packet Doctor server said ---------------------------------------------

	/**
	 * The server confirmed who disconnected us and why; this replaces guesses. Its warnings
	 * about our packets are shown in the player's wording and language.
	 */
	private static void serverSaid(Diagnosis.Builder b, ServerExplanation e, Reason why) {
		if (e.crashHeadline() != null) {
			// The server crashed and told us why before it went down.
			b.severity(Severity.DANGER).headline(t("packetdoctor.dc.server_crash.headline"))
					.summary(t("packetdoctor.dc.server_crash.summary", e.crashHeadline(), e.crashSource() != null ? e.crashSource() : "?"))
					.source(SourceKind.SERVER, t("packetdoctor.who.server_crash", e.crashSource() != null ? e.crashSource() : "?"), t("packetdoctor.dc.server_crash.note"));
			b.tipFirst(t("packetdoctor.tip.server_crash_tell"));
			b.tipFirst(t("packetdoctor.tip.server_crash_wait"));
			return;
		}
		switch (e.kickerKind()) {
			case "MOD" -> {
				String kicker = e.kicker() != null ? e.kicker() : t("packetdoctor.who.a_server_mod");
				b.headline(t("packetdoctor.dc.server_mod.headline", kicker))
						.summary(t("packetdoctor.dc.server_mod.summary", kicker, e.reason() != null ? e.reason() : why.text))
						.source(SourceKind.SERVER, t("packetdoctor.who.server_mod", kicker), t("packetdoctor.dc.server_mod.note"));
			}
			case "OPERATOR" -> b.headline(t("packetdoctor.dc.operator.headline"))
					.source(SourceKind.SERVER, t("packetdoctor.who.operator"), t("packetdoctor.note.server_confirmed"));
			default -> {
				if (b.sourceKind() == SourceKind.SERVER) b.source(SourceKind.SERVER, null, t("packetdoctor.note.server_confirmed"));
			}
		}
		if (e.reason() != null && !"MOD".equals(e.kickerKind())) b.summary(b.summary() + " " + t("packetdoctor.dc.server_reason", e.reason()));
		if (e.serverError() != null) b.summary(b.summary() + " " + t("packetdoctor.dc.server_error", e.serverError()));

		boolean noticedOurs = false;
		for (WarningData data : e.warnings()) {
			Warning w;
			try {
				w = data.withNs(Warning.CLIENT).toWarning();
			} catch (RuntimeException ex) {
				continue; // a warning kind from a newer version
			}
			b.warnings(List.of(t("packetdoctor.dc.server_prefix") + " " + w.oneLine()));
			if (!noticedOurs && w.severity == Severity.DANGER && (w.id.startsWith("out.") || w.id.startsWith("srv."))) {
				noticedOurs = true;
				b.tipFirst(t("packetdoctor.tip.server_noticed", w.title()));
			}
		}
	}

	// --- Other ways a connection ends ------------------------------------------------

	private static void timeout(Diagnosis.Builder b, DisconnectRecord r) {
		String silence = r.silenceMs() > 1000 ? " " + t("packetdoctor.dc.timeout.silence", Math.round(r.silenceMs() / 1000.0)) : "";
		explain(b, "packetdoctor.dc.timeout", Severity.WARNING, SourceKind.NETWORK, null, silence);
		if (hasWarning(r, "in.rate") || hasWarning(r, "in.size")) b.tipFirst(t("packetdoctor.tip.flood_before"));
		timeoutTips(b);
	}

	private static void timeoutTips(Diagnosis.Builder b) {
		tips(b, "packetdoctor.tip.check_internet", "packetdoctor.tip.cable_no_vpn", "packetdoctor.tip.server_lagging");
	}

	private static void networkError(Diagnosis.Builder b, DisconnectRecord r, Reason why) {
		Throwable error = r.networkError();
		String text = (why.text + " " + chainText(error)).toLowerCase(Locale.ROOT);
		ModBlame.Culprit mod = error != null ? ModBlame.prime(ModBlame.blame(error, true)) : null;

		if (containsAny(text, "too big", "too large", "badly compressed", "8388608", "2097152", "exceeds", "maximum")) {
			explain(b, "packetdoctor.dc.too_big", Severity.DANGER, SourceKind.SERVER, t("packetdoctor.who.world_or_items"));
			tips(b, "packetdoctor.tip.admin_remove_item", "packetdoctor.tip.every_join");
		} else if (containsAny(text, "connection reset", "forcibly closed", "broken pipe", "connection abort", "connection refused")) {
			explain(b, "packetdoctor.dc.conn_cut", Severity.WARNING, SourceKind.NETWORK, null);
			timeoutTips(b);
		} else if (containsAny(text, "cipher", "decrypt", "badpadding", "encryption")) {
			explain(b, "packetdoctor.dc.crypto", Severity.WARNING, SourceKind.NETWORK, null);
			tips(b, "packetdoctor.tip.disable_ssl_scan");
		} else if (error instanceof io.netty.handler.codec.EncoderException || text.contains("encode")) {
			explain(b, "packetdoctor.dc.encode", Severity.DANGER, SourceKind.MINECRAFT, null);
			if (mod != null) b.source(SourceKind.MOD, mod.label(), t("packetdoctor.note.error_in_mod"));
			tips(b, "packetdoctor.tip.update_named_mod");
		} else {
			String packet = packetFrom(text);
			String channel = channelFrom(text);
			String channelMod = channel == null ? null : ModBlame.modForNamespace(channel.substring(0, channel.indexOf(':')));
			String packetText = packet != null ? " " + t("packetdoctor.dc.decode.packet", PacketNames.describe(packet)) : "";
			explain(b, "packetdoctor.dc.decode", Severity.DANGER, SourceKind.SERVER, null, packetText);
			if (mod != null) {
				b.source(SourceKind.MOD, mod.label(), t("packetdoctor.note.decode_mod"));
				b.tip(t("packetdoctor.tip.update_mod_exact", mod.name()));
			} else if (channelMod != null) {
				b.source(SourceKind.MOD, ModBlame.name(channelMod) + " (" + channelMod + ")", t("packetdoctor.note.decode_channel", channel));
				b.tip(t("packetdoctor.tip.install_same_version", ModBlame.name(channelMod)));
			}
			tips(b, "packetdoctor.tip.exact_version", "packetdoctor.tip.exact_modpack");
		}
	}

	private static void closed(Diagnosis.Builder b, DisconnectRecord r) {
		explain(b, "packetdoctor.dc.closed", Severity.WARNING, SourceKind.SERVER, null);
		if (r.silenceMs() > 8000) {
			b.summary(b.summary() + " " + t("packetdoctor.dc.closed.silent", Math.round(r.silenceMs() / 1000.0)));
		}
		Warning bad = firstOutbound(r, Severity.DANGER);
		if (bad != null) {
			b.summary(t("packetdoctor.dc.closed.rejected", bad.title()) + " " + b.summary());
			if (bad.mod() != null) b.source(SourceKind.MOD, bad.mod(), t("packetdoctor.note.closed_mod"));
		}
		tips(b, "packetdoctor.tip.wait_check_status", "packetdoctor.tip.one_place", "packetdoctor.tip.every_server");
	}

	private static void packetError(Diagnosis.Builder b, DisconnectRecord r) {
		Throwable error = r.packetError() != null ? r.packetError() : r.networkError();
		ModBlame.Culprit mod = error != null ? ModBlame.prime(ModBlame.blame(error, true)) : null;
		String packet = r.failedPacket() != null ? PacketNames.describe(r.failedPacket()) : t("packetdoctor.dc.packet_error.unknown");
		explain(b, "packetdoctor.dc.packet_error", Severity.DANGER, SourceKind.MINECRAFT, null, packet);
		if (mod != null) {
			b.source(SourceKind.MOD, mod.label(), t("packetdoctor.note.error_in_mod_handling"));
			b.tip(t("packetdoctor.tip.update_mod", mod.name())).tip(t("packetdoctor.tip.remove_mod_report", mod.name()));
		} else {
			tips(b, "packetdoctor.tip.match_version_exact", "packetdoctor.tip.tell_staff_packet");
		}
	}

	private static void modDisconnect(Diagnosis.Builder b, DisconnectRecord r, Reason why) {
		ModBlame.Culprit mod = ModBlame.prime(ModBlame.blame(r.stack(), true));
		String name = mod != null ? mod.name() : t("packetdoctor.who.a_mod");
		b.severity(Severity.WARNING)
				.headline(t("packetdoctor.dc.mod.headline", name))
				.summary(t("packetdoctor.dc.mod.summary", name, why.text))
				.source(SourceKind.MOD, mod != null ? mod.label() : null, t("packetdoctor.dc.mod.note"));
		b.tip(t("packetdoctor.tip.mod_same_version", name)).tip(t("packetdoctor.tip.update_or_remove", name));
	}

	private static void generic(Diagnosis.Builder b, Reason why) {
		explain(b, "packetdoctor.dc.generic", Severity.WARNING, SourceKind.UNKNOWN, null, why.text);
		tips(b, "packetdoctor.tip.rejoin_send_report");
	}

	private static void connectFailure(Diagnosis.Builder b, Reason why) {
		String lower = why.text.toLowerCase(Locale.ROOT);
		if (why.key.equals("disconnect.unknownHost") || containsAny(lower, "unknown host", "unknownhost", "unresolved", "no such host",
				"name or service not known", "nodename nor servname")) {
			explain(b, "packetdoctor.dc.connect_unknown_host", Severity.WARNING, SourceKind.NETWORK, t("packetdoctor.who.dns"));
			tips(b, "packetdoctor.tip.check_address", "packetdoctor.tip.check_site_address", "packetdoctor.tip.change_dns");
		} else if (lower.contains("refused")) {
			explain(b, "packetdoctor.dc.connect_refused", Severity.WARNING, SourceKind.SERVER, null);
			tips(b, "packetdoctor.tip.check_port", "packetdoctor.tip.own_server_running", "packetdoctor.tip.wait_restart");
		} else if (containsAny(lower, "timed out", "timeout")) {
			explain(b, "packetdoctor.dc.connect_timeout", Severity.WARNING, SourceKind.NETWORK, null);
			tips(b, "packetdoctor.tip.check_address_port", "packetdoctor.tip.school_network", "packetdoctor.tip.port_forward");
		} else if (containsAny(lower, "network is unreachable", "no route to host", "network unreachable")) {
			explain(b, "packetdoctor.dc.connect_unreachable", Severity.WARNING, SourceKind.NETWORK, t("packetdoctor.who.your_connection"));
			tips(b, "packetdoctor.tip.check_internet_vpn");
		} else if (containsAny(lower, "connection reset", "forcibly closed")) {
			explain(b, "packetdoctor.dc.connect_reset", Severity.WARNING, SourceKind.NETWORK, null);
			tips(b, "packetdoctor.tip.disable_av");
		} else {
			explain(b, "packetdoctor.dc.connect_generic", Severity.WARNING, SourceKind.NETWORK, null, why.text);
			tips(b, "packetdoctor.tip.check_address_online");
		}
	}

	// --- Helpers --------------------------------------------------------------------

	/** Blames the mod named on a recent warning with the given id prefix, if any. */
	private static void blameFromWarnings(Diagnosis.Builder b, @Nullable DisconnectRecord r, String idPrefix) {
		if (r != null) {
			for (Warning w : r.recentWarnings()) {
				if (w.id.startsWith(idPrefix) && w.mod() != null) {
					b.source(SourceKind.MOD, w.mod(), t("packetdoctor.note.seen_mod", w.title()));
					b.tipFirst(t("packetdoctor.tip.turn_off_mod", w.mod()));
					return;
				}
			}
		}
		b.source(SourceKind.YOU, t("packetdoctor.who.your_game"), b.sourceNote());
		riskyMods(b);
	}

	private static void riskyMods(Diagnosis.Builder b) {
		List<String> risky = ModBlame.installedRiskyMods();
		if (!risky.isEmpty()) b.tip(t("packetdoctor.tip.risky_mods", String.join(", ", risky)));
	}

	/**
	 * Points at serious problems in what the game sent (or network errors) right before the
	 * end, since those can be the real reason behind a vague message. Server-side oddities
	 * are listed in the warnings but not blamed here.
	 */
	private static void mentionRecentProblems(Diagnosis.Builder b, DisconnectRecord r) {
		long dangers = r.recentWarnings().stream()
				.filter(w -> w.severity == Severity.DANGER && (w.id.startsWith("out.") || w.id.startsWith("net.")))
				.count();
		if (dangers > 0 && b.sourceKind() != SourceKind.MOD) b.tip(t("packetdoctor.tip.recent_problems", dangers));
	}

	private static boolean hasWarning(DisconnectRecord r, String prefix) {
		for (Warning w : r.recentWarnings()) if (w.id.startsWith(prefix)) return true;
		return false;
	}

	private static @Nullable Warning firstOutbound(DisconnectRecord r, Severity min) {
		for (Warning w : r.recentWarnings()) {
			if (w.id.startsWith("out.") && w.severity.atLeast(min) && System.currentTimeMillis() - w.lastSeen() < 15_000) return w;
		}
		return null;
	}

	private static boolean containsAny(String text, String... needles) {
		for (String n : needles) if (text.contains(n)) return true;
		return false;
	}

	private static String chainText(@Nullable Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = t; c != null && sb.length() < 4000; c = c.getCause() == c ? null : c.getCause()) {
			sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(' ');
		}
		return sb.toString();
	}

	private static @Nullable String packetFrom(String text) {
		Matcher m = PACKET_ID.matcher(text);
		return m.find() ? m.group(1) : null;
	}

	/** A non-minecraft "namespace:path" in the text, e.g. a mod's network channel. */
	private static @Nullable String channelFrom(String text) {
		Matcher m = CHANNEL.matcher(text);
		while (m.find()) {
			String ns = m.group(1);
			if (!ns.equals("minecraft") && !ns.equals("java") && !ns.startsWith("io.") && !ns.equals("http") && !ns.equals("https")) {
				return ns + ":" + m.group(2);
			}
		}
		return null;
	}

	/** Details for modders and server admins; deliberately English, like stack traces. */
	static String technical(Component title, DisconnectionDetails details, @Nullable DisconnectRecord r) {
		StringBuilder sb = new StringBuilder();
		sb.append("Screen: ").append(title.getString()).append('\n');
		Reason why = Reason.of(details.reason());
		sb.append("Reason: ").append(details.reason().getString());
		if (!why.key.isEmpty()) sb.append("  [").append(why.key).append(']');
		sb.append('\n');
		if (r == null) {
			sb.append("No connection data was recorded (the connection never fully started).\n");
			return sb.toString();
		}
		sb.append("Ended by: ").append(r.trigger()).append('\n');
		sb.append("Server: ").append(r.server().isEmpty() ? "?" : r.server());
		if (r.serverBrand() != null) sb.append("  (software: ").append(r.serverBrand()).append(')');
		sb.append('\n');
		sb.append(String.format(Locale.ROOT, "Last data from server: %.1f s before the end%n", r.silenceMs() / 1000.0));
		if (r.outboundPeak() != null) sb.append("Busiest outgoing second: ").append(r.outboundPeakRate()).append("/s - ").append(r.outboundPeak()).append('\n');
		if (r.transferTarget() != null) sb.append("Transfer target: ").append(r.transferTarget()).append('\n');
		ServerExplanation e = r.serverExplanation();
		if (e != null) {
			sb.append("Server explanation (Packet Doctor ").append(e.modVersion()).append("): kicker=").append(e.kickerKind());
			if (e.kicker() != null) sb.append(" ").append(e.kicker());
			if (e.reason() != null) sb.append(", reason=").append(e.reason());
			if (e.serverError() != null) sb.append(", server error=").append(e.serverError());
			sb.append(", ").append(e.warnings().size()).append(" warning(s)\n");
		}

		sb.append("\nDisconnect requested by:\n");
		appendFrames(sb, r.stack(), 14, true);
		if (r.networkError() != null) {
			sb.append("\nNetwork error:\n");
			appendThrowable(sb, r.networkError());
		}
		if (r.packetError() != null) {
			sb.append("\nPacket handling error").append(r.failedPacket() != null ? " (" + r.failedPacket() + ")" : "").append(":\n");
			appendThrowable(sb, r.packetError());
		}
		sb.append("\nLast packets (newest first):\n");
		List<PacketRecord> packets = r.lastPackets();
		for (int i = 0; i < Math.min(30, packets.size()); i++) sb.append("  ").append(packets.get(i).oneLine()).append('\n');
		return sb.toString();
	}

	static void appendThrowable(StringBuilder sb, Throwable t) {
		for (Throwable c : ModBlame.causes(t).reversed()) {
			sb.append("  ").append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
			appendFrames(sb, c.getStackTrace(), 10, false);
		}
		List<ModBlame.Culprit> mods = ModBlame.blame(t, false);
		if (!mods.isEmpty()) {
			sb.append("  Mods in this error: ");
			for (int i = 0; i < mods.size(); i++) {
				if (i > 0) sb.append(", ");
				sb.append(mods.get(i).label()).append(" x").append(mods.get(i).frames());
			}
			sb.append('\n');
		}
	}

	static void appendFrames(StringBuilder sb, StackTraceElement[] frames, int max, boolean skipSelf) {
		int shown = 0;
		for (StackTraceElement f : frames) {
			if (shown >= max) {
				sb.append("      ...\n");
				break;
			}
			String mod = ModBlame.modForFrame(f);
			if (skipSelf && "packetdoctor".equals(mod)) continue;
			sb.append("      at ").append(f);
			if (mod != null && !mod.equals("java") && !mod.equals("minecraft")) sb.append("  [").append(mod).append(']');
			sb.append('\n');
			shown++;
		}
	}
}
