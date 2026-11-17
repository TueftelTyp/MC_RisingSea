package de.risingsea.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.risingsea.network.ConfigSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;

/**
 * A compact countdown card styled after the vanilla status-effect list.
 * It appears only during the configured warning window before the next lowering step.
 */
public final class RisingSeaClient implements ClientModInitializer {
    private static final int WIDTH = 120;
    private static final int HEIGHT = 32;
    private static final int ICON_X_OFFSET = 7;
    private static final int ICON_Y_OFFSET = 7;
    private static final long MAX_SNAPSHOT_AGE_MS = 4_000L;

    private static boolean countdownEnabled;
    private static int warningSeconds;
    private static long remainingSeconds;
    private static long snapshotTimeMs;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ConfigSnapshotPayload.TYPE, (packet, context) ->
            context.client().execute(() -> {
                JsonObject json;
                try {
                    json = JsonParser.parseString(packet.json()).getAsJsonObject();
                    boolean show = json.has("showCountdown") && json.get("showCountdown").getAsBoolean();
                    boolean enabled = json.has("enableFlooding") && json.get("enableFlooding").getAsBoolean();
                    boolean running = json.has("running") && json.get("running").getAsBoolean();
                    boolean scanReady = !json.has("scanReady") || json.get("scanReady").getAsBoolean();
                    int level = json.has("currentWaterLevel") ? json.get("currentWaterLevel").getAsInt() : 0;
                    int maximum = json.has("maxWaterLevel") ? json.get("maxWaterLevel").getAsInt() : level;
                    int window = json.has("countdownLastSeconds") ? json.get("countdownLastSeconds").getAsInt() : 10;
                    long seconds = json.has("nextRiseSeconds") ? json.get("nextRiseSeconds").getAsLong() : Long.MAX_VALUE;
                    warningSeconds = Math.max(0, Math.min(60, window));
                    remainingSeconds = seconds;
                    countdownEnabled = show && enabled && running && scanReady && level < maximum
                        && warningSeconds > 0 && seconds > 0
                        && (!json.has("automaticRiseScheduled") || json.get("automaticRiseScheduled").getAsBoolean());
                    snapshotTimeMs = System.currentTimeMillis();
                } catch (RuntimeException exception) {
                    countdownEnabled = false;
                    snapshotTimeMs = 0;
                    return;
                }
                if (context.client().gui.screen() instanceof RisingSeaConfigScreen screen) {
                    screen.receiveSnapshot(json);
                }
            })
        );

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("risingsea", "countdown"), (graphics, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.gui.hud.isHidden() || !countdownEnabled) return;
            long age = System.currentTimeMillis() - snapshotTimeMs;
            if (snapshotTimeMs == 0 || age < 0 || age > MAX_SNAPSHOT_AGE_MS) return;

            long left = Math.max(0, remainingSeconds - age / 1_000L);
            if (left == 0 || left > warningSeconds) return;

            int x = Math.max(2, graphics.guiWidth() - WIDTH - 2);
            int y = 2;
            drawVanillaEffectCard(graphics, x, y);
            drawEffectIcon(graphics, x + ICON_X_OFFSET, y + ICON_Y_OFFSET);

            graphics.text(mc.font, Component.translatable("risingsea.effect.rising").getString(), x + 31, y + 6, 0xFFFFFFFF, false);
            long minutes = left / 60;
            long seconds = left % 60;
            String time = String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
            graphics.text(mc.font, time, x + 31, y + 17, 0xFF7F7F7F, false);
        });
    }


private static void drawVanillaEffectCard(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y) {
    // More faithful vanilla-style effect tile: subtle bevel, clipped corners, no harsh white frame.
    int outerDark = 0xFF111111;
    int panel = 0xFF1F1F1F;
    int highlight = 0xFFA0A0A0;
    int light = 0xFF6F6F6F;
    int shadow = 0xFF2A2A2A;
    int deepShadow = 0xFF171717;

    // Main body without fully square corner pixels.
    graphics.fill(x + 1, y, x + WIDTH - 1, y + HEIGHT, outerDark);
    graphics.fill(x, y + 1, x + WIDTH, y + HEIGHT - 1, outerDark);
    graphics.fill(x + 2, y + 2, x + WIDTH - 2, y + HEIGHT - 2, panel);

    // Top/left bevel — gray, not white.
    graphics.fill(x + 2, y + 1, x + WIDTH - 2, y + 2, highlight);
    graphics.fill(x + 1, y + 2, x + 2, y + HEIGHT - 2, highlight);
    graphics.fill(x + 2, y + 2, x + WIDTH - 3, y + 3, light);
    graphics.fill(x + 2, y + 2, x + 3, y + HEIGHT - 3, light);

    // Bottom/right bevel.
    graphics.fill(x + 2, y + HEIGHT - 2, x + WIDTH - 2, y + HEIGHT - 1, shadow);
    graphics.fill(x + WIDTH - 2, y + 2, x + WIDTH - 1, y + HEIGHT - 2, shadow);
    graphics.fill(x + 3, y + HEIGHT - 3, x + WIDTH - 2, y + HEIGHT - 2, deepShadow);
    graphics.fill(x + WIDTH - 3, y + 3, x + WIDTH - 2, y + HEIGHT - 2, deepShadow);

    // Explicit clipped-corner look.
    graphics.fill(x, y, x + 1, y + 1, 0x00000000);
    graphics.fill(x + WIDTH - 1, y, x + WIDTH, y + 1, 0x00000000);
    graphics.fill(x, y + HEIGHT - 1, x + 1, y + HEIGHT, 0x00000000);
    graphics.fill(x + WIDTH - 1, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, 0x00000000);
}

private static void drawEffectIcon(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y) {
    int cyan = 0xFF56E6FF;
    int cyanDark = 0xFF2CA6D2;

    // Upward arrow (opposite of Falling Sea's descending arrow).
    graphics.fill(x + 8, y + 4, x + 10, y + 13, cyan);
    graphics.fill(x + 7, y + 5, x + 11, y + 7, cyan);
    graphics.fill(x + 5, y + 7, x + 13, y + 9, cyan);
    graphics.fill(x + 8, y + 2, x + 10, y + 4, cyan);

    // Two compact wave rows, slightly tidier and closer to a status glyph.
    graphics.fill(x + 3, y + 14, x + 6, y + 15, cyanDark);
    graphics.fill(x + 6, y + 15, x + 9, y + 16, cyan);
    graphics.fill(x + 9, y + 14, x + 12, y + 15, cyanDark);
    graphics.fill(x + 12, y + 15, x + 15, y + 16, cyan);

    graphics.fill(x + 3, y + 16, x + 6, y + 17, cyan);
    graphics.fill(x + 6, y + 17, x + 9, y + 18, cyanDark);
    graphics.fill(x + 9, y + 16, x + 12, y + 17, cyan);
    graphics.fill(x + 12, y + 17, x + 15, y + 18, cyanDark);
}
}
