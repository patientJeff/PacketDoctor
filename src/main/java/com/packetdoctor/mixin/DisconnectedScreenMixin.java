package com.packetdoctor.mixin;

import com.packetdoctor.diagnose.Diagnosis;
import com.packetdoctor.diagnose.DisconnectExplainer;
import com.packetdoctor.ui.DiagnosisScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a one-line explanation and a "Why was I disconnected?" button to the vanilla
 * disconnect screen, just above its back button.
 */
@Mixin(DisconnectedScreen.class)
public abstract class DisconnectedScreenMixin extends Screen {
	@Shadow
	@Final
	private Screen parent;
	@Shadow
	@Final
	private DisconnectionDetails details;
	@Shadow
	@Final
	private Component buttonText;
	@Shadow
	@Final
	private LinearLayout layout;

	protected DisconnectedScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;allowsMultiplayer()Z"))
	private void packetdoctor$addExplanation(CallbackInfo ci) {
		Diagnosis d = DisconnectExplainer.forScreen(title, details);
		if (d == null) return;

		Component line = Component.literal(d.headline()).withColor(d.severity().color & 0xFFFFFF)
				.append(Component.literal("  (" + d.sourceLabel() + ")").withColor(0xAAAAAA));
		layout.addChild(new MultiLineTextWidget(line, font).setMaxWidth(width - 50).setCentered(true));

		Screen parentScreen = parent;
		Component screenTitle = title;
		DisconnectionDetails screenDetails = details;
		Component back = buttonText;
		layout.addChild(Button.builder(Component.translatable("packetdoctor.disconnect.button"), b ->
				minecraft.gui.setScreen(new DiagnosisScreen(d,
						() -> new DisconnectedScreen(parentScreen, screenTitle, screenDetails, back))))
				.width(200).build());
	}
}
