package de.risingsea.client;

import com.google.gson.JsonParser;
import de.risingsea.network.ConfigSnapshotPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class RisingSeaClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ConfigSnapshotPayload.TYPE, (packet, context) -> {
            context.client().execute(() -> {
                if (context.client().gui.screen() instanceof RisingSeaConfigScreen screen) {
                    screen.receiveSnapshot(JsonParser.parseString(packet.json()).getAsJsonObject());
                }
            });
        });
    }
}
