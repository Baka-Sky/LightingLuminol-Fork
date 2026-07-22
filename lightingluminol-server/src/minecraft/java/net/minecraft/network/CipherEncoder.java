package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import javax.crypto.Cipher;

public class CipherEncoder extends io.netty.handler.codec.MessageToMessageEncoder<ByteBuf> { // Paper - Use Velocity cipher; change superclass
    private final com.velocitypowered.natives.encryption.VelocityCipher cipher; // Paper - Use Velocity cipher

    public CipherEncoder(final com.velocitypowered.natives.encryption.VelocityCipher cipher) { // Paper - Use Velocity cipher
        this.cipher = cipher; // Paper - Use Velocity cipher
    }

    // Paper start - Use Velocity cipher
    @Override
    protected void encode(final ChannelHandlerContext ctx, final ByteBuf msg, final java.util.List<Object> out) throws Exception {
        final ByteBuf compatible = com.velocitypowered.natives.util.MoreByteBufUtils.ensureCompatible(ctx.alloc(), this.cipher, msg);
        try {
            this.cipher.process(compatible);
            out.add(compatible);
        } catch (final Exception e) {
            compatible.release(); // compatible will never be used if we throw an exception
            throw e;
        }
        // Paper end - Use Velocity cipher
    }

    // Paper start - Use Velocity cipher
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        this.cipher.close();
    }
    // Paper end - Use Velocity cipher
}
