# Rising Sea v6.1

Minecraft Java 26.3 / Fabric. Java 25 is required to build.

## Features

- Rising water modes: LAYERS, REALISTIC, and TSUNAMI (EXPERIMENTAL).
- Configurable rising interval, increase per interval, and maximum sea level.
- Persistent global target sea level, with rate-limited processing near loaded chunks.
- Raise or lower the target sea level using the in-game controls or admin commands. Lowering only removes water tracked as mod-generated; it cannot restore arbitrary previously flooded water.
- Tsunami scheduling by Minecraft days (`tsunamidays`; 0 means manual triggering only). One Minecraft day is 24,000 game ticks.
- Tsunami crest cleanup is experimental and cannot guarantee removal of water spread by normal Minecraft fluid updates.
- Mod Menu configuration GUI and admin/server-console commands with server-side configuration synchronization.
- Only boats near the old sea surface are deliberately repositioned during normal sea-level changes. Occupants stay aboard; other entity types retain vanilla physics. Boat occupants can be ejected by a tsunami if enabled.

## Important limitations

**Back up your world before testing.** Water block edits may be irreversible, particularly with TSUNAMI. Reversal only covers water recorded as placed by this version's water ledger. Chunk processing is gradual; the global target is not an instantaneous change to all unloaded terrain. Boat handling does not replace vanilla physics for boats outside currently loaded areas. This source has not been fully built or game-tested in this environment.

## Build (Windows CMD, from the directory containing build.gradle)

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot" && set "PATH=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin;%PATH%" && gradle --stop && gradle clean build
```

Expected output on successful build: `build/libs/risingsea-6.1.jar`.

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

In Minecraft chat, prefix commands with `/`. Modifications require admin/OP permission.
