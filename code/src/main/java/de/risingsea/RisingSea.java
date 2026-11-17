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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
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
    private static final long TICKS_PER_DAY = 24000L;
    private Settings config = new Settings();
    private Save save = new Save();
    private Path saveFile;
    private Path waterLedgerFile;
    private int ticksSinceSave;
    private final FloodWorker worker = new FloodWorker();
    private final Map<UUID, BlockPos> lastSafe = new HashMap<>();
    private long gameTicks;
    private MinecraftServer activeServer;
    private final Set<UUID> waveEjectedBoats = new HashSet<>();
    private int previousSurfaceLevel = 63;
    // Only boats present when the global target changes are eligible for relocation.
    // This intentionally excludes items, mobs, players, and newly placed boats.
    private int boatObservedLevel = 63;
    private final Map<UUID, BoatTransfer> boatTransfers = new HashMap<>();
    private record BoatTransfer(double initialY, double targetY, long expiresAtTick) {}

    public static final class Settings {
        public String floodMode = "REALISTIC";
        public int intervalSeconds = 300;
        // Borrowed from Falling Sea: optional, persistent random Minecraft-day intervals.
        // This controls ordinary rising; tsunamiIntervalDays remains independent.
        public boolean randomSchedule = false;
        public int randomMinDays = 1;
        public int randomMaxDays = 5;
        public boolean waitForChunkPass = false;
        public boolean debug = false;
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
        // Separate from the usual sea-rise interval. 0 = manual tsunami only.
        public int tsunamiIntervalDays = 3;
        public boolean tsunamiEjectBoats = true;
        public double tsunamiLaunchVelocity = 1.1;
    }

    private static final class Save {
        int level = 63;
        long elapsedTicks;
        long scheduledIntervalTicks;
        boolean scheduledRandomMode;
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
            waterLedgerFile = server.getWorldPath(LevelResource.ROOT).resolve("risingsea-owned-water.dat.gz");
            loadSave();
            synchronizeSchedule(false);
            previousSurfaceLevel = save.level;
            boatObservedLevel = save.level; boatTransfers.clear();
            worker.clearAll(); worker.loadLedger(waterLedgerFile); worker.reconcileLevel(save.level); lastSafe.clear(); gameTicks = 0;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveSave();
            worker.saveLedger(waterLedgerFile);
            worker.clearAll(); lastSafe.clear(); waveEjectedBoats.clear(); boatTransfers.clear();
            saveFile = null; waterLedgerFile = null;
            activeServer = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        registerCommands();
    }

    private void setNumber(String key, int value) {
        switch (key) {
            case "interval" -> config.intervalSeconds = value;
            case "randommindays" -> config.randomMinDays = value;
            case "randommaxdays" -> config.randomMaxDays = value;
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
            case "tsunamidays" -> config.tsunamiIntervalDays = value;
            default -> throw new IllegalArgumentException(key);
        }
        validateConfig();
        if (key.equals("randommindays") || key.equals("randommaxdays")) synchronizeSchedule(true);
        writeConfig();
        worker.reset();
        broadcastSnapshot();
    }

    private void setToggle(String key, boolean value) {
        switch (key) {
            case "enabled" -> config.enableFlooding = value;
            case "randomschedule" -> config.randomSchedule = value;
            case "waitscan" -> config.waitForChunkPass = value;
            case "debug" -> config.debug = value;
            case "caves" -> config.floodCaves = value;
            case "countdown" -> config.showCountdown = value;
            case "announce" -> config.announceRise = value;
            case "pauseempty" -> config.pauseWhenEmpty = value;
            case "newchunks" -> config.processNewChunks = value;
            case "guard" -> config.blockUnpreparedChunkEntry = value;
            case "permanent" -> config.permanentSeaLevelRise = value;
            case "boateject" -> config.tsunamiEjectBoats = value;
            default -> throw new IllegalArgumentException(key);
        }
        if (key.equals("randomschedule")) synchronizeSchedule(true);
        writeConfig();
        worker.reset();
        broadcastSnapshot();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("risingsea")
                .then(Commands.literal("status").executes(ctx -> {
                    long interval = currentIntervalTicks();
                    long left = interval < 0 ? -1 : Math.max(0, interval - save.elapsedTicks);
                    String nextRise = interval < 0 ? "manual" :
                        (save.elapsedTicks >= interval && config.waitForChunkPass && !worker.readyForNextStep()
                            ? "pending scan" : ((left + 19) / 20) + "s");
                    ctx.getSource().sendSuccess(() -> Component.literal(PREFIX).append(Component.translatable("risingsea.chat.status",
                        save.level, config.floodMode, save.running,
                        (save.waveActive ? "Radius " + (save.waveElapsedTicks * config.waveSpeedBlocksPerSecond / 20) : "off"),
                        worker.pendingCount(), worker.placedBlocks(),
                        (interval < 0 ? Component.translatable("risingsea.chat.status_manual") : Component.literal(nextRise)))), false);
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
                        + " | crestlifetime=" + c.waveCrestLifetimeSeconds + " | permanent=" + c.permanentSeaLevelRise
                        + " | tsunamidays=" + c.tsunamiIntervalDays + " | boateject=" + c.tsunamiEjectBoats
                        + " | randomschedule=" + c.randomSchedule + " | randommindays=" + c.randomMinDays
                        + " | randommaxdays=" + c.randomMaxDays + " | waitscan=" + c.waitForChunkPass
                        + " | debug=" + c.debug;
                    ctx.getSource().sendSuccess(() -> Component.literal(PREFIX).append(Component.translatable("risingsea.chat.config",
                        c.floodMode,c.intervalSeconds,c.riseBlocks,c.maxWaterLevel,c.enableFlooding,c.floodCaves,
                        c.showCountdown,c.blocksPerTick,c.chunkRadius,c.rescanSeconds,c.pauseWhenEmpty,c.announceRise,
                        c.chunkProcessingMode,c.preloadRadiusChunks,c.processNewChunks,c.blockUnpreparedChunkEntry,
                        c.waveHeightBlocks,c.waveWidthBlocks,c.waveSpeedBlocksPerSecond,c.waveMaxRadiusBlocks,
                        c.waveCrestLifetimeSeconds,c.permanentSeaLevelRise,c.tsunamiIntervalDays,c.tsunamiEjectBoats,
                        c.randomSchedule,c.randomMinDays,c.randomMaxDays,c.waitForChunkPass,c.debug)), false);
                    return 1;
                }))
                .then(Commands.literal("set").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("interval").then(Commands.argument("value", IntegerArgumentType.integer(1, 86400)).executes(ctx -> { setNumber("interval", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "interval"); })))
                    .then(Commands.literal("randommindays").then(Commands.argument("value", IntegerArgumentType.integer(1, 365)).executes(ctx -> { setNumber("randommindays", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "randommindays"); })))
                    .then(Commands.literal("randommaxdays").then(Commands.argument("value", IntegerArgumentType.integer(1, 365)).executes(ctx -> { setNumber("randommaxdays", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "randommaxdays"); })))
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
                    .then(Commands.literal("tsunamidays").then(Commands.argument("value", IntegerArgumentType.integer(0, 365)).executes(ctx -> { setNumber("tsunamidays", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "tsunamidays"); })))
                    .then(Commands.literal("crestlifetime").then(Commands.argument("value", IntegerArgumentType.integer(1, 30)).executes(ctx -> { setNumber("crestlifetime", IntegerArgumentType.getInteger(ctx, "value")); return changed(ctx.getSource().getServer(), "crestlifetime"); })))
                    .then(Commands.literal("randomschedule").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("randomschedule", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "randomschedule"); })))
                    .then(Commands.literal("waitscan").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("waitscan", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "waitscan"); })))
                    .then(Commands.literal("debug").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("debug", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "debug"); })))
                    .then(Commands.literal("enabled").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("enabled", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "enabled"); })))
                    .then(Commands.literal("caves").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("caves", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "caves"); })))
                    .then(Commands.literal("countdown").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("countdown", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "countdown"); })))
                    .then(Commands.literal("announce").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("announce", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "announce"); })))
                    .then(Commands.literal("pauseempty").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("pauseempty", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "pauseempty"); })))
                    .then(Commands.literal("newchunks").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("newchunks", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "newchunks"); })))
                    .then(Commands.literal("guard").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("guard", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "guard"); })))
                    .then(Commands.literal("boateject").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("boateject", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "boateject"); })))
                    .then(Commands.literal("permanent").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("permanent", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "permanent"); }))))
                .then(Commands.literal("start").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    save.running = true; saveSave(); broadcastSnapshot(); broadcastTranslated(ctx.getSource().getServer(), "started"); return 1;
                }))
                .then(Commands.literal("stop").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    save.running = false; saveSave(); broadcastSnapshot(); broadcastTranslated(ctx.getSource().getServer(), "paused"); return 1;
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
                        previousSurfaceLevel = save.level;
                        save.level = Math.max(-64, Math.min(config.maxWaterLevel, requested));
                        save.waveActive = false;
                        worker.reset(); worker.reconcileLevel(save.level); saveSave(); broadcastSnapshot();
                        broadcastTranslated(ctx.getSource().getServer(), "level", save.level);
                        return 1;
                    })))
                .then(Commands.literal("rise").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
                        previousSurfaceLevel = save.level;
                        save.level = Math.min(config.maxWaterLevel, save.level + IntegerArgumentType.getInteger(ctx, "blocks"));
                        save.elapsedTicks = 0; save.waveActive = false; worker.reset(); worker.reconcileLevel(save.level); saveSave(); broadcastSnapshot();
                        broadcastTranslated(ctx.getSource().getServer(), "level_now", save.level);
                        return 1;
                    })))
                .then(Commands.literal("reload").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    loadConfig(); synchronizeSchedule(true); worker.reset(); broadcastSnapshot();
                    ctx.getSource().sendSuccess(() -> Component.literal(PREFIX).append(Component.translatable("risingsea.chat.reload")), false);
                    return 1;
                }))
            )
        );
    }

    private int changed(MinecraftServer server, String key) {
        broadcastTranslated(server, "modified", key);
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

    private long chooseRandomIntervalTicks() {
        int days = java.util.concurrent.ThreadLocalRandom.current().nextInt(
            config.randomMinDays, config.randomMaxDays + 1);
        return days * TICKS_PER_DAY;
    }

    private long currentIntervalTicks() {
        if ("TSUNAMI".equals(config.floodMode)) {
            return config.tsunamiIntervalDays == 0 ? -1L : config.tsunamiIntervalDays * TICKS_PER_DAY;
        }
        if (!config.randomSchedule) return config.intervalSeconds * (long) TICKS_PER_SECOND;
        long lo = config.randomMinDays * TICKS_PER_DAY;
        long hi = config.randomMaxDays * TICKS_PER_DAY;
        if (save.scheduledIntervalTicks < lo || save.scheduledIntervalTicks > hi) {
            save.scheduledIntervalTicks = chooseRandomIntervalTicks();
        }
        return save.scheduledIntervalTicks;
    }

    private void synchronizeSchedule(boolean resetClock) {
        if (save.scheduledRandomMode != config.randomSchedule) resetClock = true;
        if (resetClock) save.elapsedTicks = 0;
        if (resetClock || save.scheduledIntervalTicks < config.randomMinDays * TICKS_PER_DAY
            || save.scheduledIntervalTicks > config.randomMaxDays * TICKS_PER_DAY) {
            save.scheduledIntervalTicks = config.randomSchedule ? chooseRandomIntervalTicks() : 0;
        }
        save.scheduledRandomMode = config.randomSchedule;
    }

    /** Origin is the first Overworld player. The wave radius advances in real server ticks. */
    private void triggerWave(MinecraftServer server, int rise) {
        ServerPlayer origin = null;
        for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
            if (candidate.level().dimension().equals(Level.OVERWORLD)) { origin = candidate; break; }
        }
        if (origin == null) { broadcastTranslated(server, "wave_need_player"); return; }
        save.waveX = origin.blockPosition().getX();
        save.waveZ = origin.blockPosition().getZ();
        previousSurfaceLevel = save.level;
        save.waveBaseLevel = save.level;
        save.waveDestinationLevel = Math.min(config.maxWaterLevel, save.level + rise);
        save.waveElapsedTicks = 0;
        save.waveActive = true; waveEjectedBoats.clear();
        // The global target becomes authoritative immediately; chunks are applied lazily.
        if (config.permanentSeaLevelRise) save.level = save.waveDestinationLevel;
        worker.reset(); worker.reconcileLevel(save.level); saveSave(); broadcastSnapshot();
        broadcastTranslated(server, "wave_start", save.level);
    }

    private void tick(MinecraftServer server) {
        if (saveFile == null) return;
        gameTicks++;
        if (gameTicks % TICKS_PER_SECOND == 0 && config.showCountdown) broadcastSnapshot();
        if (config.pauseWhenEmpty && server.getPlayerList().getPlayers().isEmpty()) return;
        if (config.enableFlooding && save.running && save.level < config.maxWaterLevel) {
            boolean tsunami = "TSUNAMI".equals(config.floodMode);
            long interval = currentIntervalTicks();
            // -1 = manual-only tsunami. Never let the manual sentinel enter countdown arithmetic.
            if (interval > 0) {
                if (save.elapsedTicks < interval) save.elapsedTicks++;
                if (save.elapsedTicks >= interval && (!config.waitForChunkPass || worker.readyForNextStep())) {
                    save.elapsedTicks = 0;
                    if (config.randomSchedule && !tsunami) save.scheduledIntervalTicks = chooseRandomIntervalTicks();
                    int old = save.level;
                    if (tsunami) {
                        triggerWave(server, config.riseBlocks);
                    } else {
                        previousSurfaceLevel = save.level;
                        save.level = Math.min(config.maxWaterLevel, save.level + config.riseBlocks);
                        if (old != save.level) {
                            worker.reset(); saveSave(); broadcastSnapshot();
                            if (config.announceRise || config.debug) broadcastTranslated(server, "rise", save.level);
                        }
                    }
                }
            }
        }
        // Capture existing floating boats ONCE per actual target-level transition.
        // Never sweep/move arbitrary entities on each tick.
        if (boatObservedLevel != save.level) {
            scheduleBoatTransfers(server, boatObservedLevel, save.level);
            boatObservedLevel = save.level;
        }
        // Pausing the timer also pauses further terrain edits; previous water remains in the world.
        if (config.enableFlooding && save.running) {
            if (save.waveActive) {
                save.waveElapsedTicks++;
                if (save.waveElapsedTicks * config.waveSpeedBlocksPerSecond >= (long) config.waveMaxRadiusBlocks * 20) {
                    save.waveActive = false; waveEjectedBoats.clear();
                    saveSave();
                }
            }
            worker.tick(server, config, save, gameTicks);
            processBoatTransfers(server);
            if (save.waveActive && config.tsunamiEjectBoats) ejectBoatsAtWave(server);
            if (config.blockUnpreparedChunkEntry) guardPlayers(server);
        }
        if (!config.enableFlooding || !save.running) worker.cleanupOnly(server, save, gameTicks, Math.max(10, config.blocksPerTick/4));
        if (++ticksSinceSave >= 1200) { ticksSinceSave = 0; saveSave(); worker.saveLedger(waterLedgerFile); }
    }

    /** Register boats near the OLD sea surface at the moment the target changes.
     * Other entities are never touched. New boats placed later are not registered. */
    private void scheduleBoatTransfers(MinecraftServer server, int oldLevel, int newLevel) {
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null || oldLevel == newLevel) return;
        // Falling Sea insight: never force a boat down into terrain when water recedes.
        // Vanilla buoyancy/gravity handles the lowering naturally.
        if (newLevel < oldLevel) { boatTransfers.clear(); return; }
        Set<UUID> inspected = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != world) continue;
            for (AbstractBoat boat : world.getEntitiesOfClass(AbstractBoat.class,
                    player.getBoundingBox().inflate(96, 48, 96))) {
                UUID id = boat.getUUID();
                if (!inspected.add(id)) continue;
                BoatTransfer pending = boatTransfers.get(id);
                if (pending != null) {
                    // Rebase an uncompleted move when the sea level changes again.
                    boatTransfers.put(id, new BoatTransfer(
                        pending.initialY(), pending.targetY() + newLevel - oldLevel, gameTicks + 12000));
                    continue;
                }
                if (!BoatLiftPolicy.nearOldSurface(boat.getY(), oldLevel)) continue;
                BlockPos under = BlockPos.containing(boat.getX(), boat.getY() - 0.45, boat.getZ());
                if (world.getFluidState(under).isEmpty()
                        && world.getFluidState(under.below()).isEmpty()) continue;
                boatTransfers.put(id, new BoatTransfer(boat.getY(), boat.getY() + newLevel - oldLevel,
                    gameTicks + 12000));
            }
        }
    }

    /** Move each eligible boat at most once, after water at its new height exists.
     * Passengers remain mounted; horizontal velocity is left unchanged.
     * Vanilla handles all other buoyancy and entity movement. */
    private void processBoatTransfers(MinecraftServer server) {
        if (boatTransfers.isEmpty() || gameTicks % 5 != 0) return;
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) return;
        Set<UUID> inspected = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != world) continue;
            for (AbstractBoat boat : world.getEntitiesOfClass(AbstractBoat.class,
                    player.getBoundingBox().inflate(96, 64, 96))) {
                UUID id = boat.getUUID();
                if (!inspected.add(id)) continue;
                BoatTransfer move = boatTransfers.get(id);
                if (move == null) continue;
                if (gameTicks > move.expiresAtTick()) { boatTransfers.remove(id); continue; }
                if (save.waveActive && isAtWave(boat)) continue;
                double targetY = move.targetY();
                // Never snap a boat back if Vanilla buoyancy has already done its job,
                // or if the boat has since moved vertically for some unrelated reason.
                if (BoatLiftPolicy.alreadyAtTarget(boat.getY(), targetY)
                    || !BoatLiftPolicy.nearOldSurface(boat.getY(), (int)Math.floor(move.initialY()))) {
                    boatTransfers.remove(id);
                    continue;
                }
                BlockPos atTarget = BlockPos.containing(boat.getX(), targetY, boat.getZ());
                BlockPos belowTarget = BlockPos.containing(boat.getX(), targetY - 0.45, boat.getZ());
                if (!world.hasChunk(atTarget.getX() >> 4, atTarget.getZ() >> 4)) continue;
                boolean supported = !world.getFluidState(belowTarget).isEmpty()
                    || !world.getFluidState(atTarget).isEmpty();
                // Do not move a boat into solid terrain or an unfinished chunk.
                if (!supported || (!world.getBlockState(atTarget).isAir()
                    && !world.getBlockState(atTarget).is(Blocks.WATER))) continue;
                if (!world.getBlockState(atTarget.above()).isAir()
                    && !world.getBlockState(atTarget.above()).is(Blocks.WATER)) continue;
                boat.setPos(boat.getX(), targetY, boat.getZ());
                boatTransfers.remove(id);
            }
        }
        boatTransfers.entrySet().removeIf(entry -> gameTicks > entry.getValue().expiresAtTick());
    }

    private boolean isAtWave(AbstractBoat entity) {
        double radius = save.waveElapsedTicks * config.waveSpeedBlocksPerSecond / 20.0;
        double d = Math.hypot(entity.getX() - save.waveX, entity.getZ() - save.waveZ);
        return Math.abs(d - radius) < Math.max(3, config.waveWidthBlocks);
    }

    /** Affects every passenger (players and mobs) because passengers are detached from the boat. */
    private void ejectBoatsAtWave(MinecraftServer server) {
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != world) continue;
            for (AbstractBoat boat : world.getEntitiesOfClass(AbstractBoat.class,
                player.getBoundingBox().inflate(config.waveWidthBlocks + 48, 32, config.waveWidthBlocks + 48))) {
                // An empty boat must never be launched by the passenger-ejection feature.
                if (boat.getPassengers().isEmpty() || !isAtWave(boat)
                    || !waveEjectedBoats.add(boat.getUUID())) continue;
                boat.ejectPassengers();
                Vec3 direction = new Vec3(boat.getX() - save.waveX, 0, boat.getZ() - save.waveZ).normalize();
                boat.setDeltaMovement(direction.x * config.tsunamiLaunchVelocity,
                    Math.min(1.3, config.tsunamiLaunchVelocity), direction.z * config.tsunamiLaunchVelocity);
            }
        }
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
                    player.sendOverlayMessage(Component.translatable("risingsea.chat.chunk"));
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
        long scheduleTicks = currentIntervalTicks();
        // -1 means no automatic tsunami; the client must hide the timer rather than show 7+ billion years.
        payload.addProperty("nextRiseSeconds", scheduleTicks < 0 ? -1L :
            Math.max(0L, (scheduleTicks - save.elapsedTicks + 19) / 20));
        payload.addProperty("automaticRiseScheduled", scheduleTicks > 0);
        payload.addProperty("scanReady", !config.waitForChunkPass || worker.readyForNextStep());
        payload.addProperty("pendingChunks", worker.pendingCount());
        payload.addProperty("placedWaterBlocks", worker.placedBlocks());
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
    private void broadcastTranslated(MinecraftServer server, String key, Object... args) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(PREFIX).append(Component.translatable("risingsea.chat." + key, args)), false);
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
        config.randomMinDays = Math.max(1, Math.min(365, config.randomMinDays));
        config.randomMaxDays = Math.max(config.randomMinDays, Math.min(365, config.randomMaxDays));
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
        config.tsunamiIntervalDays = Math.max(0, Math.min(365, config.tsunamiIntervalDays));
        config.tsunamiLaunchVelocity = Math.max(0.1, Math.min(4.0, config.tsunamiLaunchVelocity));
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
        // Falling Sea v1.22 insight: scan the perimeter of each 16x16 slice first,
        // reducing visible vertical seams between adjacent chunks.
        private static final int[] BORDER_FIRST_COLUMNS = borderFirstColumns();
        private static int[] borderFirstColumns() {
            int[] result = new int[256]; int i = 0;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (x == 0 || x == 15 || z == 0 || z == 15) result[i++] = z * 16 + x;
            }
            for (int z = 1; z < 15; z++) for (int x = 1; x < 15; x++) result[i++] = z * 16 + x;
            return result;
        }
        private record Target(ServerLevel level, int x, int z) {}
        private record Crest(ServerLevel level, BlockPos pos, long expires) {}
        private final ArrayDeque<Target> pending = new ArrayDeque<>();
        private final ArrayDeque<Crest> crestCleanup = new ArrayDeque<>();
        private final Set<Long> crestPositions = new HashSet<>();
        // Tracks only water placed by THIS release; never touch unknown natural water on lowering.
        private final Set<Long> ownedWater = new HashSet<>();
        private final ArrayDeque<Long> pendingRemoval = new ArrayDeque<>();
        private final Set<Long> enqueued = new HashSet<>();
        private final Set<Long> seen = new HashSet<>();
        // Completed height per chunk in this server session. Persisted in level files via water blocks,
        // and reconstructed by scanning after restart. No false ready state on reload.
        private final Map<Long, Integer> completed = new HashMap<>();
        private Target current;
        private int column, y, idleTicks;
        private int targetSurface;
        private boolean hasScanned;
        private long totalPlaced;
        int pendingCount() { return pending.size() + (current == null ? 0 : 1); }
        long placedBlocks() { return totalPlaced; }
        boolean readyForNextStep() {
            return pending.isEmpty() && current == null;
        }

        private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
        boolean ready(int cx, int cz, int level) { return completed.getOrDefault(key(cx, cz), Integer.MIN_VALUE) >= level; }
        void reset() {
            pending.clear(); enqueued.clear(); seen.clear(); completed.clear(); /* keep crestCleanup and owned-water ledger across rescan */
            current = null; column = y = idleTicks = 0; hasScanned = false;
        }

        void clearAll() {
            reset(); totalPlaced = 0;
            crestCleanup.clear(); crestPositions.clear(); ownedWater.clear(); pendingRemoval.clear();
        }

        void reconcileLevel(int level) {
            pendingRemoval.clear();
            for (long encoded : ownedWater) {
                if (BlockPos.of(encoded).getY() > level) pendingRemoval.addLast(encoded);
            }
        }
        void loadLedger(Path file) {
            ownedWater.clear(); pendingRemoval.clear();
            if (file == null || !Files.exists(file)) return;
            try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
                int count = input.readInt();
                if (count < 0 || count > 100_000_000) throw new java.io.IOException("Invalid ledger size");
                for (int i=0; i<count; i++) ownedWater.add(input.readLong());
            } catch (Exception e) {System.err.println(PREFIX + "Wasser-Ledger konnte nicht gelesen werden: " + e);}
        }
        void saveLedger(Path file) {
            if (file == null) return;
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
                out.writeInt(ownedWater.size());
                for (long encoded : ownedWater) out.writeLong(encoded);
            } catch (Exception e) {System.err.println(PREFIX + "Wasser-Ledger konnte nicht gespeichert werden: " + e);}
        }

        void tick(MinecraftServer server, Settings cfg, Save save, long ticks) {
            // During a traveling tsunami discover newly reached chunks every second.
            int scanTicks = save.waveActive && "TSUNAMI".equals(cfg.floodMode) ? 20 : cfg.rescanSeconds * 20;
            if (++idleTicks >= scanTicks || (!hasScanned && current == null && pending.isEmpty())) {
                idleTicks = 0;
                discover(server, cfg, save);
                hasScanned = true;
            }
            int budget = cfg.blocksPerTick;
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            // Removing own water is prioritised when lowering the sea. Unloaded chunks are
            // deferred and retried, and naturally existing water is never deliberately touched.
            for (int i=0, n=Math.min(budget / 3 + 1, pendingRemoval.size()); i<n; i++) {
                long encoded = pendingRemoval.pollFirst();
                BlockPos pos = BlockPos.of(encoded);
                if (overworld == null || !overworld.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                    pendingRemoval.addLast(encoded); continue;
                }
                if (pos.getY() <= save.level) {ownedWater.remove(encoded); continue;}
                if (overworld.getBlockState(pos).is(Blocks.WATER)) overworld.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                ownedWater.remove(encoded);
            }
            int cleanupBudget = Math.max(1, budget / 5);
            cleanupOnly(server, save, ticks, cleanupBudget);
            // Prevent the transient crest list from growing indefinitely in very long sessions.
            // Never silently drop the cleanup list: that would strand temporary water blocks.
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
                int cell = BORDER_FIRST_COLUMNS[column];
                int x = (cx << 4) + (cell & 15);
                int z = (cz << 4) + (cell >>> 4);
                BlockPos pos = new BlockPos(x, y++, z);
                BlockState old = level.getBlockState(pos);
                if (!old.isAir()) continue; // preserve structures, plants and all non-air blocks
                if (!cfg.floodCaves && !level.canSeeSky(pos)) continue;
                boolean fill = !"REALISTIC".equals(cfg.floodMode) || hasWetNeighbor(level, pos);
                if (fill) {
                    level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
                    ownedWater.add(pos.asLong()); totalPlaced++;
                }
            }
        }

        /**
         * A moving, NON-SOURCE crest. Placing real source-water above the permanent sea level
         * causes Vanilla fluid updates to create untracked source/flow blocks. Removing only
         * the original sources cannot make that flood recede. We therefore represent the
         * transient crest with spray / falling-water particles, while the actual permanent
         * sea-level change remains ordinary water blocks and is processed behind the front.
         * Particles are ephemeral, so a passing crest cannot leave water walls behind.
         */
        private int renderWave(MinecraftServer server, Settings cfg, Save save, long ticks, int limit) {
            if (!save.waveActive || limit <= 0 || ticks % 4 != 0 || !"TSUNAMI".equals(cfg.floodMode)) return 0;
            ServerLevel world = server.getLevel(Level.OVERWORLD);
            if (world == null) return 0;
            double radius = save.waveElapsedTicks * cfg.waveSpeedBlocksPerSecond / 20.0;
            if (radius < 1) return 0;
            limit = Math.min(limit, 96); // throttle packet volume independently of block-update budget
            int used = 0;
            int height = Math.min(cfg.waveHeightBlocks, world.getMaxY() - 1 - save.waveDestinationLevel);
            if (height <= 0) return 0;
            // Only nearby, loaded sections are illustrated; distant parts of the circle
            // are not materialized, preventing global packet/particle spikes.
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.level() != world || used >= limit) continue;
                double dx = player.getX() - save.waveX;
                double dz = player.getZ() - save.waveZ;
                double playerRadius = Math.hypot(dx, dz);
                if (Math.abs(playerRadius - radius) > 96 + cfg.waveWidthBlocks) continue;
                double playerAngle = Math.atan2(dz, dx);
                int samples = Math.min(160, Math.max(24, limit / 3));
                for (int i = 0; i < samples && used < limit; i++) {
                    double angle = playerAngle + (i - samples / 2.0) * (1.7 / samples);
                    // Wave has a broad leading face and a narrower trailing foamy shoulder.
                    int bands = Math.max(2, Math.min(5, cfg.waveWidthBlocks / 3));
                    for (int band = 0; band < bands && used < limit; band++) {
                        double rr = Math.max(0, radius - band * (cfg.waveWidthBlocks / (double) bands));
                        int x = save.waveX + (int) Math.round(Math.cos(angle) * rr);
                        int z = save.waveZ + (int) Math.round(Math.sin(angle) * rr);
                        if (!world.hasChunk(x >> 4, z >> 4)) continue;
                        double profile = Math.sin(Math.PI * (band + 1.0) / (bands + 1.0));
                        double top = save.waveDestinationLevel + height * profile;
                        // Three-dimensional rising foam. It is never a permanent water source.
                        world.sendParticles(ParticleTypes.SPLASH, x + .5, top, z + .5,
                            2, .18, .35, .18, .02);
                        world.sendParticles(ParticleTypes.FALLING_WATER, x + .5,
                            save.waveDestinationLevel + .5 + height * profile * .5, z + .5,
                            2, .28, Math.max(.2, height * profile * .3), .28, 0.0);
                        used++;
                    }
                }
            }
            return used;
        }

        void cleanupOnly(MinecraftServer server, Save save, long ticks, int budget) {
            for (int i=0; i<budget && !crestCleanup.isEmpty(); i++) {
                Crest crest = crestCleanup.peekFirst();
                if (crest.expires() > ticks) break;
                crestCleanup.pollFirst();
                BlockPos p = crest.pos();
                if (!crest.level().hasChunk(p.getX() >> 4, p.getZ() >> 4)) {
                    crestCleanup.addLast(new Crest(crest.level(), p, ticks + 40));
                    continue;
                }
                crestPositions.remove(p.asLong());
                if (crest.level().hasChunk(p.getX() >> 4, p.getZ() >> 4)
                    && crest.level().getBlockState(p).is(Blocks.WATER)
                    && p.getY() > save.level) {
                    crest.level().setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        private void discover(MinecraftServer server, Settings cfg, Save save) {
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
                        // A tsunami increases the global TARGET immediately, but does not
                        // place the new permanent water AHEAD of the traveling front.
                        // Once the wave ends, the ordinary chunk reconciliation catches up.
                        if (save.waveActive && "TSUNAMI".equals(cfg.floodMode)) {
                            double traveled = save.waveElapsedTicks * cfg.waveSpeedBlocksPerSecond / 20.0;
                            double dxWave = x * 16.0 + 8 - save.waveX;
                            double dzWave = z * 16.0 + 8 - save.waveZ;
                            if (Math.hypot(dxWave, dzWave) > traveled - cfg.waveWidthBlocks) continue;
                        }
                        long k = key(x, z);
                        if (!cfg.processNewChunks && hasScanned && !seen.contains(k)) continue;
                        seen.add(k);
                        if (round.add(k) && !enqueued.contains(k)
                            && !(cfg.waitForChunkPass && !"REALISTIC".equals(cfg.floodMode)
                                && completed.getOrDefault(k, Integer.MIN_VALUE) >= save.level)) {
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
