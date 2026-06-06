package bittree.geolock;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncWorldSizePayload(double width) implements CustomPacketPayload {
    public static final Type<SyncWorldSizePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Geolock.MODID, "sync_world_size"));

    public static final StreamCodec<FriendlyByteBuf, SyncWorldSizePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeDouble(payload.width()),
            buf -> new SyncWorldSizePayload(buf.readDouble())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
