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

**Every error file is kept.** Packet Doctor keeps a copy of every file a server error leaves behind in `packetdoctor/errors`. It never deletes anything there itself:

- `console/errors-<date>.log`: every console error, and every warning that came with an exception, with the full stack trace and the mod it came from. It is written as each error happens, so it survives a crash straight after.
- `incidents/<time>-<cause>/`: one folder per crash, watchdog freeze, start-up failure or unexpected stop. It holds the explanation, Minecraft's crash report, the server log at the time (`latest-at-crash.log.gz`) and the mod list with Java and system details. On the next start the folder also gets the finished log of that run and any files that appeared after the crash, such as Java's `hs_err_pid*.log`.

**Lag (TPS drops).** Every tick is timed. While a tick runs slow, Packet Doctor samples what the server is doing and sorts it into plain causes: entities, mob AI, pathfinding, hoppers, redstone, block updates, chunk loading, world generation, saving, commands, a specific mod, Java garbage collection, and more, with examples like the mob types involved. Lag spikes are written to the console with their cause; long ones and sustained overload also alert operators in chat.

**The biggest causes of lag, and what to do.** Packet Doctor also samples normal ticks now and then (`profileServer`), so it always knows what the server spends its time on, even while it keeps up. Each sample notes which block entity or entity was being ticked, where it was, and the nearest player. `/packetdoctor lag` ranks the biggest causes of the last 5 minutes. Each one shows its share of the server's time and its cost in milliseconds per tick, plus the best thing to do. Hover over a cause for the details; click to teleport there or see more. The causes come in four views, and the same time can show up in more than one:

- **Mods:** time spent in each mod's code. It also covers **what that code is doing** (the mod's own classes and methods, by share), **how it gets run** (its own block entities or entities, code it added to a Minecraft class, a Fabric callback such as `ServerTickEvents`, or another mod), what the server was doing at the time, and where. The advice depends on which of these it is.
- **Places:** the busiest chunks, what in them takes the time, what's there right now, and the nearest player. The advice is specific to what is there.
- **Block entities and entities:** cost by type, with how many are loaded and the cost of each one. There's specific advice for hoppers, villagers, dropped items, mob farms, minecart pile-ups, TNT, falling-block dupers, pistons, sculk and more, and generic advice for modded machines and mobs.
- **Kinds of work:** like above, with what's most involved and where. For terrain generation and chunk loading it also names the players exploring the most.

**Lag report files.** `/packetdoctor lag report` writes everything to `packetdoctor/lag/lag-<time>-report.txt`: all the causes with what to do, every mod's methods in full, time by kind of work, types with loaded counts, the busiest chunks and single block entities or entities (with teleport commands), lag near each player, and loaded counts. A report is also written automatically when a lag spike is logged or the server becomes overloaded (`autoLagReports`, at most once per `lagReportCooldownMinutes`). Lag alerts in chat say what the mod's code was doing and list the biggest causes when you hover over them. Click one to see that spike in detail. Send a mod's section of a report to its author: it names exactly which of its code ran.

**Console problems.** Warnings and errors are grouped (the same error printed 500 times is one entry) and explained: damaged chunks, failed saves, out of memory, mod compatibility (mixin) warnings, data pack errors, missing mod content, ports in use, offline mode, and more, with the mod they came from.

**Players.** Every player's packets get the same checks as the player's own game, worded for admins, with recent disconnects and who caused them. `/packetdoctor players` lists everyone online with their ping, the share of lag near them, where they are, whether they're exploring new land, and their warnings, sorted with the most lag nearby first. `/packetdoctor player <name>` adds:

- **Connection and game:** ping, client brand (vanilla, Fabric, Lunar...), view distance, language, the client mods that talk to the server (from their network channels; purely visual mods can't be seen), and the packet types sent and received most.
- **In the world:** position (click to teleport) and game mode. Also their speed, whether they're flying with an elytra, and how many new chunks they explored in the last 5 minutes and this session.
- **Around them:** entities and working block entities within 128 blocks, by type, and the share of the server's time spent near them, by type.
- **Time:** time online this session and in total.

### Commands (operators)

| Command | Shows |
|---|---|
| `/packetdoctor` | Everyone online: packet rates, warnings, and who has Packet Doctor |
| `/packetdoctor lag` | TPS, milliseconds per tick, what slow ticks are spent on, the biggest causes with what to do, and recent lag spikes |
| `/packetdoctor lag top [minutes]` | Every cause, ranked, with what to do (default: last 5 minutes, up to 60) |
| `/packetdoctor lag mods [minutes]` | Time in each mod's code, and what it's mostly doing |
| `/packetdoctor lag mod <id> [minutes]` | One mod in detail: its methods, how they get run, what for, where, and what to do |
| `/packetdoctor lag places [minutes]` | The busiest chunks, with the nearest player and a teleport |
| `/packetdoctor lag things [minutes]` | Block entity and entity types by cost |
| `/packetdoctor lag spike <number>` | One recent lag spike in detail (1 = newest) |
| `/packetdoctor lag report [minutes]` | Writes a full lag report to `packetdoctor/lag` |
| `/packetdoctor players` | Everyone online: ping, lag near them, where they are, exploring |
| `/packetdoctor console [clear]` | Console warnings and errors, grouped and explained (hover for advice) |
| `/packetdoctor crash` | The last crash or unexpected stop, explained |
| `/packetdoctor player <name>` | One player's connection, client, position, movement, surroundings, lag near them, traffic and warnings |
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
| `profileServer` | `true` | Also sample normal ticks, so the biggest causes are known even while the server keeps up |
| `profileIntervalMs` | `20` | How often a normal tick is sampled (milliseconds of server work) |
| `autoLagReports` | `true` | Write a report to `packetdoctor/lag` when a lag spike is logged or the server becomes overloaded |
| `lagReportCooldownMinutes` | `10` | Least minutes between automatic lag reports |
| `keepLagReports` | `200` | Lag report files kept |
| `logLagSpikeMs` | `1000` | Spikes at least this long are written to the console (0 = never) |
| `alertLagSpikeMs` | `2000` | Spikes at least this long alert operators (0 = never) |
| `captureConsole` | `true` | Read the console to explain errors, crashes and lag |
| `consoleLines` | `1000` | Console lines remembered |
| `keepErrorFiles` | `true` | Keep every error's files in `packetdoctor/errors` (never deleted automatically) |
| `errorLogMaxMbPerDay` | `50` | Most MB of console errors kept per day, so an error repeating in a loop can't fill the disk |
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
