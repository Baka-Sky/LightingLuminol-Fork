package net.minecraft.network.protocol.login;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.resources.RegistryOps;

public record ClientboundLoginDisconnectPacket(Component reason) implements Packet<ClientLoginPacketListener> {
    private static final RegistryOps<JsonElement> OPS = RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE);
    // Paper start - localized codec
    // In the login phase, buffer.adventure$locale field is most likely null, but plugins may use internals to set it via the channel attribute
    public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, ClientboundLoginDisconnectPacket> STREAM_CODEC = StreamCodec.composite(
        new StreamCodec<>() {

            private static final net.minecraft.network.codec.StreamCodec<ByteBuf, JsonElement> LENIENT_JSON = ByteBufCodecs.lenientJson(net.minecraft.network.FriendlyByteBuf.MAX_COMPONENT_STRING_LENGTH);
            @Override
            public Component decode(final net.minecraft.network.FriendlyByteBuf buffer) {
                java.util.Locale bufLocale = buffer.adventure$locale;
                return LENIENT_JSON.apply(ByteBufCodecs.fromCodec(OPS, ComponentSerialization.localizedCodec(bufLocale == null ? java.util.Locale.US : bufLocale))).decode(buffer);
            }

            @Override
            public void encode(final net.minecraft.network.FriendlyByteBuf buffer, final Component value) {
                java.util.Locale bufLocale = buffer.adventure$locale;
                LENIENT_JSON.apply(ByteBufCodecs.fromCodec(OPS, ComponentSerialization.localizedCodec(bufLocale == null ? java.util.Locale.US : bufLocale))).encode(buffer, value);
            }
        },
        // Paper end - localized codec
        ClientboundLoginDisconnectPacket::reason,
        ClientboundLoginDisconnectPacket::new
    );

    @Override
    public PacketType<ClientboundLoginDisconnectPacket> type() {
        return LoginPacketTypes.CLIENTBOUND_LOGIN_DISCONNECT;
    }

    @Override
    public void handle(final ClientLoginPacketListener listener) {
        listener.handleDisconnect(this);
    }
}
