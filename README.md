# Packet Doctor

A Fabric mod for Minecraft 26.3 that tells you, in plain language, why something went wrong: why you were disconnected, why the game or server crashed, and why you're lagging.

One jar works on both sides, and neither side needs the other:

- **On a player's game:** packet warnings, disconnect and crash explanations, and a lag check that says whether the lag is the server, your connection or your own game.
- **On a Fabric server:** reads the console and logs to explain crashes, start-up failures and lag (TPS drops), watches every player's connection, and offers an API for other server mods.
- **Both installed:** the server tells the player's game exactly who disconnected them and why, including when the server crashes, and shares its TPS so the player can see whether the server is the problem.

Players without the mod can join a server that has it; they are never sent anything extra. Players with the mod can join any server, modded or not. (Paper/Spigot servers can't run Fabric mods; players there still get all the client-side features.)

## For players

Press **K** to open the Packet Doctor window.

**Why am I lagging? (Lag tab)** Packet Doctor checks three things all the time and says which one is the problem:

| | What it looks at |
|---|---|
| **The server** | Its TPS (exact when the server runs Packet Doctor, which also says what is slowing it down; estimated over 30 seconds on other servers), and freezes. A server freeze is spotted when game data stops but pings are still answered at once, so it can't be mistaken for your connection. |
| **Your connection** | Your real ping, how much it jumps around, and pings that never come back, measured once a second with Minecraft's own debug ping. |
| **Your game** | Low FPS, moments where the game froze, Java memory pauses, and memory running out. Minecraft lowering its own frame rate while you're AFK or in a menu doesn't count. |

When lag starts, a pop-up says where it comes from, e.g. *"Lag: the server - Server froze for 1.7 s"*. The Lag tab has the details, what to do about it, and the recent lag history.

**Packet warnings (Warnings tab).** Every packet in and out is checked. A warning says what was seen, what it means and who sent it. Examples:

- **From the server:** packets close to the 8 MB limit; "chunk ban" / "book ban" sized chunks and items; huge chat or title messages; teleports, explosions or velocities that aren't real numbers; particle, sound, explosion, entity and chat floods; data for mods you don't have.
- **From your game:** positions or camera angles the server will reject; chat that's too long or has illegal characters; being close to a spam kick; oversized books and signs; packet rates that trip server limits. Where possible, the mod that sent the packet is named.

**Disconnect explanations.** The disconnect screen gains a one-line explanation and a **"Why was I disconnected?"** button: what happened, where it came from (the server, the network, a specific mod, Minecraft or you), what you can try, warnings from just before, and the full technical details. It recognises every vanilla kick reason and about 20 kinds of messages from Paper/Spigot, Velocity/BungeeCord, ViaVersion, anti-cheats (Grim, Vulcan, Spartan, NoCheatPlus and others), anti-VPN, ban and whitelist plugins, and mod checks. On a server running Packet Doctor it also names the operator or mod that kicked you, or tells you the server crashed and why.

**Crash explanations.** When the game crashes, Packet Doctor works out what kind of crash it was (out of memory, a mod failing to load, a mod built for another version, graphics drivers, endless loops, broken world objects, resource packs) and names the most likely mod. The explanation opens the next time the game starts.

**Other tabs:** Live (every packet), Stats (totals per packet type), History (past disconnects and crashes) and Settings. **Export log** saves the packet log to a text file for reporting problems. With [Mod Menu](https://modrinth.com/mod/modmenu), its Configure button opens the settings.

Explanations and exports are saved in `.minecraft/packetdoctor/reports`; **Copy report** puts one on the clipboard.

## For server admins

Put the same jar in the server's `mods` folder (Fabric API is required).

**Crashes, explained from the console and logs.** Packet Doctor reads everything the server prints and explains:

- **Crashes:** what kind, which mod, and the console warnings just before.
- **Watchdog freezes** (a tick longer than `max-tick-time`): what the stuck tick was doing, e.g. "stuck on: commands and datapack functions".
- **Start-up failures:** e.g. "the server's port is already in use", broken data packs or world data.
- **A server that stopped without any crash report:** worked out on the next start from the previous log, Java's own crash files (`hs_err_pid*.log`) and Packet Doctor's notes - killed by a host panel or the system, out of memory, Java itself crashing, or a frozen tick.

Each explanation is printed in the console and saved in `packetdoctor/reports`. Players online at the time are told the server crashed, and why.

**Lag (TPS drops).** Every tick is timed. While a tick runs slow, Packet Doctor samples what the server is doing and sorts it into plain causes: entities, mob AI, pathfinding, hoppers, redstone, block updates, chunk loading, world generation, saving, commands, a specific mod, Java garbage collection, and more, with examples like the mob types involved. Lag spikes are written to the console with their cause; long ones and sustained overload also alert operators in chat.

**Console problems.** Warnings and errors are grouped (the same error printed 500 times is one entry) and explained: damaged chunks, failed saves, out of memory, mod compatibility (mixin) warnings, data pack errors, missing mod content, ports in use, offline mode, and more, with the mod they came from.

**Players' connections.** Every player's packets get the same checks as the player's own game, worded for admins, with recent disconnects and who caused them.

### Commands (operators)

| Command | Shows |
|---|---|
| `/packetdoctor` | Everyone online: packet rates, warnings, and who has Packet Doctor |
| `/packetdoctor lag` | TPS, milliseconds per tick, what slow ticks are spent on, and recent lag spikes |
| `/packetdoctor console [clear]` | Console warnings and errors, grouped and explained (hover for advice) |
| `/packetdoctor crash` | The last crash or unexpected stop, explained |
| `/packetdoctor player <name>` | One player's traffic and warnings |
| `/packetdoctor disconnects [count]` | Recent disconnects and why |
| `/packetdoctor export <name>` | Saves a player's packet log to `packetdoctor/reports` |
| `/packetdoctor clear <name>` | Clears a player's warnings |

### Settings (`config/packetdoctor-server.json`)

| Setting | Default | Meaning |
|---|---|---|
| `sendExplanations` | `true` | Tell players' Packet Doctor why they were disconnected |
| `sharePerformance` | `true` | Send TPS and lag causes to players with Packet Doctor, for their Lag tab |
| `monitorPackets` | `true` | Check players' packets |
| `lagMonitor` | `true` | Time ticks and find what slow ones are spent on |
| `lagSpikeMs` | `300` | A tick at least this slow counts as a lag spike |
| `lagSampleIntervalMs` | `10` | How often a slow tick is sampled |
| `logLagSpikeMs` | `1000` | Spikes at least this long are written to the console (0 = never) |
| `alertLagSpikeMs` | `2000` | Spikes at least this long alert operators (0 = never) |
| `captureConsole` | `true` | Read the console to explain errors, crashes and lag |
| `consoleLines` | `1000` | Console lines remembered |
| `alertOps` | `true` | Chat alerts for operators |
| `alertMinSeverity` | `DANGER` | Lowest player warning level that alerts: `INFO`, `WARNING` or `DANGER` |
| `alertCooldownSeconds` | `60` | How soon the same alert can repeat |
| `logWarnings` | `true` | Also write player warnings to the console |
| `playerLogSize` | `2000` | Packets remembered per player |
| `keepDisconnects` | `100` | Disconnects remembered |
| `keepReports` | `30` | Report files kept |

## API for server mods

`com.packetdoctor.api` gives other server-side mods what Packet Doctor sees. It adds no screens. Check that the mod is loaded first, so it stays optional:

```java
if (FabricLoader.getInstance().isModLoaded("packetdoctor") && PacketDoctorApi.VERSION >= 2) {
    PacketDoctorApi.performance().ifPresent(p -> myPanel.showTps(p.tps1m()));
}
```

**Reading** (any thread; everything returned is an immutable snapshot):

| Method | Returns |
|---|---|
| `performance()` | TPS (10 s, 1 min, 5 min), ms per tick, what slow ticks are spent on, loaded chunks, entities |
| `lagSpikes(max)` | Recent lag spikes with their causes and the console lines printed during them |
| `console(max, minLevel)` | The latest console lines |
| `consoleProblems()` | Console warnings and errors, grouped and explained |
| `lastServerCrash()` | The last crash or unexpected stop, with its kind (`CRASH`, `WATCHDOG`, `OUT_OF_MEMORY`, `JVM_CRASH`, `STARTUP_FAILED`, `STOPPED_UNEXPECTEDLY`) |
| `players()`, `player(uuid)`, `hasClientMod(uuid)` | Each player's packet rates, totals and warnings, and whether their game has Packet Doctor |
| `warnings(uuid)`, `recentWarnings(max)` | Player warnings with title, detail and advice |
| `recentDisconnects(max)`, `lastDisconnect(uuid)` | Who disconnected whom, and why |
| `settings()` | The server settings |

**Acting:** `kick(player, message, modId, reason)` kicks and credits your mod (players with Packet Doctor see your mod named and your reason); `setKickReason(...)` does the same for a kick you do yourself; `clearWarnings`, `clearConsoleProblems`, `exportPacketLog`, `set(name, value)`.

**Events** (`PacketDoctorEvents`, on the server thread): `LAG_SPIKE`, `SERVER_OVERLOADED`, `SERVER_RECOVERED`, `CONSOLE_PROBLEM`, `SERVER_CRASH`, `PLAYER_WARNING`, `PLAYER_DISCONNECT`.

```java
PacketDoctorEvents.LAG_SPIKE.register(spike -> myLog.add(spike.summary()));
```

## How blame is decided

Stack traces are matched to mods in two ways: Fabric's Mixin names injected methods `handler$...$modid$...`, so code a mod injected into Minecraft still names that mod; and each class is looked up in the mod jars (nested jars included). Minecraft, Java, Fabric Loader and Fabric API are on nearly every stack, so they are only blamed when nothing else is found. Console lines are matched to mods by their exception or by the logger's name.

## Player settings

In the Settings tab, or `config/packetdoctor.json`: warning pop-ups (`toasts`, `toastInfo`, `toastCooldownSeconds`), `lagToasts`, `measurePing`, `titleScreenButton`, `openCrashExplanation`, `logSize` (packets kept in the Live log and exports) and `keepReports`.

## Translating

All text players and admins read is in `assets/packetdoctor/lang/en_us.json`. Add a file with the same keys for another language; anything missing falls back to English. The build fails if the code uses a key the English file doesn't have.

## Building

Requires JDK 25.

```
./gradlew build          # jar in build/libs/ (also checks translations)
./gradlew runServer      # dev server in run-server/
./gradlew runClient -PquickPlay=localhost   # dev client that joins it
```

Dev runs also load a small test mod (`src/testmod`, never in the jar) with `/pdtest crash`, `/pdtest error`, `/pdtest kick <player>` and `/pdtest api` for testing crash, console and API handling.
