package de.risingsea.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import de.risingsea.network.ConfigRequestPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Server-authoritative config editor with bounded rows and explicit paging. */
@Environment(EnvType.CLIENT)
public final class RisingSeaConfigScreen extends Screen {
    private record Option(String key, String name, String kind) {}
    private static Component tr(String key, Object... args) { return Component.translatable("risingsea."+key,args); }
    private static Component option(Option o) { return tr("option." + o.key); }
    private static Option n(String k, String name) { return new Option(k, name, "number"); }
    private static Option t(String k, String name) { return new Option(k, name, "toggle"); }
    private static Option action(String k, String name) { return new Option(k, name, "action"); }
    private static final List<List<Option>> PAGES = List.of(
        List.of(action("mode", "Flutmodus"), t("randomschedule", "Zufaellige MC-Tage"),
            n("randommindays", "Zufall min. MC-Tage"), n("randommaxdays", "Zufall max. MC-Tage"),
            n("interval", "Intervall (Sekunden)"),
            n("riseblocks", "Anstieg (Bloecke)"), n("startlevel", "Startwasserstand Y"),
            n("maxlevel", "Maximale Hoehe Y"), n("countdownseconds", "Countdown-Sekunden"),
            t("enabled", "Flut aktiv"), t("countdown", "Countdown anzeigen"), t("announce", "Anstieg melden")),
        List.of(action("processing", "Chunk-Verarbeitung"), n("preload", "Vorausladen (Chunks)"),
            n("radius", "Umkreis (Chunks)"), n("budget", "Pruefungen / Tick"),
            n("rescan", "Neuscan (Sekunden)"), t("newchunks", "Neue Chunks anpassen"),
            t("guard", "Unfertige Chunks sperren (Beta)"), t("waitscan", "Naechsten Anstieg abwarten"),
            t("debug", "Diagnose aktiv"), t("caves", "Hoehlen fluten"),
            t("pauseempty", "Pause ohne Spieler")),
        List.of(n("waveheight", "Tsunami-Hoehe"), n("wavewidth", "Wellenbreite"),
            n("wavespeed", "Wellentempo (Bloecke/s)"), n("wavemaxradius", "Maximaler Wellenradius"),
            n("crestlifetime", "Wellenkamm-Lebensdauer"), n("tsunamidays", "Tsunami alle X MC-Tage (0=aus)"),
            t("boateject", "Boot-Passagiere rauswerfen"), t("permanent", "Permanenter Anstieg"),
            action("trigger", "Tsunami jetzt ausloesen")),
        List.of(n("globallevel", "Globaler Zielwasserstand Y"),
            action("setlevel", "Wasserstand global setzen"),
            action("status", "Serverstatus im Chat"),
            action("refresh", "Serverwerte neu laden"))
    );
    private static final Map<String, String> JSON_NUMBERS = Map.ofEntries(
        Map.entry("interval", "intervalSeconds"), Map.entry("randommindays", "randomMinDays"),
        Map.entry("randommaxdays", "randomMaxDays"), Map.entry("riseblocks", "riseBlocks"),
        Map.entry("startlevel", "startingWaterLevel"), Map.entry("maxlevel", "maxWaterLevel"),
        Map.entry("countdownseconds", "countdownLastSeconds"), Map.entry("preload", "preloadRadiusChunks"),
        Map.entry("radius", "chunkRadius"), Map.entry("budget", "blocksPerTick"),
        Map.entry("rescan", "rescanSeconds"), Map.entry("waveheight", "waveHeightBlocks"),
        Map.entry("wavewidth", "waveWidthBlocks"), Map.entry("wavespeed", "waveSpeedBlocksPerSecond"),
        Map.entry("wavemaxradius", "waveMaxRadiusBlocks"), Map.entry("crestlifetime", "waveCrestLifetimeSeconds"),
        Map.entry("tsunamidays", "tsunamiIntervalDays"),
        Map.entry("globallevel", "currentWaterLevel"));
    private static final Map<String, String> JSON_TOGGLES = Map.ofEntries(
        Map.entry("enabled", "enableFlooding"), Map.entry("countdown", "showCountdown"),
        Map.entry("announce", "announceRise"), Map.entry("newchunks", "processNewChunks"),
        Map.entry("guard", "blockUnpreparedChunkEntry"), Map.entry("caves", "floodCaves"),
        Map.entry("pauseempty", "pauseWhenEmpty"), Map.entry("permanent", "permanentSeaLevelRise"),
        Map.entry("boateject", "tsunamiEjectBoats"), Map.entry("randomschedule", "randomSchedule"),
        Map.entry("waitscan", "waitForChunkPass"), Map.entry("debug", "debug"));

