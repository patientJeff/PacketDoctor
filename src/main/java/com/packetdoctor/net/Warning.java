package com.packetdoctor.net;

import com.packetdoctor.Tr;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * One detected problem. Repeats of the same problem (same {@link #key}) are merged into
 * one entry with a count, so a flood shows up as one line rather than thousands.
 *
 * <p>Text is stored as translation keys plus arguments and rendered when shown, so it
 * appears in the viewer's language. The same problem has two wordings: {@link #CLIENT}
 * speaks to the player ("Your game sent...") and {@link #SERVER} to admins ("This player's
 * game sent..."). A server warning sent to a player is shown in the player's wording.
 */
public final class Warning {
	public static final String CLIENT = "packetdoctor.warn.";
	public static final String SERVER = "packetdoctor.swarn.";
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	/** Merge key: the id, plus a suffix when one id covers several things (e.g. per packet type). */
	public final String key;
	/** Message id, e.g. {@code out.move.invalid}. */
	public final String id;
	/** Which wording: {@link #CLIENT} or {@link #SERVER}. */
	public final String ns;
	public final Severity severity;
	public final @Nullable Direction direction;
	/** Packet id the warning is about, e.g. {@code minecraft:level_particles}. */
	public final @Nullable String packetId;
	public final long firstSeen;

	private final String adviceRel;
	private volatile String[] titleArgs;
	private volatile String[] detailArgs;
	private volatile String[] adviceArgs;
	private volatile @Nullable String sourceKey;
	private volatile @Nullable String mod;
	private volatile long lastSeen;
	private volatile int count;

	private Warning(Builder b) {
		this.key = b.key;
		this.id = b.id;
		this.ns = b.ns;
		this.severity = b.severity;
		this.direction = b.direction;
		this.packetId = b.packetId;
		this.adviceRel = b.adviceRel;
		this.titleArgs = b.titleArgs;
		this.detailArgs = b.detailArgs;
		this.adviceArgs = b.adviceArgs;
		this.sourceKey = b.sourceKey;
		this.mod = b.mod;
		this.firstSeen = b.firstSeen;
		this.lastSeen = b.lastSeen;
		this.count = b.count;
	}

	/** A warning worded for the player. */
	public static Builder of(String id, Severity severity) {
		return new Builder(CLIENT, id, severity);
	}

	/** A warning worded for server admins. */
	public static Builder server(String id, Severity severity) {
		return new Builder(SERVER, id, severity);
	}

	void repeat(Warning newer) {
		count++;
		lastSeen = newer.lastSeen;
		titleArgs = newer.titleArgs;
		detailArgs = newer.detailArgs;
		adviceArgs = newer.adviceArgs;
		if (newer.mod != null) mod = newer.mod;
		if (newer.sourceKey != null) sourceKey = newer.sourceKey;
	}

	public String title() {
		return Tr.t(ns + id + ".title", (Object[]) titleArgs);
	}

	public String detail() {
		return Tr.t(ns + id + ".detail", (Object[]) detailArgs);
	}

	public String advice() {
		return Tr.t(ns + adviceRel, (Object[]) adviceArgs);
	}

	/** Who is behind it, e.g. "Mod: Freecam (freecam)" or "The server", or null. */
	public @Nullable String source() {
		if (mod != null) return Tr.t("packetdoctor.source.mod", mod);
		return sourceKey != null ? Tr.t(sourceKey) : null;
	}

	/** The mod named as the cause, as "Name (id)", or null. */
	public @Nullable String mod() {
		return mod;
	}

	void setMod(@Nullable String mod) {
		this.mod = mod;
	}

	public @Nullable String packetName() {
		return packetId == null ? null : PacketNames.friendly(packetId);
	}

	public long lastSeen() {
		return lastSeen;
	}

	public int count() {
		return count;
	}

	public String clock() {
		return CLOCK.format(Instant.ofEpochMilli(lastSeen));
	}

	/** One line for reports: "[Danger] 12:03:01 x3  Title - detail (source)". */
	public String oneLine() {
		StringBuilder sb = new StringBuilder();
		sb.append('[').append(severity.label()).append("] ").append(clock());
		if (count > 1) sb.append(" x").append(count);
		sb.append("  ").append(title()).append(" - ").append(detail());
		String source = source();
		if (source != null) sb.append(" (").append(source).append(')');
		return sb.toString();
	}

	/** The same warning in the other wording (e.g. a server warning shown to the player). */
	public Warning reworded(String newNs) {
		return toData().withNs(newNs).toWarning();
	}

	/** Plain data, for sending to the player's game and for the API. */
	public WarningData toData() {
		return new WarningData(key, id, ns, severity.name(), direction == null ? null : direction.name(), packetId,
				adviceRel, titleArgs.clone(), detailArgs.clone(), adviceArgs.clone(), sourceKey, mod, firstSeen, lastSeen, count);
	}

	static Warning fromData(WarningData d) {
		Builder b = new Builder(d.ns(), d.id(), Severity.valueOf(d.severity()));
		b.key = d.key();
		b.direction = d.direction() == null ? null : Direction.valueOf(d.direction());
		b.packetId = d.packetId();
		b.adviceRel = d.adviceRel();
		b.titleArgs = d.titleArgs();
		b.detailArgs = d.detailArgs();
		b.adviceArgs = d.adviceArgs();
		b.sourceKey = d.sourceKey();
		b.mod = d.mod();
		b.firstSeen = d.firstSeen();
		b.lastSeen = d.lastSeen();
		b.count = d.count();
		return b.build();
	}

	public static final class Builder {
		private final String ns;
		private final String id;
		private final Severity severity;
		private String key;
		private String adviceRel;
		private String[] titleArgs = new String[0];
		private String[] detailArgs = new String[0];
		private String[] adviceArgs = new String[0];
		private @Nullable Direction direction;
		private @Nullable String packetId;
		private @Nullable String sourceKey;
		private @Nullable String mod;
		private long firstSeen = System.currentTimeMillis();
		private long lastSeen = firstSeen;
		private int count = 1;

		private Builder(String ns, String id, Severity severity) {
			this.ns = ns;
			this.id = id;
			this.severity = severity;
			this.key = id;
			this.adviceRel = id + ".advice";
		}

		/** Merge key, when one message id covers several separate problems. */
		public Builder key(String key) {
			this.key = key;
			return this;
		}

		public Builder title(Object... args) {
			this.titleArgs = strings(args);
			return this;
		}

		public Builder detail(Object... args) {
			this.detailArgs = strings(args);
			return this;
		}

		public Builder advice(Object... args) {
			this.adviceArgs = strings(args);
			return this;
		}

		/** Use a different advice text, relative to the wording, e.g. {@code limit.place_recipe}. */
		public Builder adviceFrom(String rel) {
			this.adviceRel = rel;
			return this;
		}

		public Builder packet(Direction direction, @Nullable String packetId) {
			this.direction = direction;
			this.packetId = packetId;
			return this;
		}

		/** A general source, as a translation key such as {@code packetdoctor.source.server}. */
		public Builder source(String sourceKey) {
			this.sourceKey = sourceKey;
			return this;
		}

		/** The mod responsible, as "Name (id)". Ignored when null. */
		public Builder mod(@Nullable String modLabel) {
			if (modLabel != null) this.mod = modLabel;
			return this;
		}

		public Warning build() {
			return new Warning(this);
		}

		private static String[] strings(Object[] args) {
			return Arrays.stream(args).map(String::valueOf).toArray(String[]::new);
		}
	}
}
