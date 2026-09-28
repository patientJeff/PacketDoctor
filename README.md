# Packet Doctor

A client-side Fabric mod for Minecraft 26.3. It watches every packet between you and the server, warns you about packets that could lag you, crash you or get you kicked, and explains disconnects and crashes in plain language.

## What it does

**Packet warnings.** Every packet in and out is checked. A warning shows up as a pop-up and in the Packet Doctor window (default key **K**). Each warning says what was seen, what it means and who sent it. Examples:

- **From the server:** packets close to the 8 MB limit; "chunk ban" / "book ban" sized chunks and items; huge chat or title messages; teleports, explosions or velocities that aren't real numbers; particle, sound, explosion, entity and chat floods; data for mods you don't have.
- **From your game:** positions or camera angles the server will reject; chat that's too long or has illegal characters; being close to a spam kick; oversized books and signs; packet rates that trip server limits (total rate, recipe-book spam, click and attack rates). Where possible, the mod that sent the packet is named.

**Disconnect explanations.** The vanilla disconnect screen gains a one-line explanation and a **"Why was I disconnected?"** button. The explanation covers:

- what happened, in plain words
- where it came from: the server, the network, a specific mod, Minecraft, or you
- what you can try
- any warnings from just before
- the original message and full technical details

It tells apart a server kick, a timeout, a network drop, a server closing silently, a packet your game couldn't read, a mod-initiated disconnect, and client-side checks like chat signing. It recognises every vanilla kick reason plus common plugin, anti-cheat and proxy messages.

**Crash explanations.** When the game crashes, the mod works out the kind of crash and names the most likely mod. Crash kinds it recognises: out of memory, a mod failing to hook in (mixin), a mod built for another version, graphics driver problems, endless loops, world or entity crashes, resource packs, and F3+C. The explanation opens automatically the next time the game starts.

Every explanation is saved as a text file in `.minecraft/packetdoctor/reports`, and **Copy report** puts it on the clipboard for sharing.

## How blame is decided

Stack traces are matched to mods in two ways:

- Fabric's Mixin names injected methods `handler$...$modid$...`, so code a mod injected into Minecraft still names that mod.
- Each class is looked up in the mod jars (nested jars included).

Minecraft, Java, Fabric Loader and Fabric API are on nearly every stack, so they are only blamed when nothing else is found.

## Settings

Change settings in the Settings tab, or edit `config/packetdoctor.json`:

- `toasts`: warning pop-ups
- `toastInfo`: also pop up info-level notes
- `toastCooldownSeconds`
- `titleScreenButton`
- `openCrashExplanation`
- `logSize`: packets kept in the Live log
- `keepReports`: report files to keep

## Building

Requires JDK 25.

```
./gradlew build          # jar in build/libs/
./gradlew runServer      # dev server in run-server/
./gradlew runClient -PquickPlay=localhost   # dev client that joins it
```
