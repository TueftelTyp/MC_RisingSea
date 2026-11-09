# Rising Sea – Minecraft Java 26.3 / Fabric

**Version 0.3.2 – source project with GUI and console commands; NOT compiled or runtime tested.**

Minecraft's sea surface rises at a configurable pace. Server-side logic runs in singleplayer (integrated server) and on dedicated servers. Only the Overworld is changed. Existing water blocks are never reverted automatically. **BACK UP YOUR WORLD BEFORE TESTING!**

## Requirements / build

- Minecraft Java **26.3**
- **JDK 25** (run `java -version` to confirm)
- **Gradle 9.7+**, preferably the release compatible with the configured Fabric Loom plugin
- Internet to download Minecraft and Fabric dependencies on first build

In this directory run:

```sh
gradle --no-daemon build
```

If successful, use **`build/libs/risingsea-0.3.2.jar`** (do not use a sources or dev jar). Install the same Minecraft 26.3-compatible **Fabric Loader** and **Fabric API**; put the mod jar in `mods/`. The server needs the mod; clients should use the mod too for consistent mod metadata, although the flooding logic is server-side.

This ZIP does not include a Gradle wrapper because the Gradle wrapper binary was not available locally. For Windows, use `gradle.bat build` if Gradle is installed or install Gradle and use `gradle build` from PowerShell.

The versions in `gradle.properties` were listed by Fabric's version selector on 2026-11-06. If the build cannot resolve a version, consult https://fabricmc.net/develop/ and adjust `loader_version`, `loom_version`, and `fabric_api_version`. Minecraft 26.x uses the non-obfuscated `net.fabricmc.fabric-loom` plugin, not the old remap plugin.

## Gameplay

- **REALISTIC**: eligible air blocks flood if touching existing water/fluids horizontally or below. Repeated sweeps allow gradual expansion. This is an approximation of connected flooding, **not** a true real-time ocean hydraulic simulation.
- **LAYERS**: all eligible air cells at or below the configured target Y level fill regardless of proximity to existing water. Builds below the water level will get flooded inside if air is exposed to the scanner.
- `floodCaves=false`: only sky-visible air is filled (caves and indoor spaces are generally left alone, except for vanilla fluid mechanics).
- Only **air** is replaced directly: blocks, plants and built structures are not directly overwritten; vanilla water propagation can still break susceptible blocks.
- Newly visited loaded chunks around players are scanned, including terrain visited after the sea level rises. No chunks are force-loaded.
- The engine is bounded by `blocksPerTick`, not guaranteed to flood the whole map instantly. Scanning large worlds takes time.
- The water already placed is permanent unless manually removed or restored from backup.
- The ticking timer pauses when no players are connected (`pauseWhenEmpty=true`).
- When paused via `/risingsea stop`, both the timer and the ongoing flood scan stop.

## Config

The mod creates `config/risingsea.json` on launch. Change it and run `/risingsea reload` (or restart):

```json
{
  "floodMode": "REALISTIC",
  "intervalSeconds": 300,
  "riseBlocks": 1,
  "startingWaterLevel": 63,
  "maxWaterLevel": 250,
  "blocksPerTick": 1000,
  "chunkRadius": 3,
  "rescanSeconds": 15,
  "enableFlooding": true,
  "floodCaves": true,
  "showCountdown": true,
  "countdownLastSeconds": 10,
  "announceRise": true,
  "pauseWhenEmpty": true
}
```

`startingWaterLevel` only seeds a **new world's** state. Later changes to this option do not reset a saved world. Target level and elapsed tick count are in the world save at `risingsea-state.json`. `maxWaterLevel` is the upper target cap, Y 319 is the intended default overworld upper boundary. `blocksPerTick` is a budget of examined positions, not a rate of placed water blocks.

## Commands

- `/risingsea status` – water Y, mode, paused state and remaining time
- `/risingsea start` – resume the timer and flooding
- `/risingsea stop` – pause the timer and flooding
- `/risingsea mode realistic` – switch modes and save config
- `/risingsea mode layers` – switch modes and save config
- `/risingsea setlevel <y>` – set current target level, does not remove previously placed water
- `/risingsea rise <blocks>` – increase target by N blocks, respecting maxWaterLevel
- `/risingsea reload` – reload settings from the config file

