package de.risingsea.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record ConfigRequestPayload(String query) implements CustomPacketPayload {
    public static final Type<ConfigRequestPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("risingsea", "config_request"));
    public static final StreamCodec<FriendlyByteBuf, ConfigRequestPayload> CODEC =
        StreamCodec.of((buf, value) -> buf.writeUtf(value.query(), 32),
                       buf -> new ConfigRequestPayload(buf.readUtf(32)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
