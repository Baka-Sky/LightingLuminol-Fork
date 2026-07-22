package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

public record DiscardedPayload(Identifier id, byte[] data) implements CustomPacketPayload { // Paper - store data
    public static <T extends FriendlyByteBuf> StreamCodec<T, DiscardedPayload> codec(final Identifier id, final int maxPayloadSize) {
        return CustomPacketPayload.codec((payload, buf) -> {
            // Paper start
            // Always write data
            buf.writeBytes(payload.data);
        }, buf -> {
            int length = buf.readableBytes();
            if (length >= 0 && length <= maxPayloadSize) {
                final byte[] data = new byte[length];
                buf.readBytes(data);
                return new DiscardedPayload(id, data);
                // Paper end
            } else {
                throw new IllegalArgumentException("Payload may not be larger than " + maxPayloadSize + " bytes");
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<DiscardedPayload> type() {
        return new CustomPacketPayload.Type<>(this.id);
    }
}
