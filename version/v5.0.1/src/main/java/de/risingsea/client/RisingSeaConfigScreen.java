package de.risingsea.client;

import com.google.gson.JsonObject;
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
    private static Option n(String k, String name) { return new Option(k, name, "number"); }
    private static Option t(String k, String name) { return new Option(k, name, "toggle"); }
    private static Option action(String k, String name) { return new Option(k, name, "action"); }
    private static final List<List<Option>> PAGES = List.of(
        List.of(action("mode", "Flutmodus"), n("interval", "Intervall (Sekunden)"),
            n("riseblocks", "Anstieg (Bloecke)"), n("startlevel", "Startwasserstand Y"),
            n("maxlevel", "Maximale Hoehe Y"), n("countdownseconds", "Countdown-Sekunden"),
            t("enabled", "Flut aktiv"), t("countdown", "Countdown anzeigen"), t("announce", "Anstieg melden")),
        List.of(action("processing", "Chunk-Verarbeitung"), n("preload", "Vorausladen (Chunks)"),
            n("radius", "Umkreis (Chunks)"), n("budget", "Pruefungen / Tick"),
            n("rescan", "Neuscan (Sekunden)"), t("newchunks", "Neue Chunks anpassen"),
            t("guard", "Unfertige Chunks sperren (Beta)"), t("caves", "Hoehlen fluten"),
            t("pauseempty", "Pause ohne Spieler")),
        List.of(n("waveheight", "Tsunami-Hoehe"), n("wavewidth", "Wellenbreite"),
            n("wavespeed", "Wellentempo (Bloecke/s)"), n("wavemaxradius", "Maximaler Wellenradius"),
            n("crestlifetime", "Wellenkamm-Lebensdauer"), t("permanent", "Permanenter Anstieg"),
            action("trigger", "Tsunami jetzt ausloesen")),
        List.of(n("globallevel", "Globaler Zielwasserstand Y"),
            action("setlevel", "Wasserstand global setzen"),
            action("status", "Serverstatus im Chat"),
            action("refresh", "Serverwerte neu laden"))
    );
    private static final Map<String, String> JSON_NUMBERS = Map.ofEntries(
        Map.entry("interval", "intervalSeconds"), Map.entry("riseblocks", "riseBlocks"),
        Map.entry("startlevel", "startingWaterLevel"), Map.entry("maxlevel", "maxWaterLevel"),
        Map.entry("countdownseconds", "countdownLastSeconds"), Map.entry("preload", "preloadRadiusChunks"),
        Map.entry("radius", "chunkRadius"), Map.entry("budget", "blocksPerTick"),
        Map.entry("rescan", "rescanSeconds"), Map.entry("waveheight", "waveHeightBlocks"),
        Map.entry("wavewidth", "waveWidthBlocks"), Map.entry("wavespeed", "waveSpeedBlocksPerSecond"),
        Map.entry("wavemaxradius", "waveMaxRadiusBlocks"), Map.entry("crestlifetime", "waveCrestLifetimeSeconds"),
        Map.entry("globallevel", "currentWaterLevel"));
    private static final Map<String, String> JSON_TOGGLES = Map.of(
        "enabled", "enableFlooding", "countdown", "showCountdown", "announce", "announceRise",
        "newchunks", "processNewChunks", "guard", "blockUnpreparedChunkEntry",
        "caves", "floodCaves", "pauseempty", "pauseWhenEmpty", "permanent", "permanentSeaLevelRise");

    private final Screen parent;
    private final Map<String,String> numbers = new LinkedHashMap<>();
    private final Map<String,Boolean> toggles = new LinkedHashMap<>();
    private final Set<String> changes = new HashSet<>();
    private String mode = "REALISTIC", processing = "AHEAD";
    private String message = "Serverwerte werden geladen ...";
    private boolean synced;
    private int page, scroll;

    public RisingSeaConfigScreen(Screen parent) {
        super(Component.literal("Rising Sea - Einstellungen"));
        this.parent = parent;
    }

    @Override protected void init() {
        clearWidgets();
        int x = width/2 - Math.min(320, width - 24)/2;
        int w = Math.min(320, width - 24);
        int footerY = height - 50;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> navigate(-1))
            .bounds(x, 28, 48, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Seite " + (page+1) + " / 4"), b -> {})
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
                addRenderableWidget(Button.builder(Component.literal(opt.name), b -> {})
                    .bounds(x, y, leftWidth-4, 20).build()).active = false;
                EditBox field = new EditBox(font, x+leftWidth, y, contentWidth-leftWidth, 20, Component.literal(opt.name));
                field.setValue(numbers.getOrDefault(opt.key, ""));
                field.setResponder(v -> {numbers.put(opt.key, v);changes.add(opt.key);});
                addRenderableWidget(field);
            } else if (opt.kind.equals("toggle")) {
                boolean value = toggles.getOrDefault(opt.key,false);
                addRenderableWidget(Button.builder(Component.literal(opt.name+": "+(value?"AN":"AUS")), b -> {
                    toggles.put(opt.key,!toggles.getOrDefault(opt.key,false));
                    changes.add(opt.key);
                    b.setMessage(Component.literal(opt.name+": "+(toggles.get(opt.key)?"AN":"AUS")));
                }).bounds(x,y,contentWidth,20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal(actionLabel(opt)), b -> invoke(opt.key))
                    .bounds(x,y,contentWidth,20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> {scroll=Math.max(0, scroll-1);init();})
            .bounds(x+w-40, top, 40, 20).build()).active = scroll > 0;
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> {scroll=Math.min(Math.max(0,all.size()-perPage),scroll+1);init();})
            .bounds(x+w-40, Math.max(top+23,footerY-39),40,20).build()).active = scroll+perPage < all.size();
        int bwidth = (w - 10)/3;
        addRenderableWidget(Button.builder(Component.literal("Anwenden"), b -> apply())
            .bounds(x,footerY,bwidth,20).build());
        addRenderableWidget(Button.builder(Component.literal("Aktualisieren"), b -> request())
            .bounds(x+bwidth+5,footerY,bwidth,20).build());
        addRenderableWidget(Button.builder(Component.literal("Schliessen"), b -> onClose())
            .bounds(x+(bwidth+5)*2,footerY,w-(bwidth+5)*2,20).build());
    }

    @Override public void added() {
        super.added();
        request();
    }

    private String actionLabel(Option o) {
        return switch(o.key) {
            case "mode" -> "Flutmodus: "+mode;
            case "processing" -> "Chunkmodus: "+processing;
            default -> o.name;
        };
    }
    private void invoke(String key) {
        switch(key) {
            case "mode" -> {mode = switch(mode){case "REALISTIC"->"LAYERS";case "LAYERS"->"TSUNAMI";default->"REALISTIC";};changes.add("mode");init();}
            case "processing" -> {processing = switch(processing){case "AHEAD"->"GLOBAL";case "GLOBAL"->"LOADED";default->"AHEAD";};changes.add("processing");init();}
            case "trigger" -> {send("risingsea tsunami trigger");message="Tsunami-Befehl gesendet";}
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
            message = "Neuer globaler Zielwasserstand angefordert: Y="+level;
        } catch (Exception e) {message="Ungueltiger Wasserstand: -64 bis 319";}
    }
    private void apply() {
        List<String> commands = new ArrayList<>();
        if (changes.contains("mode")) commands.add("risingsea mode "+mode.toLowerCase(Locale.ROOT));
        if (changes.contains("processing")) commands.add("risingsea processing "+processing.toLowerCase(Locale.ROOT));
        for (var entry: JSON_NUMBERS.entrySet()) {
            String key = entry.getKey();
            if (!changes.contains(key) || key.equals("globallevel")) continue;
            String value = numbers.getOrDefault(key,"").trim();
            try {Integer.parseInt(value);}catch(NumberFormatException e){message="Ungueltige Zahl: "+key;return;}
            commands.add("risingsea set "+key+" "+value);
        }
        for (var entry: JSON_TOGGLES.entrySet()) {
            if (changes.contains(entry.getKey())) commands.add("risingsea set "+entry.getKey()+" "+toggles.get(entry.getKey()));
        }
        for(String command:commands) send(command);
        changes.removeIf(k -> !k.equals("globallevel"));
        message = commands.isEmpty() ? "Keine Aenderung ausgewaehlt" : "Einstellungen gesendet; Server bestaetigt Werte";
        request();
    }
    private void navigate(int diff) {page=Math.floorMod(page+diff,4);scroll=0;init();}
    private void request() {
        if (Minecraft.getInstance().player == null) {
            message="Verbinde dich mit einer Welt, um Serverwerte zu laden";
            return;
        }
        ClientPlayNetworking.send(new ConfigRequestPayload("read"));
        message="Lade aktuelle Serverwerte ...";
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
        synced=true; message="Serverwerte synchronisiert | Ziel Y="+numbers.getOrDefault("globallevel","?");
        init();
    }
    private void send(String command) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.connection.sendCommand(command);
        else message="Befehle sind nur in einer Welt verfuegbar";
    }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
