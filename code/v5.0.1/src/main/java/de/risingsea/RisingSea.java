package de.risingsea;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import de.risingsea.network.ConfigRequestPayload;
import de.risingsea.network.ConfigSnapshotPayload;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Set;

/** Dedicated-server-safe flood controller: all block edits execute on the main server thread. */
public final class RisingSea implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String PREFIX = "[Rising Sea] ";
    private static final int TICKS_PER_SECOND = 20;
    private Settings config = new Settings();
    private Save save = new Save();
    private Path saveFile;
    private int ticksSinceSave;
    private final FloodWorker worker = new FloodWorker();
    private final Map<UUID, BlockPos> lastSafe = new HashMap<>();
    private long gameTicks;
    private MinecraftServer activeServer;

    public static final class Settings {
        public String floodMode = "REALISTIC";
        public int intervalSeconds = 300;
        public int riseBlocks = 1;
        public int startingWaterLevel = 63;
        public int maxWaterLevel = 250;
        public int blocksPerTick = 1000;
        public int chunkRadius = 3;
        public int rescanSeconds = 15;
        public boolean enableFlooding = true;
        public boolean floodCaves = true;
        public boolean showCountdown = true;
        public int countdownLastSeconds = 10;
        public boolean announceRise = true;
        public boolean pauseWhenEmpty = true;
        // GLOBAL scans loaded chunks; AHEAD prioritizes chunks in front of players;
        // LOADED limits work to the immediate vicinity. No setting forces the entire world into RAM.
        public String chunkProcessingMode = "AHEAD";
        public int preloadRadiusChunks = 5;
        public boolean processNewChunks = true;
        public boolean blockUnpreparedChunkEntry = false;
        public int waveHeightBlocks = 8;
        public int waveWidthBlocks = 12;
        public int waveSpeedBlocksPerSecond = 4;
        public boolean permanentSeaLevelRise = true;
        public int waveMaxRadiusBlocks = 1024;
        public int waveCrestLifetimeSeconds = 5;
    }

    private static final class Save {
        int level = 63;
        long elapsedTicks;
        boolean running = true;
        boolean waveActive;
        int waveX, waveZ;
        long waveElapsedTicks;
        int waveBaseLevel;
        int waveDestinationLevel;
    }

    @Override public void onInitialize() {
        loadConfig();
        PayloadTypeRegistry.serverboundPlay().register(ConfigRequestPayload.TYPE, ConfigRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ConfigSnapshotPayload.TYPE, ConfigSnapshotPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ConfigRequestPayload.TYPE, (payload, context) -> sendSnapshot(context.player()));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            saveFile = server.getWorldPath(LevelResource.ROOT).resolve("risingsea-state.json");
            loadSave();
            worker.reset(); lastSafe.clear(); gameTicks = 0;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveSave();
            worker.reset(); lastSafe.clear();
            saveFile = null;
            activeServer = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        registerCommands();
    }

    private void setNumber(String key, int value) {
        switch (key) {
            case "interval" -> config.intervalSeconds = value;
            case "riseblocks" -> config.riseBlocks = value;
            case "maxlevel" -> config.maxWaterLevel = value;
            case "startlevel" -> config.startingWaterLevel = value;
            case "budget" -> config.blocksPerTick = value;
            case "radius" -> config.chunkRadius = value;
            case "rescan" -> config.rescanSeconds = value;
            case "countdownseconds" -> config.countdownLastSeconds = value;
            case "preload" -> config.preloadRadiusChunks = value;
            case "waveheight" -> config.waveHeightBlocks = value;
            case "wavewidth" -> config.waveWidthBlocks = value;
            case "wavespeed" -> config.waveSpeedBlocksPerSecond = value;
            case "wavemaxradius" -> config.waveMaxRadiusBlocks = value;
            case "crestlifetime" -> config.waveCrestLifetimeSeconds = value;
            default -> throw new IllegalArgumentException(key);
        }
        validateConfig();
        writeConfig();
        worker.reset();
        broadcastSnapshot();
    }

    private void setToggle(String key, boolean value) {
        switch (key) {
            case "enabled" -> config.enableFlooding = value;
            case "caves" -> config.floodCaves = value;
            case "countdown" -> config.showCountdown = value;
            case "announce" -> config.announceRise = value;
            case "pauseempty" -> config.pauseWhenEmpty = value;
            case "newchunks" -> config.processNewChunks = value;
            case "guard" -> config.blockUnpreparedChunkEntry = value;
            case "permanent" -> config.permanentSeaLevelRise = value;
            default -> throw new IllegalArgumentException(key);
        }
        writeConfig();
        worker.reset();
        broadcastSnapshot();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("risingsea")
                .then(Commands.literal("status").executes(ctx -> {
                    long left = Math.max(0, (long) config.intervalSeconds * TICKS_PER_SECOND - save.elapsedTicks);
                    ctx.getSource().sendSuccess(() -> Component.literal(PREFIX + "Ziel Y=" + save.level
                        + " | Modus=" + config.floodMode + " | aktiv=" + save.running
                        + " | Welle=" + (save.waveActive ? "Radius " + (save.waveElapsedTicks * config.waveSpeedBlocksPerSecond / 20) : "aus")
                        + " | naechster Anstieg in " + ((left + 19) / 20) + "s"), false);
                    return 1;
                }))
                .then(Commands.literal("config").executes(ctx -> {
                    Settings c = config;
                    String text = "Modus=" + c.floodMode + " | interval=" + c.intervalSeconds
                        + "s | riseblocks=" + c.riseBlocks + " | maxlevel=" + c.maxWaterLevel
                        + " | enabled=" + c.enableFlooding + " | caves=" + c.floodCaves
                        + " | countdown=" + c.showCountdown + " | budget=" + c.blocksPerTick
                        + " | radius=" + c.chunkRadius + " | rescan=" + c.rescanSeconds
                        + " | pauseempty=" + c.pauseWhenEmpty + " | announce=" + c.announceRise
                        + " | processing=" + c.chunkProcessingMode + " | preload=" + c.preloadRadiusChunks
                        + " | newchunks=" + c.processNewChunks + " | guard=" + c.blockUnpreparedChunkEntry
                        + " | waveheight=" + c.waveHeightBlocks + " | wavewidth=" + c.waveWidthBlocks
                        + " | wavespeed=" + c.waveSpeedBlocksPerSecond + " | wavemaxradius=" + c.waveMaxRadiusBlocks
                        + " | crestlifetime=" + c.waveCrestLifetimeSeconds + " | permanent=" + c.permanentSeaLevelRise;
                    ctx.getSource().sendSuccess(() -> Component.literal(PREFIX + text), false);
                    return 1;
                }))
                .then(Commands.literal("set").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("interval").then(Commands.argument("value", IntegerArgumentType.integer(1, 86400)).executes(ctx -> { setNumber("interval", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "interval"); })))
                    .then(Commands.literal("riseblocks").then(Commands.argument("value", IntegerArgumentType.integer(1, 100)).executes(ctx -> { setNumber("riseblocks", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "riseblocks"); })))
                    .then(Commands.literal("maxlevel").then(Commands.argument("value", IntegerArgumentType.integer(-64, 319)).executes(ctx -> { setNumber("maxlevel", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "maxlevel"); })))
                    .then(Commands.literal("startlevel").then(Commands.argument("value", IntegerArgumentType.integer(-64, 319)).executes(ctx -> { setNumber("startlevel", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "startlevel"); })))
                    .then(Commands.literal("budget").then(Commands.argument("value", IntegerArgumentType.integer(1, 5000)).executes(ctx -> { setNumber("budget", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "budget"); })))
                    .then(Commands.literal("radius").then(Commands.argument("value", IntegerArgumentType.integer(0, 8)).executes(ctx -> { setNumber("radius", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "radius"); })))
                    .then(Commands.literal("rescan").then(Commands.argument("value", IntegerArgumentType.integer(1, 600)).executes(ctx -> { setNumber("rescan", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "rescan"); })))
                    .then(Commands.literal("countdownseconds").then(Commands.argument("value", IntegerArgumentType.integer(0, 60)).executes(ctx -> { setNumber("countdownseconds", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "countdownseconds"); })))
                    .then(Commands.literal("preload").then(Commands.argument("value", IntegerArgumentType.integer(0, 16)).executes(ctx -> { setNumber("preload", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "preload"); })))
                    .then(Commands.literal("waveheight").then(Commands.argument("value", IntegerArgumentType.integer(1, 32)).executes(ctx -> { setNumber("waveheight", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "waveheight"); })))
                    .then(Commands.literal("wavewidth").then(Commands.argument("value", IntegerArgumentType.integer(2, 64)).executes(ctx -> { setNumber("wavewidth", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "wavewidth"); })))
                    .then(Commands.literal("wavespeed").then(Commands.argument("value", IntegerArgumentType.integer(1, 32)).executes(ctx -> { setNumber("wavespeed", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "wavespeed"); })))
                    .then(Commands.literal("wavemaxradius").then(Commands.argument("value", IntegerArgumentType.integer(64, 4096)).executes(ctx -> { setNumber("wavemaxradius", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "wavemaxradius"); })))
                    .then(Commands.literal("crestlifetime").then(Commands.argument("value", IntegerArgumentType.integer(1, 30)).executes(ctx -> { setNumber("crestlifetime", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "crestlifetime"); })))
                    .then(Commands.literal("enabled").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("enabled", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "enabled"); })))
                    .then(Commands.literal("caves").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("caves", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "caves"); })))
                    .then(Commands.literal("countdown").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("countdown", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "countdown"); })))
                    .then(Commands.literal("announce").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("announce", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "announce"); })))
                    .then(Commands.literal("pauseempty").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("pauseempty", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "pauseempty"); })))
                    .then(Commands.literal("newchunks").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("newchunks", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "newchunks"); })))
                    .then(Commands.literal("guard").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("guard", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "guard"); })))
                    .then(Commands.literal("permanent").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("permanent", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "permanent"); }))))
                .then(Commands.literal("start").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    save.running = true; saveSave(); broadcastSnapshot(); broadcast(ctx.getSource().getServer(), "Flut gestartet."); return 1;
                }))
                .then(Commands.literal("stop").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    save.running = false; saveSave(); broadcastSnapshot(); broadcast(ctx.getSource().getServer(), "Flut pausiert."); return 1;
                }))
                .then(Commands.literal("mode").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("realistic").executes(ctx -> { changeMode("REALISTIC"); return 1; }))
                    .then(Commands.literal("layers").executes(ctx -> { changeMode("LAYERS"); return 1; }))
                    .then(Commands.literal("tsunami").executes(ctx -> { changeMode("TSUNAMI"); return 1; })))
                .then(Commands.literal("processing").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("global").executes(ctx -> { changeProcessing("GLOBAL"); return 1; }))
                    .then(Commands.literal("ahead").executes(ctx -> { changeProcessing("AHEAD"); return 1; }))
                    .then(Commands.literal("loaded").executes(ctx -> { changeProcessing("LOADED"); return 1; })))
                .then(Commands.literal("tsunami").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("trigger").executes(ctx -> {
                        triggerWave(ctx.getSource().getServer(), config.riseBlocks);
                        return 1;
                    })))
                .then(Commands.literal("setlevel").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.argument("y", IntegerArgumentType.integer(-64, 319)).executes(ctx -> {
                        int requested = IntegerArgumentType.getInteger(ctx, "y");
                        save.level = Math.max(config.startingWaterLevel, Math.min(config.maxWaterLevel, requested));
                        save.waveActive = false;
                        worker.reset(); saveSave(); broadcastSnapshot();
                        broadcast(ctx.getSource().getServer(), "Globaler Zielwasserstand Y=" + save.level + " (Chunks werden schrittweise angepasst; Wasser sinkt nicht automatisch).");
                        return 1;
                    })))
                .then(Commands.literal("rise").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
                        save.level = Math.min(config.maxWaterLevel, save.level + IntegerArgumentType.getInteger(ctx, "blocks"));
                        save.elapsedTicks = 0; save.waveActive = false; worker.reset(); saveSave(); broadcastSnapshot();
                        broadcast(ctx.getSource().getServer(), "Wasserstand jetzt Y=" + save.level);
                        return 1;
                    })))
                .then(Commands.literal("reload").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    loadConfig(); worker.reset(); broadcastSnapshot();
                    ctx.getSource().sendSuccess(() -> Component.literal(PREFIX + "Konfiguration neu geladen."), false);
                    return 1;
                }))
            )
        );
    }

    private int changed(MinecraftServer server, String key) {
        broadcast(server, "Konfiguration geaendert: " + key);
        return 1;
    }

    private void changeMode(String mode) {
        config.floodMode = mode;
        writeConfig();
        worker.reset();
        broadcastSnapshot();
    }

    private void changeProcessing(String mode) {
        config.chunkProcessingMode = mode;
        writeConfig(); worker.reset(); broadcastSnapshot();
    }

    /** Origin is the first Overworld player. The wave radius advances in real server ticks. */
    private void triggerWave(MinecraftServer server, int rise) {
        ServerPlayer origin = null;
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (candidate.level().dimension().equals(Level.OVERWORLD)) { origin = candidate; break; }
        }
        if (origin == null) { broadcast(server, "Tsunami braucht einen Spieler in der Overworld."); return; }
        save.waveX = origin.blockPosition().getX();
        save.waveZ = origin.blockPosition().getZ();
        save.waveBaseLevel = save.level;
        save.waveDestinationLevel = Math.min(config.maxWaterLevel, save.level + rise);
        save.waveElapsedTicks = 0;
        save.waveActive = true;
        // The global target becomes authoritative immediately; chunks are applied lazily.
        if (config.permanentSeaLevelRise) save.level = save.waveDestinationLevel;
        worker.reset(); saveSave(); broadcastSnapshot();
        broadcast(server, "Tsunami startet! Ziel-Meeresspiegel Y=" + save.level);
    }

    private void tick(MinecraftServer server) {
        if (saveFile == null || !config.enableFlooding) return;
        gameTicks++;
        if (config.pauseWhenEmpty && server.getPlayerList().getPlayers().isEmpty()) return;
        if (save.running && save.level < config.maxWaterLevel) {
            long interval = (long) config.intervalSeconds * TICKS_PER_SECOND;
            long remaining = interval - save.elapsedTicks;
            if (config.showCountdown && remaining > 0 && remaining <= (long) config.countdownLastSeconds * TICKS_PER_SECOND
                && remaining % TICKS_PER_SECOND == 0) {
                int seconds = (int) (remaining / TICKS_PER_SECOND);
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    player.sendOverlayMessage(Component.literal("Nächster Wasseranstieg in " + seconds + "s").withStyle(net.minecraft.ChatFormatting.AQUA));
                }
            }
            if (++save.elapsedTicks >= interval) {
                save.elapsedTicks = 0;
                int old = save.level;
                if ("TSUNAMI".equals(config.floodMode)) {
                    triggerWave(server, config.riseBlocks);
                } else {
                    save.level = Math.min(config.maxWaterLevel, save.level + config.riseBlocks);
                    if (old != save.level) {
                        worker.reset(); saveSave(); broadcastSnapshot();
                        if (config.announceRise) broadcast(server, "Meeresspiegel auf Y=" + save.level + " gestiegen!");
                    }
                }
            }
        }
        // Pausing the timer also pauses further terrain edits; previous water remains in the world.
        if (save.running) {
            if (save.waveActive) {
                save.waveElapsedTicks++;
                if (save.waveElapsedTicks * config.waveSpeedBlocksPerSecond >= (long) config.waveMaxRadiusBlocks * 20) {
                    save.waveActive = false;
                    saveSave();
                }
            }
            worker.tick(server, config, save, gameTicks);
            if (config.blockUnpreparedChunkEntry) guardPlayers(server);
        }
        if (++ticksSinceSave >= 1200) { ticksSinceSave = 0; saveSave(); }
    }

    /** Best-effort movement protection for unprepared chunks. This does NOT intercept teleport packet delivery. */
    private void guardPlayers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.level().dimension().equals(Level.OVERWORLD)) continue;
            BlockPos pos = player.blockPosition();
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            if (worker.ready(cx, cz, save.level)) {
                lastSafe.put(player.getUUID(), pos);
            } else {
                BlockPos safe = lastSafe.get(player.getUUID());
                if (safe != null && (safe.getX() >> 4 != cx || safe.getZ() >> 4 != cz)) {
                    player.setPos(safe.getX() + .5, safe.getY(), safe.getZ() + .5);
                    player.setDeltaMovement(0, 0, 0);
                    player.sendOverlayMessage(Component.literal("Rising Sea: Chunk wird ueberflutet ..."));
                }
            }
        }
    }

    /** Authoritative snapshot; sent on GUI request and after every settings update. */
    private void sendSnapshot(ServerPlayer player) {
        if (activeServer == null || player == null) return;
        JsonObject payload = GSON.toJsonTree(config).getAsJsonObject();
        payload.addProperty("currentWaterLevel", save.level);
        payload.addProperty("running", save.running);
        ServerPlayNetworking.send(player, new ConfigSnapshotPayload(payload.toString()));
    }

    private void broadcastSnapshot() {
        if (activeServer == null) return;
        for (ServerPlayer player : activeServer.getPlayerList().getPlayers()) {
            sendSnapshot(player);
        }
    }

    private void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(PREFIX + text), false);
    }

    private Path configPath() { return FabricLoader.getInstance().getConfigDir().resolve("risingsea.json"); }

    private void loadConfig() {
        try {
            Path path = configPath();
            if (Files.exists(path)) {
                try (Reader in = Files.newBufferedReader(path)) {
                    Settings loaded = GSON.fromJson(in, Settings.class);
                    if (loaded != null) config = loaded;
                }
            }
            validateConfig();
            writeConfig();
        } catch (Exception e) {
            System.err.println(PREFIX + "Config konnte nicht geladen werden: " + e);
            config = new Settings();
        }
    }

    private void validateConfig() {
        if (config.floodMode == null) config.floodMode = "REALISTIC";
        config.floodMode = config.floodMode.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("LAYERS", "REALISTIC", "TSUNAMI").contains(config.floodMode)) config.floodMode = "REALISTIC";
        if (config.chunkProcessingMode == null) config.chunkProcessingMode = "AHEAD";
        config.chunkProcessingMode = config.chunkProcessingMode.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("GLOBAL", "AHEAD", "LOADED").contains(config.chunkProcessingMode)) config.chunkProcessingMode = "AHEAD";
        config.intervalSeconds = Math.max(1, Math.min(86400, config.intervalSeconds));
        config.riseBlocks = Math.max(1, Math.min(100, config.riseBlocks));
        config.startingWaterLevel = Math.max(-64, Math.min(319, config.startingWaterLevel));
        config.maxWaterLevel = Math.max(config.startingWaterLevel, Math.min(319, config.maxWaterLevel));
        config.blocksPerTick = Math.max(1, Math.min(5000, config.blocksPerTick));
        config.chunkRadius = Math.max(0, Math.min(8, config.chunkRadius));
        config.rescanSeconds = Math.max(1, Math.min(600, config.rescanSeconds));
        config.countdownLastSeconds = Math.max(0, Math.min(60, config.countdownLastSeconds));
        config.preloadRadiusChunks = Math.max(0, Math.min(16, config.preloadRadiusChunks));
        config.waveHeightBlocks = Math.max(1, Math.min(32, config.waveHeightBlocks));
        config.waveWidthBlocks = Math.max(2, Math.min(64, config.waveWidthBlocks));
        config.waveSpeedBlocksPerSecond = Math.max(1, Math.min(32, config.waveSpeedBlocksPerSecond));
        config.waveMaxRadiusBlocks = Math.max(64, Math.min(4096, config.waveMaxRadiusBlocks));
        config.waveCrestLifetimeSeconds = Math.max(1, Math.min(30, config.waveCrestLifetimeSeconds));
    }

    private void writeConfig() {
        try {
            Files.createDirectories(configPath().getParent());
            try (Writer out = Files.newBufferedWriter(configPath())) { GSON.toJson(config, out); }
        } catch (Exception e) { System.err.println(PREFIX + "Config konnte nicht gespeichert werden: " + e); }
    }

    private void loadSave() {
        save = new Save();
        save.level = config.startingWaterLevel;
        if (saveFile != null && Files.exists(saveFile)) {
            try (Reader in = Files.newBufferedReader(saveFile)) {
                Save stored = GSON.fromJson(in, Save.class);
                if (stored != null) save = stored;
            } catch (Exception e) { System.err.println(PREFIX + "Speicherstand unlesbar: " + e); }
        }
        save.elapsedTicks = Math.max(0, save.elapsedTicks);
        save.waveElapsedTicks = Math.max(0, save.waveElapsedTicks);
        saveSave();
    }

    private void saveSave() {
        if (saveFile == null) return;
        try (Writer out = Files.newBufferedWriter(saveFile)) { GSON.toJson(save, out); }
        catch (Exception e) { System.err.println(PREFIX + "Speichern fehlgeschlagen: " + e); }
    }

    /** Main-thread only, bounded scan + block edit queue. Chunks never forced to load. */
    private static final class FloodWorker {
        private record Target(ServerLevel level, int x, int z) {}
        private record Crest(ServerLevel level, BlockPos pos, long expires) {}
        private final ArrayDeque<Target> pending = new ArrayDeque<>();
        private final ArrayDeque<Crest> crestCleanup = new ArrayDeque<>();
        private final Set<Long> crestPositions = new HashSet<>();
        private final Set<Long> enqueued = new HashSet<>();
        private final Set<Long> seen = new HashSet<>();
        // Completed height per chunk in this server session. Persisted in level files via water blocks,
        // and reconstructed by scanning after restart. No false ready state on reload.
        private final Map<Long, Integer> completed = new HashMap<>();
        private Target current;
        private int column, y, idleTicks;
        private int targetSurface;
        private boolean hasScanned;

        private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
        boolean ready(int cx, int cz, int level) { return completed.getOrDefault(key(cx, cz), Integer.MIN_VALUE) >= level; }
        void reset() {
            pending.clear(); enqueued.clear(); seen.clear(); completed.clear(); crestCleanup.clear(); crestPositions.clear();
            current = null; column = y = idleTicks = 0; hasScanned = false;
        }

        void tick(MinecraftServer server, Settings cfg, Save save, long ticks) {
            if (++idleTicks >= cfg.rescanSeconds * 20 || (!hasScanned && current == null && pending.isEmpty())) {
                idleTicks = 0;
                discover(server, cfg);
                hasScanned = true;
            }
            int budget = cfg.blocksPerTick;
            int cleanupBudget = Math.max(1, budget / 5);
            // Remove only our temporary crest blocks, not existing water or permanently raised sea blocks.
            for (int i = 0; i < cleanupBudget && !crestCleanup.isEmpty(); i++) {
                Crest crest = crestCleanup.peekFirst();
                if (crest.expires() > ticks) break;
                crestCleanup.pollFirst();
                BlockPos p = crest.pos();
                crestPositions.remove(p.asLong());
                if (crest.level().hasChunk(p.getX() >> 4, p.getZ() >> 4)
                    && crest.level().getBlockState(p).is(Blocks.WATER)
                    && p.getY() > save.level) {
                    crest.level().setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            // Prevent the transient crest list from growing indefinitely in very long sessions.
            if (crestCleanup.size() > 300000) { crestCleanup.clear(); crestPositions.clear(); }
            int usedByWave = renderWave(server, cfg, save, ticks, Math.max(0, budget / 3));
            int remaining = Math.max(0, budget - usedByWave - cleanupBudget);
            while (remaining-- > 0) {
                if (current == null) {
                    current = pending.pollFirst();
                    if (current == null) return;
                    enqueued.remove(key(current.x(), current.z()));
                    column = 0;
                    y = cfg.startingWaterLevel;
                    targetSurface = save.level;
                }
                ServerLevel level = current.level();
                int cx = current.x(), cz = current.z();
                if (!level.hasChunk(cx, cz)) { current = null; continue; }
                int lowest = Math.max(cfg.startingWaterLevel, level.getMinY());
                int highest = Math.min(targetSurface, level.getMaxY() - 1);
                if (highest < lowest) { completed.put(key(cx, cz), targetSurface); current = null; continue; }
                if (y < lowest) y = lowest;
                if (y > highest) {
                    y = lowest;
                    if (++column == 256) {
                        completed.put(key(cx, cz), targetSurface);
                        current = null;
                    }
                    continue;
                }
                int x = (cx << 4) + (column & 15);
                int z = (cz << 4) + (column >>> 4);
                BlockPos pos = new BlockPos(x, y++, z);
                BlockState old = level.getBlockState(pos);
                if (!old.isAir()) continue; // preserve structures, plants and all non-air blocks
                if (!cfg.floodCaves && !level.canSeeSky(pos)) continue;
                boolean fill = !"REALISTIC".equals(cfg.floodMode) || hasWetNeighbor(level, pos);
                if (fill) level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
            }
        }

        /** Traveling annular water front. No forced chunk loads and capped placement cost. */
        private int renderWave(MinecraftServer server, Settings cfg, Save save, long ticks, int limit) {
            if (!save.waveActive || limit <= 0 || !"TSUNAMI".equals(cfg.floodMode)) return 0;
            ServerLevel world = server.getLevel(Level.OVERWORLD);
            if (world == null) return 0;
            double radius = save.waveElapsedTicks * cfg.waveSpeedBlocksPerSecond / 20.0;
            if (radius < 1) return 0;
            int used = 0;
            int height = Math.min(cfg.waveHeightBlocks, world.getMaxY() - 1 - save.waveDestinationLevel);
            if (height <= 0) return 0;
            // Rotate the sampling phase every tick so there are no permanent gaps.
            int samples = Math.min(4096, Math.max(64, (int)(2 * Math.PI * radius)));
            int bands = Math.max(1, cfg.waveWidthBlocks / 3);
            double phase = (ticks % 17) / 17.0;
            for (int b = 0; b < bands && used < limit; b++) {
                double rr = Math.max(0, radius - b * 3);
                for (int i = 0; i < samples && used < limit; i++) {
                    double angle = (i + phase) * (2 * Math.PI / samples);
                    int x = save.waveX + (int)Math.round(Math.cos(angle) * rr);
                    int z = save.waveZ + (int)Math.round(Math.sin(angle) * rr);
                    if (!world.hasChunk(x >> 4, z >> 4)) continue;
                    for (int h = 1; h <= height && used < limit; h++) {
                        BlockPos pos = new BlockPos(x, save.waveDestinationLevel + h, z);
                        if (!crestPositions.add(pos.asLong())) continue;
                        if (!world.getBlockState(pos).isAir()) {
                            crestPositions.remove(pos.asLong()); continue;
                        }
                        if (!cfg.floodCaves && !world.canSeeSky(pos)) {
                            crestPositions.remove(pos.asLong()); continue;
                        }
                        world.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                        crestCleanup.addLast(new Crest(world, pos, ticks + cfg.waveCrestLifetimeSeconds * 20L));
                        used++;
                    }
                }
            }
            return used;
        }

        private void discover(MinecraftServer server, Settings cfg) {
            Set<Long> round = new HashSet<>();
            List<Target> candidates = new ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerLevel level = player.level();
                if (!level.dimension().equals(Level.OVERWORLD)) continue;
                int cx = player.blockPosition().getX() >> 4;
                int cz = player.blockPosition().getZ() >> 4;
                int radius = switch (cfg.chunkProcessingMode) {
                    case "GLOBAL" -> Math.max(cfg.preloadRadiusChunks, cfg.chunkRadius);
                    case "LOADED" -> cfg.chunkRadius;
                    default -> cfg.preloadRadiusChunks;
                };
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = cx + dx, z = cz + dz;
                        if (!level.hasChunk(x, z)) continue;
                        long k = key(x, z);
                        if (!cfg.processNewChunks && hasScanned && !seen.contains(k)) continue;
                        seen.add(k);
                        if (round.add(k) && !enqueued.contains(k)
                            ) {
                            candidates.add(new Target(level, x, z));
                        }
                    }
                }
            }
            // GLOBAL also scans all currently loaded Overworld chunks through the players' server view.
            // Truly unloaded regions are not forced into RAM; they are brought up to date when visited.
            if ("AHEAD".equals(cfg.chunkProcessingMode)) {
                candidates.sort(Comparator.comparingDouble(t -> priority(t, server)));
            }
            for (Target candidate : candidates) {
                long k = key(candidate.x(), candidate.z());
                if (enqueued.add(k)) pending.addLast(candidate);
            }
        }

        private double priority(Target target, MinecraftServer server) {
            double best = Double.MAX_VALUE;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() != target.level()) continue;
                double dx = target.x() * 16 + 8 - player.getX();
                double dz = target.z() * 16 + 8 - player.getZ();
                double dist = Math.hypot(dx, dz);
                double forward = dx * player.getLookAngle().x + dz * player.getLookAngle().z;
                best = Math.min(best, dist - Math.max(0, forward) * .65);
            }
            return best;
        }

        private boolean hasWetNeighbor(ServerLevel level, BlockPos pos) {
            return wet(level, pos.north()) || wet(level, pos.south()) || wet(level, pos.east())
                || wet(level, pos.west()) || wet(level, pos.below());
        }
        private boolean wet(ServerLevel level, BlockPos pos) {
            return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                && !level.getFluidState(pos).isEmpty();
        }
    }
}