    // Values offered in the main menu before a server/world exists.
    private static final Map<String, String> DEFAULT_NUMBERS = Map.ofEntries(
        Map.entry("interval", "300"), Map.entry("randommindays", "1"), Map.entry("randommaxdays", "5"),
        Map.entry("riseblocks", "1"), Map.entry("startlevel", "63"), Map.entry("maxlevel", "250"),
        Map.entry("countdownseconds", "10"), Map.entry("preload", "5"), Map.entry("radius", "3"),
        Map.entry("budget", "1000"), Map.entry("rescan", "15"), Map.entry("waveheight", "8"),
        Map.entry("wavewidth", "12"), Map.entry("wavespeed", "4"), Map.entry("wavemaxradius", "1024"),
        Map.entry("crestlifetime", "5"), Map.entry("tsunamidays", "3"), Map.entry("globallevel", "63"));


    private final Screen parent;
    private final Map<String,String> numbers = new LinkedHashMap<>();
    private final Map<String,Boolean> toggles = new LinkedHashMap<>();
    private final Set<String> changes = new HashSet<>();
    private String mode = "REALISTIC", processing = "AHEAD";
    private Component message = tr("message.loading");
    private boolean synced;
    private int page, scroll;

    public RisingSeaConfigScreen(Screen parent) {
        super(tr("screen.title"));
        this.parent = parent;
        numbers.putAll(DEFAULT_NUMBERS);
        toggles.put("enabled", true); toggles.put("caves", true);
        toggles.put("countdown", true); toggles.put("announce", true);
        toggles.put("pauseempty", true); toggles.put("newchunks", true);
        toggles.put("permanent", true); toggles.put("boateject", true);
        toggles.put("waitscan", false);
    }

