package net.minecraft.network.protocol;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.codec.StreamMemberEncoder;

public interface Packet<T extends PacketListener> {
    PacketType<? extends Packet<T>> type();

    void handle(T listener);

    // Paper start
    default boolean hasLargePacketFallback() {
        return false;
    }

    /**
     * override {@link #hasLargePacketFallback()} to return true when overriding in subclasses
     */
    default boolean packetTooLarge(net.minecraft.network.Connection manager) {
        return false;
    }
    // Paper end

    default boolean isSkippable() {
        return false;
    }

    default boolean isTerminal() {
        return false;
    }

    static <B extends ByteBuf, T extends Packet<?>> StreamCodec<B, T> codec(final StreamMemberEncoder<B, T> writer, final StreamDecoder<B, T> reader) {
        return StreamCodec.ofMember(writer, reader);
    }

    // Paper start
    /**
     * @param player null if not at PLAY stage yet
     */
    default void onPacketDispatch(net.minecraft.server.level.@org.jspecify.annotations.Nullable ServerPlayer player) {
    }

    /**
     * @param player null if not at PLAY stage yet
     * @param future can be null if packet was cancelled
     */
    default void onPacketDispatchFinish(net.minecraft.server.level.@org.jspecify.annotations.Nullable ServerPlayer player, io.netty.channel.@org.jspecify.annotations.Nullable ChannelFuture future) {
    }

    default boolean hasFinishListener() {
        return false;
    }

    default boolean isReady() {
        return true;
    }

    default java.util.@org.jspecify.annotations.Nullable List<Packet<?>> getExtraPackets() {
        return null;
    }
    // Paper end
}
