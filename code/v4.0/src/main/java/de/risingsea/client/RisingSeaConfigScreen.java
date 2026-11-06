package de.risingsea.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Paginated Mod Menu config. Values live on the server; commands require OP permissions. */
@Environment(EnvType.CLIENT)
public final class RisingSeaConfigScreen extends Screen {
    private final Screen parent;
    private final Map<String, String> numbers = new LinkedHashMap<>();
    private final Map<String, Boolean> toggles = new LinkedHashMap<>();
    private final Set<String> changed = new HashSet<>();
    private int page;
    private String mode = "REALISTIC";
    private String processing = "AHEAD";
    private String message = "Nur geaenderte Werte werden gesendet. Server hat Vorrang.";

    public RisingSeaConfigScreen(Screen parent) {
        super(Component.literal("Rising Sea - Einstellungen"));
        this.parent = parent;
        numbers.put("interval", "300");
        numbers.put("riseblocks", "1");
        numbers.put("startlevel", "63");
        numbers.put("maxlevel", "250");
        numbers.put("budget", "1000");
        numbers.put("radius", "3");
        numbers.put("rescan", "15");
        numbers.put("preload", "5");
        numbers.put("waveheight", "8");
        numbers.put("wavewidth", "12");
        numbers.put("wavespeed", "4");
        numbers.put("wavemaxradius", "1024");
        numbers.put("crestlifetime", "5");
        numbers.put("countdownseconds", "10");
        for (String key : new String[]{"enabled", "caves", "countdown", "announce", "pauseempty", "newchunks", "guard", "permanent"}) {
            toggles.put(key, !key.equals("guard"));
        }
    }

