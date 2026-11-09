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

/** Server-authoritative GUI. Sends ordinary /risingsea commands; requires OP rights. */
@Environment(EnvType.CLIENT)
public final class RisingSeaConfigScreen extends Screen {
    private final Screen parent;
    private final Map<String, EditBox> numeric = new LinkedHashMap<>();
    private final Map<String, Boolean> toggles = new LinkedHashMap<>();
    private String mode = "REALISTIC";
    private final Set<String> changed = new HashSet<>();
    private String error = "";

    public RisingSeaConfigScreen(Screen parent) {
        super(Component.literal("Rising Sea - Serverkonfiguration"));
        this.parent = parent;
        for (String key : new String[] {"enabled", "caves", "countdown", "announce", "pauseempty"}) toggles.put(key, true);
    }

    @Override
    protected void init() {
        numeric.clear();
        int left = this.width / 2 - 155;
        int y = 36;
        addRenderableWidget(Button.builder(Component.literal("Modus: " + mode), b -> {
            mode = mode.equals("REALISTIC") ? "LAYERS" : "REALISTIC";
            changed.add("mode");
            b.setMessage(Component.literal("Modus: " + mode));
        }).bounds(left, y, 310, 20).build());
        y += 27;
        addNumber(left, y, "interval", "300", "Intervall (Sekunden)");
        y += 24;
        addNumber(left, y, "riseblocks", "1", "Anstieg (Bloecke)");
        y += 24;
        addNumber(left, y, "maxlevel", "250", "Maximale Hoehe Y");
        y += 24;
        addNumber(left, y, "budget", "1000", "Bloecke pro Tick");
        y += 26;
        int offset = 0;
        for (String key : toggles.keySet()) {
            final String choice = key;
            addRenderableWidget(Button.builder(toggleLabel(choice), b -> {
                toggles.put(choice, !toggles.get(choice));
                changed.add(choice);
                b.setMessage(toggleLabel(choice));
            }).bounds(left + (offset % 2) * 157, y + (offset / 2) * 23, 153, 20).build());
            offset++;
        }
        y += 3 * 23 + 4;
        addRenderableWidget(Button.builder(Component.literal("Auf Server anwenden"), b -> apply())
            .bounds(left, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Serverwerte abfragen"), b -> {
            send("risingsea config");
            error = "Serverwerte stehen im Chat.";
        }).bounds(left + 160, y, 150, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Schliessen"), b -> onClose())
            .bounds(left, y, 310, 20).build());
    }

    private void addNumber(int x, int y, String key, String initial, String hint) {
        EditBox box = new EditBox(this.font, x + 166, y, 144, 20, Component.literal(hint));
        box.setValue(initial);
        box.setResponder(value -> changed.add(key));
        box.setHint(Component.literal(hint));
        numeric.put(key, box);
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.literal(hint), b -> {})
            .bounds(x, y, 162, 20).build()).active = false;
    }

    private Component toggleLabel(String key) {
        return Component.literal(key + ": " + (toggles.get(key) ? "AN" : "AUS"));
    }

    private void apply() {
        for (var field : numeric.entrySet()) {
            if (!changed.contains(field.getKey())) continue;
            String value = field.getValue().getValue().trim();
            try { Integer.parseInt(value); }
            catch (NumberFormatException e) { error = "Ungueltige Zahl bei " + field.getKey(); return; }
        }
        if (Minecraft.getInstance().player == null) {
            error = "Bitte zuerst einer Welt beitreten.";
            return;
        }
        if (changed.isEmpty()) { error = "Keine Aenderungen ausgewaehlt."; return; }
        if (changed.contains("mode")) send("risingsea mode " + (mode.equals("LAYERS") ? "layers" : "realistic"));
        for (var field : numeric.entrySet()) {
            if (changed.contains(field.getKey())) send("risingsea set " + field.getKey() + " " + field.getValue().getValue().trim());
        }
        for (var option : toggles.entrySet()) {
            if (changed.contains(option.getKey())) send("risingsea set " + option.getKey() + " " + option.getValue());
        }
        changed.clear();
        error = "Aenderungen gesendet. OP-Rechte erforderlich; Chat pruefen.";
    }

    private void send(String command) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.connection.sendCommand(command);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
