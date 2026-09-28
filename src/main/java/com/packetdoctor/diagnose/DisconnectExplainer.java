package com.packetdoctor.diagnose;

import com.packetdoctor.PacketDoctor;
import com.packetdoctor.client.PacketDoctorClient;
import com.packetdoctor.net.PacketMonitor;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Produces the explanation for a disconnect screen, once per disconnect. Coming back
 * from the explanation opens a fresh copy of the disconnect screen with the same details
 * object, which must reuse the same explanation instead of analysing (and saving) again.
 */
public final class DisconnectExplainer {
	private static @Nullable DisconnectionDetails lastDetails;
	private static @Nullable Diagnosis lastDiagnosis;

	private DisconnectExplainer() {
	}

	public static synchronized @Nullable Diagnosis forScreen(Component title, DisconnectionDetails details) {
		if (details == lastDetails) return lastDiagnosis;
		try {
			Diagnosis d = DisconnectDiagnoser.diagnose(title, details, PacketMonitor.get().claimDisconnect());
			d = Reports.save(d, PacketDoctorClient.config().keepReports);
			PacketDoctorClient.addHistory(d);
			PacketDoctor.LOGGER.info("Disconnect explained: {} -> {}", d.headline(), d.sourceLabel());
			lastDetails = details;
			lastDiagnosis = d;
			return d;
		} catch (RuntimeException e) {
			// The vanilla screen must still work if the explanation fails.
			PacketDoctor.LOGGER.warn("Couldn't explain the disconnect", e);
			return null;
		}
	}
}
