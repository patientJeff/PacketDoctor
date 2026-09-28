package com.packetdoctor.client;

import com.packetdoctor.ui.PacketDoctorScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Adds the "Configure" button for Packet Doctor in Mod Menu, opening the settings tab.
 * Loaded only when Mod Menu is installed, via the {@code modmenu} entrypoint.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return PacketDoctorScreen::settings;
	}
}
