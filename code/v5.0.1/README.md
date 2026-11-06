# Rising Sea 0.5.1 - Minecraft Java 26.3 / Fabric

Source project; build with **JDK 25** and recent Gradle, run `gradle clean build`. The mod JAR is in `build/libs/` (do not install the `-sources.jar`). Requires matching Fabric Loader, Fabric API and optionally Mod Menu.

## Improvements
- Pixel-art mod icon in `assets/risingsea/icon.png` with `fabric.mod.json` reference.
- Four smaller UI pages with constrained scrollable rows. Footer no longer overlaps settings at large GUI scales.
- Live server-authoritative settings: menu requests configuration over Fabric network packets on opening and refresh. Server broadcasts updated values after setting changes, including changes through the console. Unsaved edits are not overwritten by incoming snapshots.
- Global target sea level can be set in the UI on page 4 with **Wasserstand global setzen**, or in console via `risingsea setlevel 90` (in game `/risingsea setlevel 90`). This clamps to starting level / maximum; water blocks already placed will not be removed by lowering the target.
- Existing LAYERS / REALISTIC / TSUNAMI modes, saved global water target and bounded chunk work kept.

## Usage
1. Join an existing world (administrator / OP permissions required for changing settings).
2. Pause or backup the world before changing large water amounts.
3. Open Mod Menu -> Rising Sea -> Configure; the client requests the actual world values.
4. Page 4: select Y target then click **Wasserstand global setzen** (this action is separate from Apply).
5. Page 1-3: edit values and click Apply. `Aktualisieren` asks the server for its current settings. Console commands work without the GUI.

## Important limitations
- The server stores a **global target** in the world save, but only processes loaded player-near chunks gradually. Processing modes do NOT flood all unloaded chunks immediately; processing of newly visited chunks may lag behind traversal.
- Chunk-entry guard is best effort, not a hard pre-load barrier. Teleports may reach unfinished areas.
- Reducing `setlevel` does not drain existing water or reverse edits.
- Tsunami is a block-based approximation; it is not a fluid simulation and can leave water flow side effects.
- Client-side GUI requires the mod installed on the client; server/remote updates require the mod installed on the server as well.
- **Build and in-game behavior are not verified in this environment.** Test in a disposable/backup world.

## Build on Windows CMD
In this directory:

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot" && set "PATH=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin;%PATH%" && gradle --stop && gradle clean build
```

## Console examples

```text
risingsea status
risingsea config
risingsea setlevel 90
risingsea set interval 20
risingsea mode tsunami
risingsea tsunami trigger
risingsea processing ahead
risingsea reload
```


## v0.5.1 Fix
- Minecraft 26.3: `RisingSeaConfigScreen.added()` ist jetzt `public`, wie von `Screen` gefordert.
- Dieser Fix beseitigt den gemeldeten Java-Compilerfehler; ein vollstaendiger Build wurde hier nicht ausgefuehrt.