    @Override protected void init() {
        clearWidgets();
        int x = width/2 - Math.min(320, width - 24)/2;
        int w = Math.min(320, width - 24);
        int footerY = height - 50;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> navigate(-1))
            .bounds(x, 28, 48, 20).build());
        addRenderableWidget(Button.builder(tr("page", page+1, 4), b -> {})
            .bounds(x + 52, 28, w-104, 20).build()).active = false;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> navigate(1))
            .bounds(x+w-48, 28, 48, 20).build());
        int top = 57;
        int available = Math.max(20, footerY - 16 - top);
        int perPage = Math.max(1, available / 25);
        List<Option> all = PAGES.get(page);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - perPage)));
        int contentWidth = w - 46;
        for (int idx=scroll; idx<Math.min(all.size(), scroll+perPage); idx++) {
            Option opt = all.get(idx);
            int y = top + (idx-scroll)*25;
            if (opt.kind.equals("number")) {
                int leftWidth = Math.max(88, contentWidth * 58 / 100);
                addRenderableWidget(Button.builder(option(opt), b -> {})
                    .bounds(x, y, leftWidth-4, 20).build()).active = false;
                EditBox field = new EditBox(font, x+leftWidth, y, contentWidth-leftWidth, 20, option(opt));
                field.setValue(numbers.getOrDefault(opt.key, ""));
                field.setResponder(v -> {numbers.put(opt.key, v);changes.add(opt.key);});
                addRenderableWidget(field);
            } else if (opt.kind.equals("toggle")) {
                boolean value = toggles.getOrDefault(opt.key,false);
                addRenderableWidget(Button.builder(tr("toggle",option(opt).getString(),tr(value?"on":"off").getString()), b -> {
                    toggles.put(opt.key,!toggles.getOrDefault(opt.key,false));
                    changes.add(opt.key);
                    b.setMessage(tr("toggle",option(opt).getString(),tr(toggles.get(opt.key)?"on":"off").getString()));
                }).bounds(x,y,contentWidth,20).build());
            } else {
                addRenderableWidget(Button.builder(actionLabel(opt), b -> invoke(opt.key))
                    .bounds(x,y,contentWidth,20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {scroll=Math.max(0, scroll-1);init();})
            .bounds(x+w-40, top, 40, 20).build()).active = scroll > 0;
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {scroll=Math.min(Math.max(0,all.size()-perPage),scroll+1);init();})
            .bounds(x+w-40, Math.max(top+23,footerY-39),40,20).build()).active = scroll+perPage < all.size();
        int bwidth = (w - 10)/3;
        addRenderableWidget(Button.builder(tr("apply"), b -> apply())
            .bounds(x,footerY,bwidth,20).build());
        addRenderableWidget(Button.builder(tr("refresh"), b -> request())
            .bounds(x+bwidth+5,footerY,bwidth,20).build());
        addRenderableWidget(Button.builder(tr("close"), b -> onClose())
            .bounds(x+(bwidth+5)*2,footerY,w-(bwidth+5)*2,20).build());
    }

    @Override public void added() {
        super.added();
        request();
    }

    private Component actionLabel(Option o) {
        return switch(o.key) {
            case "mode" -> tr("selected.mode",tr("mode."+mode).getString()+("TSUNAMI".equals(mode)?" ("+tr("experimental").getString()+")":""));
            case "processing" -> tr("selected.processing",tr("processing."+processing).getString());
            default -> option(o);
        };
    }
    private void invoke(String key) {
        switch(key) {
            case "mode" -> {mode = switch(mode){case "REALISTIC"->"LAYERS";case "LAYERS"->"TSUNAMI";default->"REALISTIC";};changes.add("mode");init();}
            case "processing" -> {processing = switch(processing){case "AHEAD"->"GLOBAL";case "GLOBAL"->"LOADED";default->"AHEAD";};changes.add("processing");init();}
            case "trigger" -> {send("risingsea tsunami trigger");message=tr("message.trigger");}
            case "setlevel" -> setGlobalLevel();
            case "status" -> send("risingsea status");
            case "refresh" -> request();
        }
    }
    private void setGlobalLevel() {
        String value = numbers.get("globallevel");
        try {
            int level = Integer.parseInt(value.trim());
            if (level < -64 || level > 319) throw new NumberFormatException();
            send("risingsea setlevel "+level);
            changes.remove("globallevel");
            message = tr("message.level",level);
        } catch (Exception e) {message=tr("message.level_invalid");}
    }
    private void apply() {
        if (Minecraft.getInstance().player == null) {
            saveLocalConfig();
            return;
        }
        List<String> commands = new ArrayList<>();
        if (changes.contains("mode")) commands.add("risingsea mode "+mode.toLowerCase(Locale.ROOT));
        if (changes.contains("processing")) commands.add("risingsea processing "+processing.toLowerCase(Locale.ROOT));
        for (var entry: JSON_NUMBERS.entrySet()) {
            String key = entry.getKey();
            if (!changes.contains(key) || key.equals("globallevel")) continue;
            String value = numbers.getOrDefault(key,"").trim();
            try {Integer.parseInt(value);}catch(NumberFormatException e){message=tr("message.number",key);return;}
            commands.add("risingsea set "+key+" "+value);
        }
        for (var entry: JSON_TOGGLES.entrySet()) {
            if (changes.contains(entry.getKey())) commands.add("risingsea set "+entry.getKey()+" "+toggles.get(entry.getKey()));
        }
        for(String command:commands) send(command);
        changes.removeIf(k -> !k.equals("globallevel"));
        message = tr(commands.isEmpty()?"message.nochange":"message.sent");
        request();
    }
    // Main-menu defaults are client-local; in a world the server remains authoritative.
    private Path localConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("risingsea.json");
    }
    private void loadLocalConfig() {
        try {
            Path path = localConfigPath();
            if (Files.exists(path)) {
                JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                for (var entry : JSON_NUMBERS.entrySet()) {
                    if (!entry.getKey().equals("globallevel") && !changes.contains(entry.getKey())
                        && json.has(entry.getValue())) numbers.put(entry.getKey(), json.get(entry.getValue()).getAsString());
                }
                for (var entry : JSON_TOGGLES.entrySet()) {
                    if (!changes.contains(entry.getKey()) && json.has(entry.getValue()))
                        toggles.put(entry.getKey(), json.get(entry.getValue()).getAsBoolean());
                }
                if (!changes.contains("mode") && json.has("floodMode")) mode=json.get("floodMode").getAsString();
                if (!changes.contains("processing") && json.has("chunkProcessingMode")) processing=json.get("chunkProcessingMode").getAsString();
            }
            message=tr("message.defaults");
            synced=true;
            init();
        } catch (Exception e) { message=tr("message.local_error",e.getMessage()); }
    }
    private void saveLocalConfig() {
        try {
            Path path=localConfigPath();
            JsonObject json=Files.exists(path) ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : new JsonObject();
            for (var entry: JSON_NUMBERS.entrySet()) {
                if (!changes.contains(entry.getKey()) || entry.getKey().equals("globallevel")) continue;
                String raw=numbers.getOrDefault(entry.getKey(), "").trim();
                int value=Integer.parseInt(raw);
                json.addProperty(entry.getValue(), value);
            }
            for (var entry: JSON_TOGGLES.entrySet()) {
                if (changes.contains(entry.getKey())) json.addProperty(entry.getValue(), toggles.get(entry.getKey()));
            }
            if (changes.contains("mode")) json.addProperty("floodMode",mode);
            if (changes.contains("processing")) json.addProperty("chunkProcessingMode",processing);
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(json));
            changes.clear();
            message=tr("message.local_saved");
        } catch (NumberFormatException e) { message=tr("message.number",e.getMessage()); }
          catch (Exception e) { message=tr("message.save_error",e.getMessage()); }
    }

    private void navigate(int diff) {page=Math.floorMod(page+diff,4);scroll=0;init();}
    private void request() {
        if (Minecraft.getInstance().player == null) {
            loadLocalConfig();
            return;
        }
        ClientPlayNetworking.send(new ConfigRequestPayload("read"));
        message=tr("message.fetching");
    }
    public void receiveSnapshot(JsonObject data) {
        for(var e:JSON_NUMBERS.entrySet()) {
            if (!changes.contains(e.getKey()) && data.has(e.getValue())) numbers.put(e.getKey(),data.get(e.getValue()).getAsString());
        }
        for(var e:JSON_TOGGLES.entrySet()) {
            if (!changes.contains(e.getKey()) && data.has(e.getValue())) toggles.put(e.getKey(),data.get(e.getValue()).getAsBoolean());
        }
        if(!changes.contains("mode") && data.has("floodMode"))mode=data.get("floodMode").getAsString();
        if(!changes.contains("processing") && data.has("chunkProcessingMode"))processing=data.get("chunkProcessingMode").getAsString();
        synced=true; message=tr("message.synced",numbers.getOrDefault("globallevel","?"));
        init();
    }
    private void send(String command) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.connection.sendCommand(command);
        else message=tr("message.need_world");
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
