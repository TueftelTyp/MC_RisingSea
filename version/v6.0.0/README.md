# Rising Sea v0.6.0 – Minecraft 26.3 / Fabric

**EXPERIMENTAL BUILD, NOT COMPILED OR TESTED.** Requires Java JDK 25 and a compatible Gradle/Fabric environment.

## Changes

- TSUNAMI is explicitly marked **EXPERIMENTAL** in the mode button. It can be scheduled every X Minecraft days (`tsunamidays`, 0 = manual only), independent of the ordinary seconds-based rising-sea interval.
- Boat passengers are ejected only in the Tsunami mode when a crest reaches the boat (`boateject`); otherwise surface boats and item entities near players are moved toward the new sea level without detaching riders. This is best effort, not yet proven for all entities/mobs.
- The permanent sea level is still a global target, with a chunk-budgeted worker.
- `setlevel` now accepts a lower height. The worker records **new water placed by v0.6.0** in a compressed per-world ledger `risingsea-owned-water.dat.gz` and removes those tracked water blocks above the new target in loaded chunks. This does **not** safely remove arbitrary water from earlier mod versions or flowing water that escaped from a transient wave.
- Transient wave crest blocks use a separate time-based cleanup queue; unloaded regions are deferred for cleanup. Minecraft's natural water updates can still spread crest water; the Tsunami remains experimental.
- Mod Menu (Tsunami page): Tsunami schedule and ejection toggle, plus existing wave parameters.

## Critical limitations

1. **Back up the world** before using this mod. The wave uses real Minecraft water and can leave flowing-water artifacts, especially in existing worlds. No claim of full reversibility is made.
2. Setting a *lower* level removes only water explicitly tracked by this release. Already flooded worlds from v0.5.1 should be restored from backup or tested separately.
3. Water editing is rate-limited. A globally saved sea level is not an instant all-chunks rewrite; unloaded chunks update as they enter processing range.
4. Item/boat floating is best effort near active players and may not capture all modded boats, items, or unusual motion. Boat ejection applies to the boat's passengers, including mobs.
5. With `tsunamidays=0`, only `/risingsea tsunami trigger` launches a Tsunami. Day schedule measures 24,000 server game ticks, not real-world calendar days.
6. This build has **not** been compiled or run in Minecraft here; your Gradle build is the next verification step.

## Build (Windows CMD, from folder containing build.gradle)

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot" && set "PATH=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin;%PATH%" && gradle --stop && gradle clean build
```

Expected JAR (if the build succeeds): `build/libs/risingsea-0.6.0.jar`.

## Server console examples

```text
risingsea mode tsunami
risingsea set tsunamidays 3
risingsea set boateject true
risingsea set waveheight 8
risingsea set crestlifetime 5
risingsea tsunami trigger
risingsea setlevel 70
risingsea setlevel 63
risingsea status
risingsea config
```

In-game: add `/` before each command. Admin/OP permission is required for modifications. GUI changes use the same commands and server-authoritative config sync.