All commands except status need moderator/operator permissions.

## Limitations / known issues

This project was **not compiled** here: the build machine did not have Java 25 and Gradle. Fabric/Minecraft API changes could cause compile errors; share your build output to troubleshoot those. The real flooding uses slow progressive scans rather than a connected-ocean flood fill or global worldgen modification. Water flow and physics are vanilla. Countdown is an actionbar notification during the last configured seconds, not a continuously rendered HUD. The engine only sees loaded chunks near active players. Changes in configuration or water level do not reverse old floods. A restarted game re-scans nearby terrain.


## GUI und Serverkonsole (v0.3.0)

Installiere optional **Mod Menu 21.0.0 (Minecraft 26.3)** auf dem Client.
Unter Mods > Rising Sea > Konfigurieren oeffnet sich die Oberflaeche.
Das Menue sendet gewoehnliche `/risingsea`-Serverbefehle: Nur OPs koennen Werte aendern.
Die Menuefelder enthalten **Standardwerte als Vorbelegung, nicht automatisch synchronisierte Serverwerte**;
vor einer Aenderung `/risingsea config` ausfuehren und aktuelle Werte pruefen.
**Nur aktiv bearbeitete Felder** werden beim Klick auf *Auf Server anwenden* gesendet;
unveraenderte Standardwerte werden nicht auf den Server geschrieben.
Schaltflaeche *Serverwerte abfragen* zeigt aktuelle Werte im Chat.
Die GUI ist nur waehrend eines laufenden Spiels wirksam.

Konsole (ohne fuehrenden Slash) bzw. OP-Chat (mit Slash):

```
risingsea config
risingsea status
risingsea mode realistic
risingsea mode layers
risingsea set interval 300
risingsea set riseblocks 2
risingsea set maxlevel 250
risingsea set budget 1000
risingsea set radius 3
risingsea set rescan 15
risingsea set enabled true
risingsea set caves false
risingsea set countdown true
risingsea set countdownseconds 10
risingsea set announce true
risingsea set pauseempty true
risingsea start
risingsea stop
risingsea rise 1
risingsea setlevel 70
risingsea reload
```

Aenderungen werden serverseitig in `config/risingsea.json` gespeichert. Das GUI
steuert keine lokale Einzelspieler-Konfiguration separat, sondern sendet Serverbefehle.
Bei einem Moduswechsel wird bereits gesetztes Wasser nicht entfernt.

**Hinweis:** Die Integration wurde hier noch nicht gegen die Minecraft-26.3-API kompiliert oder im Spiel getestet.
Falls API-Namen abweichen, bitte Gradle-Fehlerausgabe bereitstellen.


## v0.3.1 – Build configuration repair (2026-11-06)

The previous archive used `modImplementation` and `modCompileOnly` with the non-remapping
Fabric Loom plugin for Minecraft 26.3. These dependency configurations are not available
in the selected Loom setup. This archive replaces them with `implementation` and
`compileOnly` respectively. This addresses the reported Gradle *configuration* error,
not any potential later Java compilation or runtime issues.

**Windows build:** open CMD with Java 25 configured, then run `gradle clean build`.
Check `gradle -version` to ensure the Gradle JVM uses Java 25.

If the build still fails, capture and share the *first* `> Task :compileJava FAILED`
block (or other first failing task), including the Java file and line number.

**Mod Menu:** The GUI requires Mod Menu on the client. The menu currently uses
pre-filled defaults, does not fetch server values into controls, and sends only changed
settings as commands. Your server must permit the command (operator privileges).
For accurate settings check `/risingsea config`. Server console commands omit `/`.
This project is not a build-validated or gameplay-validated release. Always test in a
backup world.

## v0.3.2 - Minecraft 26.3 API compile fixes

- Use `Minecraft.getInstance().gui.setScreen(parent)` for screen navigation.
- Use `ServerPlayer.sendOverlayMessage(Component)` for the action bar.
- Correct previous version shown in build outputs.
- Still requires a full Gradle build and in-game verification.