    @Override protected void init() {
        int left = width / 2 - 158;
        int y = 30;
        addRenderableWidget(Button.builder(Component.literal("< Vorherige"), b -> navigate(-1))
            .bounds(left, y, 105, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Seite " + (page + 1) + " / 3"), b -> {})
            .bounds(left + 110, y, 100, 20).build()).active = false;
        addRenderableWidget(Button.builder(Component.literal("Naechste >"), b -> navigate(1))
            .bounds(left + 215, y, 105, 20).build());
        y += 29;
        if (page == 0) {
            addRenderableWidget(Button.builder(Component.literal("Flutmodus: " + mode), b -> {
                mode = switch (mode) { case "REALISTIC" -> "LAYERS"; case "LAYERS" -> "TSUNAMI"; default -> "REALISTIC"; };
                changed.add("mode"); b.setMessage(Component.literal("Flutmodus: " + mode));
            }).bounds(left, y, 320, 20).build());
            y += 26;
            y = addNumber(left, y, "interval", "Intervall in Sekunden");
            y = addNumber(left, y, "riseblocks", "Meeresspiegel + Bloecke");
            y = addNumber(left, y, "startlevel", "Startwasserstand Y");
            y = addNumber(left, y, "maxlevel", "Maximale Hoehe Y");
            y = addNumber(left, y, "countdownseconds", "Countdown-Sekunden");
            y = addToggle(left, y, "enabled", "Flut einschalten");
            y = addToggle(left, y, "countdown", "Countdown anzeigen");
            y = addToggle(left, y, "announce", "Wasseranstieg ansagen");
        } else if (page == 1) {
            addRenderableWidget(Button.builder(Component.literal("Chunkmodus: " + processing), b -> {
                processing = switch (processing) { case "AHEAD" -> "GLOBAL"; case "GLOBAL" -> "LOADED"; default -> "AHEAD"; };
                changed.add("processing"); b.setMessage(Component.literal("Chunkmodus: " + processing));
            }).bounds(left, y, 320, 20).build());
            y += 26;
            y = addNumber(left, y, "preload", "Vorladeradius in Chunks");
            y = addNumber(left, y, "radius", "Basisradius in Chunks");
            y = addNumber(left, y, "budget", "Max. Pruefungen pro Tick");
            y = addNumber(left, y, "rescan", "Neuscan in Sekunden");
            y = addToggle(left, y, "newchunks", "Neue Chunks angleichen");
            y = addToggle(left, y, "guard", "Unfertige Chunks sperren (Beta)");
            y = addToggle(left, y, "caves", "Hoehlen mit fluten");
            y = addToggle(left, y, "pauseempty", "Pausieren ohne Spieler");
        } else {
            y = addNumber(left, y, "waveheight", "Tsunami-Wellenhoehe");
            y = addNumber(left, y, "wavewidth", "Tsunami-Wellenbreite");
            y = addNumber(left, y, "wavespeed", "Wellengeschwindigkeit B/s");
            y = addNumber(left, y, "wavemaxradius", "Max. Ausbreitungsradius");
            y = addNumber(left, y, "crestlifetime", "Wellenkamm-Dauer (s)");
            y = addToggle(left, y, "permanent", "Meeresspiegel bleibt oben");
            addRenderableWidget(Button.builder(Component.literal("Tsunami jetzt ausloesen"), b -> {
                send("risingsea tsunami trigger"); message = "Befehl gesendet: Tsunami trigger";
            }).bounds(left, y + 4, 320, 20).build());
        }
        int footer = Math.max(y + 29, height - 78);
        footer = Math.min(footer, height - 52);
        addRenderableWidget(Button.builder(Component.literal("Anwenden"), b -> apply())
            .bounds(left, footer, 102, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Serverwerte"), b -> {
            send("risingsea config"); message = "Serverwerte im Chat. Eingabefelder sind Vorschlagswerte.";
        }).bounds(left + 108, footer, 102, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Schliessen"), b -> onClose())
            .bounds(left + 216, footer, 104, 20).build());
    }

    private void navigate(int delta) {
        page = Math.floorMod(page + delta, 3);
        clearWidgets(); init();
    }

    private int addNumber(int left, int y, String key, String caption) {
        EditBox box = new EditBox(font, left + 174, y, 146, 20, Component.literal(caption));
        box.setValue(numbers.get(key));
        box.setResponder(value -> { numbers.put(key, value); changed.add(key); });
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.literal(caption), b -> {})
            .bounds(left, y, 170, 20).build()).active = false;
        return y + 24;
    }

    private int addToggle(int left, int y, String key, String caption) {
        addRenderableWidget(Button.builder(Component.literal(caption + ": " + (toggles.get(key) ? "AN" : "AUS")), b -> {
            toggles.put(key, !toggles.get(key)); changed.add(key);
            b.setMessage(Component.literal(caption + ": " + (toggles.get(key) ? "AN" : "AUS")));
        }).bounds(left, y, 320, 20).build());
        return y + 24;
    }

    private void apply() {
        if (Minecraft.getInstance().player == null) { message = "Erst einer Welt beitreten."; return; }
        for (String key : changed) {
            if (!numbers.containsKey(key)) continue;
            try { Integer.parseInt(numbers.get(key).trim()); }
            catch (NumberFormatException e) { message = "Ungueltige Zahl: " + key; return; }
        }
        if (changed.contains("mode")) send("risingsea mode " + mode.toLowerCase(java.util.Locale.ROOT));
        if (changed.contains("processing")) send("risingsea processing " + processing.toLowerCase(java.util.Locale.ROOT));
        for (var entry : numbers.entrySet()) {
            if (changed.contains(entry.getKey())) send("risingsea set " + entry.getKey() + " " + entry.getValue().trim());
        }
        for (var entry : toggles.entrySet()) {
            if (changed.contains(entry.getKey())) send("risingsea set " + entry.getKey() + " " + entry.getValue());
        }
        changed.clear();
        message = "Aenderungen gesendet. OP-Rechte erforderlich.";
    }

    private void send(String command) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.connection.sendCommand(command);
    }

    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
