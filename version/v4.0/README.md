# Rising Sea 0.4.0 — Minecraft 26.3 (Fabric)

**Developer prototype.** The existing 0.3.2 project is extended; it has **not** been compiled with Java 25 in this environment. Test only in a disposable world. Back up saved worlds before loading.

## Build

Requires Java JDK 25 and Gradle compatible with Fabric Loom 1.19.3. In a Windows `cmd` opened in the `risingsea-26.3` directory:

```bat
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot" && set "PATH=C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot\bin;%PATH%" && gradle --stop && gradle clean build
```

Place `build/libs/risingsea-0.4.0.jar` (NOT `-sources.jar`) alongside the matching Fabric API in the Minecraft 26.3 mods folder. Mod Menu is optional for the configuration screen; use a Mod Menu version compatible with Minecraft 26.3. In multiplayer, install Rising Sea on the server; client GUI requires the mod and Mod Menu client-side as well.

## Flooding modes

- `LAYERS`: global target sea level; loaded/player-near chunks gradually acquire blocks at that height (only AIR is changed).
- `REALISTIC`: water spreads from existing wet neighbors in each scan; its result depends on scan order and is not a physics simulation.
- `TSUNAMI`: a circular advancing **block-based simulated crest** originates at the first Overworld player, expands at the configured speed, and creates temporary water above the new target level. The `permanent` flag controls whether the target global sea level increases as well. *This is not a fluid-dynamics tsunami.*

The globally shared **target** is saved in `WORLD/risingsea-state.json`. It is NOT a globally prefilled world: only loaded, selected chunks are physically modified, with an inspection budget per tick. World water is destructive (even though solid blocks aren't replaced), and **water is not automatically undone** when you lower the target.

### Chunk processing

- `AHEAD`: prioritize loaded chunks around players, especially in their look direction; default radius is `preload`.
- `LOADED`: loaded chunks within `radius` of players.
- `GLOBAL`: wider player-centered loaded radius (max of `preload`/`radius`), **NOT an offline traversal of all stored region files**. Chunks far from players are processed when encountered later.
- `newchunks`: when `false`, only chunks known in the first loaded sweep are revisited (subsequent newly encountered chunks are skipped). Keep `true` for the intended global-level behavior.
- `guard`: **experimental** best-effort position correction at chunk borders when a previous safe location exists. It does not intercept client chunk delivery or protect against every teleport, elytra, vehicle, or plugin teleport. Disable if movement behaves strangely. Preloading ahead does not strictly guarantee that water is done before entry.

## Configuration

Edit `config/risingsea.json`, then `/risingsea reload`, or use **Mod Menu -> Rising Sea -> Configure**. Menu pages cover Flood, Chunks, and Tsunami. The GUI sends server commands **only for values edited there**. It does NOT automatically read back the current server state; click `Serverwerte` to print them in chat. OP/administrator privileges required for edits. Console commands omit the initial `/`.

Command examples:

```text
/risingsea status
/risingsea config
/risingsea start
/risingsea stop
/risingsea mode realistic
/risingsea mode layers
/risingsea mode tsunami
/risingsea processing global
/risingsea processing ahead
/risingsea processing loaded
/risingsea set interval 300
/risingsea set riseblocks 3
/risingsea set maxlevel 250
/risingsea set budget 2000
/risingsea set preload 5
/risingsea set radius 3
/risingsea set rescan 10
/risingsea set newchunks true
/risingsea set guard false
/risingsea set waveheight 8
/risingsea set wavewidth 12
/risingsea set wavespeed 4
/risingsea set wavemaxradius 1024
/risingsea set crestlifetime 5
/risingsea set permanent true
/risingsea tsunami trigger
/risingsea setlevel 75
/risingsea reload
```

The example in `config-example/risingsea.json` is not installed automatically. The mod creates its runtime config in the Fabric config directory. New keys are added on next config load/write.

## Known constraints / safety

1. A ring of water blocks is not a continuous smooth wave. Sampling, vanilla water ticks and scan budgets may produce gaps, temporary walls, lateral water spread, and performance costs.
2. The crest cleanup attempts to remove only temporary water recorded in RAM. Restarting during a wave loses that list, potentially leaving residual water above the target.
3. Changing modes, reloading configuration or restarting does **not** revert existing water. Ensure a world backup exists before testing.
4. Chunk completion is reconstructed after restart by rescanning; not every chunk is processed immediately, and no terrain is generated/loaded solely to flood it.
5. Multiplayer screen is an OP command-based interface; displayed entry defaults may differ from server settings.
6. `blockUnpreparedChunkEntry` is experimental and does not provide a strict guarantee. Proper collision/packet gating would require a more invasive, separately tested implementation.
7. **Build unverified against Minecraft 26.3.** Share compiler error output if the build fails.
