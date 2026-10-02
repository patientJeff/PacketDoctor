package dev.pdtest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.File;

/**
 * Development only: takes a real in-game screenshot when the file {@code pdtest-screenshot}
 * appears in the game folder, so tests can see exactly what the game shows (toasts included)
 * without touching the window.
 */
public final class PdTestClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			File trigger = new File(mc.gameDirectory, "pdtest-screenshot");
			if (trigger.exists() && trigger.delete()) Screenshot.grab(mc, false);
			File lag = new File(mc.gameDirectory, "pdtest-lagtab");
			if (lag.exists() && lag.delete()) mc.gui.setScreen(com.packetdoctor.ui.PacketDoctorScreen.lag(null));
		});
	}
}
