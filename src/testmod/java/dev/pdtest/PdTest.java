package dev.pdtest;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.packetdoctor.api.ConsoleProblem;
import com.packetdoctor.api.CrashInfo;
import com.packetdoctor.api.LagSpikeInfo;
import com.packetdoctor.api.PacketDoctorApi;
import com.packetdoctor.api.PacketDoctorEvents;
import com.packetdoctor.api.PerformanceInfo;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development-only helper for testing Packet Doctor on a dev server (it is not part of the
 * released jar). {@code /pdtest crash} crashes the next server tick, {@code /pdtest error}
 * logs an error with an exception, {@code /pdtest kick <player>} kicks through the API, and
 * {@code /pdtest api} prints what the API returns. API events are logged as they fire.
 */
public final class PdTest implements ModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("pdtest");
	private static volatile boolean crashNextTick;

	@Override
	public void onInitialize() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (crashNextTick) {
				crashNextTick = false;
				throw new IllegalStateException("Packet Doctor test: a crash in a server tick");
			}
			if (lagMs > 0) simulateHeavyWork(lagMs);
			int spike = spikeMs;
			if (spike > 0) {
				spikeMs = 0;
				simulateHeavyWork(spike);
			}
		});
		PacketDoctorEvents.LAG_SPIKE.register(spike -> LOG.info("API event LAG_SPIKE: {} ms, {} cause(s)", spike.durationMs(), spike.causes().size()));
		PacketDoctorEvents.SERVER_OVERLOADED.register(p -> LOG.info("API event SERVER_OVERLOADED: {} TPS", p.tps10s()));
		PacketDoctorEvents.SERVER_RECOVERED.register(p -> LOG.info("API event SERVER_RECOVERED: {} TPS", p.tps10s()));
		PacketDoctorEvents.CONSOLE_PROBLEM.register(p -> LOG.info("API event CONSOLE_PROBLEM: {} x{} ({})", p.id(), p.count(), p.mod()));
		PacketDoctorEvents.SERVER_CRASH.register(c -> LOG.info("API event SERVER_CRASH: {} - {}", c.kind(), c.headline()));
		PacketDoctorEvents.PLAYER_DISCONNECT.register(d -> LOG.info("API event PLAYER_DISCONNECT: {} {} ({})", d.name(), d.cause(), d.summary()));

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(Commands.literal("pdtest")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("crash").executes(c -> {
					crashNextTick = true;
					return 1;
				}))
				.then(Commands.literal("error").executes(c -> {
					LOG.error("The test mod failed to do something", new IllegalArgumentException("test error, please ignore"));
					return 1;
				}))
				.then(Commands.literal("kick").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
					PacketDoctorApi.kick(EntityArgument.getPlayer(c, "player"), Component.literal("Kicked by the test mod"), "pdtest",
							"testing the API kick");
					return 1;
				})))
				.then(Commands.literal("api").executes(PdTest::api))
				.then(Commands.literal("lag").then(Commands.argument("ms", IntegerArgumentType.integer(0, 500)).executes(c -> {
					lagMs = IntegerArgumentType.getInteger(c, "ms");
					return 1;
				})))
				.then(Commands.literal("spike").then(Commands.argument("ms", IntegerArgumentType.integer(1, 30_000)).executes(c -> {
					spikeMs = IntegerArgumentType.getInteger(c, "ms");
					return 1;
				})))));
	}

	/** Lag for testing: {@code /pdtest lag <ms>} every tick, {@code /pdtest spike <ms>} once. */
	private static volatile int lagMs;
	private static volatile int spikeMs;
	private static volatile double sink;

	private static void simulateHeavyWork(int ms) {
		long end = System.nanoTime() + ms * 1_000_000L;
		double x = 0;
		while (System.nanoTime() < end) x += Math.sqrt(x + 1);
		sink = x;
	}

	private static int api(CommandContext<CommandSourceStack> c) {
		PerformanceInfo p = PacketDoctorApi.performance().orElse(null);
		say(c, "API version " + PacketDoctorApi.VERSION + ", running: " + PacketDoctorApi.isRunning());
		say(c, p == null ? "performance(): empty" : "performance(): " + p.tps10s() + " TPS, " + p.msptAverage() + " ms, " + p.causes().size()
				+ " cause(s), " + p.entities() + " entities");
		java.util.List<LagSpikeInfo> spikes = PacketDoctorApi.lagSpikes(5);
		say(c, "lagSpikes(5): " + spikes.size() + (spikes.isEmpty() ? "" : ", newest: " + spikes.getFirst().summary()));
		java.util.List<ConsoleProblem> problems = PacketDoctorApi.consoleProblems();
		say(c, "consoleProblems(): " + problems.size() + (problems.isEmpty() ? "" : ", first: " + problems.getFirst().title()));
		say(c, "console(5, WARN): " + PacketDoctorApi.console(5, "WARN").size() + " line(s)");
		CrashInfo crash = PacketDoctorApi.lastServerCrash().orElse(null);
		say(c, "lastServerCrash(): " + (crash == null ? "none" : crash.kind() + " - " + crash.headline()));
		return 1;
	}

	private static void say(CommandContext<CommandSourceStack> c, String text) {
		c.getSource().sendSystemMessage(Component.literal(text));
	}
}
