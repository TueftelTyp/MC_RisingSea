package de.risingsea.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ConfigSnapshotPayload(String json) implements CustomPacketPayload {
    public static final Type<ConfigSnapshotPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("risingsea", "config_snapshot"));
    public static final StreamCodec<FriendlyByteBuf, ConfigSnapshotPayload> CODEC =
        StreamCodec.of((buf, value) -> buf.writeUtf(value.json(), 32767),
                       buf -> new ConfigSnapshotPayload(buf.readUtf(32767)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
