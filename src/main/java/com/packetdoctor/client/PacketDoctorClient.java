package com.packetdoctor.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.packetdoctor.Config;
import com.packetdoctor.PacketDoctor;
import com.packetdoctor.diagnose.CrashHandler;
import com.packetdoctor.diagnose.Diagnosis;
import com.packetdoctor.diagnose.Reports;
import com.packetdoctor.net.PacketMonitor;
import com.packetdoctor.net.Severity;
import com.packetdoctor.net.Warning;
import com.packetdoctor.network.ExplanationPayload;
import com.packetdoctor.network.ServerStatus;
import com.packetdoctor.network.StatusPayload;
import com.packetdoctor.ui.DiagnosisScreen;
import com.packetdoctor.ui.PacketDoctorScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Player-side entrypoint. Wires the {@link PacketMonitor} to the game tick, registers the
 * "open Packet Doctor" key, shows warnings as toasts, and brings up the explanation of
 * the last crash when the game starts again.
 */
public final class PacketDoctorClient implements ClientModInitializer {
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(7000L);
	private static final int MAX_HISTORY = 30;

	private static @Nullable Config config;
	private static KeyMapping openKey;
	private static final List<Diagnosis> HISTORY = new ArrayList<>();
	private static @Nullable Diagnosis pendingCrash;

	@Override
	public void onInitializeClient() {
		config = Config.load();
		CrashHandler.keepReports = config.keepReports;
		PacketMonitor.init(config.logSize, () -> config().toastCooldownSeconds, PacketDoctorClient::notify);

		// Registering receivers is what tells a Packet Doctor server this game understands its
		// explanations. The explanation itself is read on the network thread by PacketMonitor,
		// because it must be seen before the disconnect packet right behind it.
		ClientPlayNetworking.registerGlobalReceiver(ExplanationPayload.TYPE, (payload, context) -> {
		});
		ClientConfigurationNetworking.registerGlobalReceiver(ExplanationPayload.TYPE, (payload, context) -> {
		});
		// How a Packet Doctor server is running, so lag can be put down to the server or not.
		ClientPlayNetworking.registerGlobalReceiver(StatusPayload.TYPE, (payload, context) -> {
			ServerStatus status = ServerStatus.fromJson(payload.json());
			if (status != null) LagWatcher.get().onStatus(status);
		});

		KeyMapping.Category category = KeyMapping.Category.register(PacketDoctor.id("main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.packetdoctor.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, category));

		pendingCrash = Reports.loadPendingCrash(Reports.CLIENT_CRASH);
		if (pendingCrash != null) addHistory(pendingCrash);

		ClientTickEvents.END_CLIENT_TICK.register(PacketDoctorClient::tick);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof TitleScreen && config().titleScreenButton) addTitleButton(screen);
		});

		PacketDoctor.LOGGER.info("Packet Doctor {} ready{}", PacketDoctor.version(),
				pendingCrash != null ? " (the last session crashed; explanation pending)" : "");
	}

	private static void tick(Minecraft client) {
		PacketMonitor.get().tick(client);
		LagWatcher.get().tick(client);

		while (openKey.consumeClick()) {
			client.gui.setScreen(new PacketDoctorScreen(client.gui.screen()));
		}

		Diagnosis crash = pendingCrash;
		if (crash != null && config().openCrashExplanation && client.gui.screen() instanceof TitleScreen title
				&& client.gui.overlay() == null) {
			showCrash(client, crash, title);
		}
	}

	private static void showCrash(Minecraft client, Diagnosis crash, TitleScreen back) {
		pendingCrash = null;
		Reports.clearPendingCrash(Reports.CLIENT_CRASH);
		client.gui.setScreen(new DiagnosisScreen(crash, () -> back));
	}

	/** A small button in the top-left of the title screen; highlighted after a crash. */
	private static void addTitleButton(Screen screen) {
		Minecraft client = Screens.getMinecraft(screen);
		Diagnosis crash = pendingCrash;
		Component label = crash != null
				? Component.translatable("packetdoctor.title_button.crash").withColor(Severity.DANGER.color & 0xFFFFFF)
				: Component.translatable("packetdoctor.title_button");
		Button button = Button.builder(label, b -> {
			Diagnosis c = pendingCrash;
			if (c != null && screen instanceof TitleScreen title) showCrash(client, c, title);
			else client.gui.setScreen(new PacketDoctorScreen(screen));
		}).bounds(4, 4, crash != null ? 130 : 90, 20)
				.tooltip(Tooltip.create(Component.translatable("packetdoctor.title_button.tooltip")))
				.build();
		Screens.getWidgets(screen).add(button);
	}

	/** Shows a warning as a toast, if the player wants that. Safe to call from any thread. */
	public static void notify(Warning w) {
		Config c = config;
		if (c == null || !c.toasts) return;
		if (w.severity == Severity.INFO && !c.toastInfo) return;
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.gui.screen() instanceof PacketDoctorScreen) return; // already looking at it
			String key = openKey.isUnbound() ? Component.translatable("packetdoctor.toast.the_key").getString()
					: openKey.getTranslatedKeyMessage().getString();
			String detail = w.detail();
			if (detail.length() > 70) detail = detail.substring(0, 67) + "...";
			SystemToast.addOrUpdate(client.gui.toastManager(), TOAST,
					Component.literal(w.title()).withColor(w.severity.color & 0xFFFFFF),
					Component.translatable("packetdoctor.toast.message", detail, key));
		});
	}

	private static final SystemToast.SystemToastId LAG_TOAST = new SystemToast.SystemToastId(7001L);

	/** Lag has started: say where it comes from (any thread). */
	public static void notifyLag(LagWatcher.Cause cause, LagWatcher.Finding finding) {
		Config c = config;
		if (c == null || !c.toasts || !c.lagToasts) return;
		Minecraft client = Minecraft.getInstance();
		client.execute(() -> {
			if (client.gui.screen() instanceof PacketDoctorScreen) return;
			String key = openKey.isUnbound() ? Component.translatable("packetdoctor.toast.the_key").getString()
					: openKey.getTranslatedKeyMessage().getString();
			SystemToast.addOrUpdate(client.gui.toastManager(), LAG_TOAST,
					Component.translatable("packetdoctor.lag.toast", cause.label()).withColor(finding.severity().color & 0xFFFFFF),
					Component.translatable("packetdoctor.lag.toast.body", finding.shortText(), key));
		});
	}

	public static Config config() {
		Config c = config;
		if (c == null) throw new IllegalStateException("Packet Doctor isn't initialized yet");
		return c;
	}

	/** Explanations shown this session (and the last crash), newest first. */
	public static synchronized List<Diagnosis> history() {
		return List.copyOf(HISTORY);
	}

	public static synchronized void addHistory(Diagnosis d) {
		HISTORY.addFirst(d);
		while (HISTORY.size() > MAX_HISTORY) HISTORY.removeLast();
	}

	public static KeyMapping openKey() {
		return openKey;
	}
}
