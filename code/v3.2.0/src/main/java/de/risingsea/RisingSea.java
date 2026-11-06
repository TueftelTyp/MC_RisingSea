package de.risingsea;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
    }

    private static final class Save {
        int level = 63;
        long elapsedTicks;
        boolean running = true;
    }

    @Override public void onInitialize() {
        loadConfig();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            saveFile = server.getWorldPath(LevelResource.ROOT).resolve("risingsea-state.json");
            loadSave();
            worker.reset();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveSave();
            worker.reset();
            saveFile = null;
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
            default -> throw new IllegalArgumentException(key);
        }
        validateConfig();
        writeConfig();
        worker.reset();
    }

    private void setToggle(String key, boolean value) {
        switch (key) {
            case "enabled" -> config.enableFlooding = value;
            case "caves" -> config.floodCaves = value;
            case "countdown" -> config.showCountdown = value;
            case "announce" -> config.announceRise = value;
            case "pauseempty" -> config.pauseWhenEmpty = value;
            default -> throw new IllegalArgumentException(key);
        }
        writeConfig();
        worker.reset();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("risingsea")
                .then(Commands.literal("status").executes(ctx -> {
                    long left = Math.max(0, (long) config.intervalSeconds * TICKS_PER_SECOND - save.elapsedTicks);
                    ctx.getSource().sendSuccess(() -> Component.literal(PREFIX + "Wasser Y=" + save.level
                        + " | Modus=" + config.floodMode + " | aktiv=" + save.running
                        + " | nächster Anstieg in " + ((left + 19) / 20) + "s"), false);
                    return 1;
                }))
                .then(Commands.literal("config").executes(ctx -> {
                    Settings c = config;
                    String text = "Modus=" + c.floodMode + " | interval=" + c.intervalSeconds
                        + "s | riseblocks=" + c.riseBlocks + " | maxlevel=" + c.maxWaterLevel
                        + " | enabled=" + c.enableFlooding + " | caves=" + c.floodCaves
                        + " | countdown=" + c.showCountdown + " | budget=" + c.blocksPerTick
                        + " | radius=" + c.chunkRadius + " | rescan=" + c.rescanSeconds
                        + " | pauseempty=" + c.pauseWhenEmpty + " | announce=" + c.announceRise;
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
                    .then(Commands.literal("enabled").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("enabled", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "enabled"); })))
                    .then(Commands.literal("caves").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("caves", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "caves"); })))
                    .then(Commands.literal("countdown").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("countdown", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "countdown"); })))
                    .then(Commands.literal("announce").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("announce", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "announce"); })))
                    .then(Commands.literal("pauseempty").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> { setToggle("pauseempty", BoolArgumentType.getBool(ctx, "value")); return changed(ctx.getSource().getServer(), "pauseempty"); }))))
                .then(Commands.literal("start").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    save.running = true; saveSave(); broadcast(ctx.getSource().getServer(), "Flut gestartet."); return 1;
                }))
                .then(Commands.literal("stop").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    save.running = false; saveSave(); broadcast(ctx.getSource().getServer(), "Flut pausiert."); return 1;
                }))
                .then(Commands.literal("mode").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.literal("realistic").executes(ctx -> { changeMode("REALISTIC"); return 1; }))
                    .then(Commands.literal("layers").executes(ctx -> { changeMode("LAYERS"); return 1; })))
                .then(Commands.literal("setlevel").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.argument("y", IntegerArgumentType.integer(-64, 319)).executes(ctx -> {
                        save.level = IntegerArgumentType.getInteger(ctx, "y");
                        worker.reset(); saveSave();
                        broadcast(ctx.getSource().getServer(), "Zielwasserstand auf Y=" + save.level + " gesetzt.");
                        return 1;
                    })))
                .then(Commands.literal("rise").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
                    .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
                        save.level = Math.min(config.maxWaterLevel, save.level + IntegerArgumentType.getInteger(ctx, "blocks"));
                        save.elapsedTicks = 0; worker.reset(); saveSave();
                        broadcast(ctx.getSource().getServer(), "Wasserstand jetzt Y=" + save.level);
                        return 1;
                    })))
                .then(Commands.literal("reload").requires(s -> s.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR)).executes(ctx -> {
                    loadConfig(); worker.reset();
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
    }

    private void tick(MinecraftServer server) {
        if (saveFile == null || !config.enableFlooding) return;
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
                save.level = Math.min(config.maxWaterLevel, save.level + config.riseBlocks);
                if (old != save.level) {
                    worker.reset();
                    saveSave();
                    if (config.announceRise) broadcast(server, "Meeresspiegel auf Y=" + save.level + " gestiegen!");
                }
            }
        }
        // Pausing the timer also pauses further terrain edits; previous water remains in the world.
        if (save.running) worker.tick(server, config, save.level);
        if (++ticksSinceSave >= 1200) { ticksSinceSave = 0; saveSave(); }
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
        if (config.floodMode == null || !config.floodMode.equalsIgnoreCase("LAYERS")) config.floodMode = "REALISTIC";
        else config.floodMode = "LAYERS";
        config.intervalSeconds = Math.max(1, Math.min(86400, config.intervalSeconds));
        config.riseBlocks = Math.max(1, Math.min(100, config.riseBlocks));
        config.startingWaterLevel = Math.max(-64, Math.min(319, config.startingWaterLevel));
        config.maxWaterLevel = Math.max(config.startingWaterLevel, Math.min(319, config.maxWaterLevel));
        config.blocksPerTick = Math.max(1, Math.min(5000, config.blocksPerTick));
        config.chunkRadius = Math.max(0, Math.min(8, config.chunkRadius));
        config.rescanSeconds = Math.max(1, Math.min(600, config.rescanSeconds));
        config.countdownLastSeconds = Math.max(0, Math.min(60, config.countdownLastSeconds));
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
        saveSave();
    }

    private void saveSave() {
        if (saveFile == null) return;
        try (Writer out = Files.newBufferedWriter(saveFile)) { GSON.toJson(save, out); }
        catch (Exception e) { System.err.println(PREFIX + "Speichern fehlgeschlagen: " + e); }
    }

    /** Round-robin, scan-budgeted approach. No unloaded chunk is ever forced to load. */
    private static final class FloodWorker {
        private record Target(ServerLevel level, int x, int z) {}
        private final ArrayDeque<Target> pending = new ArrayDeque<>();
        private final Set<String> known = new HashSet<>();
        private Target current;
        private int column;
        private int y;
        private int idleTicks = 12000;

        void reset() {
            pending.clear(); known.clear(); current = null;
            column = y = idleTicks = 0;
        }

        void tick(MinecraftServer server, Settings cfg, int surface) {
            // Never throw away an unfinished sweep on a fixed timer.
            if (current == null && pending.isEmpty()) {
                if (++idleTicks < cfg.rescanSeconds * 20) return;
                idleTicks = 0;
                discover(server, cfg.chunkRadius);
            }
            int remaining = cfg.blocksPerTick;
            while (remaining-- > 0) {
                if (current == null) {
                    current = pending.pollFirst();
                    if (current == null) return;
                    column = 0;
                    y = cfg.startingWaterLevel;
                }
                ServerLevel level = current.level();
                if (!level.hasChunk(current.x(), current.z())) { current = null; continue; }
                int lowest = Math.max(cfg.startingWaterLevel, level.getMinY());
                int highest = Math.min(surface, level.getMaxY() - 1);
                if (highest < lowest) { current = null; continue; }
                if (y < lowest) y = lowest;
                if (y > highest) {
                    y = lowest;
                    if (++column == 256) current = null;
                    continue;
                }
                int x = (current.x() << 4) + (column & 15);
                int z = (current.z() << 4) + (column >>> 4);
                BlockPos pos = new BlockPos(x, y++, z);
                BlockState old = level.getBlockState(pos);
                if (!old.isAir()) continue; // no griefing: preserve solid blocks, plants, and player builds
                if (!cfg.floodCaves && !level.canSeeSky(pos)) continue;
                boolean fill = "LAYERS".equals(cfg.floodMode) || hasWetNeighbor(level, pos);
                if (fill) level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
            }
        }

        private void discover(MinecraftServer server, int radius) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ServerLevel level = player.level();
                if (!level.dimension().equals(Level.OVERWORLD)) continue;
                int cx = player.blockPosition().getX() >> 4;
                int cz = player.blockPosition().getZ() >> 4;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = cx + dx, z = cz + dz;
                        String key = x + ":" + z;
                        if (known.add(key) && level.hasChunk(x, z)) pending.addLast(new Target(level, x, z));
                    }
                }
            }
            known.clear(); // only dedupe during this sweep; rescan later for water spread/new chunks
        }

        private boolean hasWetNeighbor(ServerLevel level, BlockPos p) {
            // Avoid triggering chunk loads at target borders.
            return wet(level, p.north()) || wet(level, p.south()) || wet(level, p.east())
                || wet(level, p.west()) || wet(level, p.below());
        }
        private boolean wet(ServerLevel level, BlockPos p) {
            return level.hasChunk(p.getX() >> 4, p.getZ() >> 4)
                && !level.getFluidState(p).isEmpty();
        }
    }
}
