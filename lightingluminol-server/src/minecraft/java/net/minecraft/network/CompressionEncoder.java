package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.zip.Deflater;

public class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {
    private final byte @org.jspecify.annotations.Nullable [] encodeBuf; // Paper - Use Velocity cipher
    private final @org.jspecify.annotations.Nullable Deflater deflater; // Paper - Use Velocity cipher
    private final com.velocitypowered.natives.compression.@org.jspecify.annotations.Nullable VelocityCompressor compressor; // Paper - Use Velocity cipher
    private int threshold;

    // Paper start - Use Velocity cipher
    public CompressionEncoder(final int threshold) {
        this(null, threshold);
    }
    public CompressionEncoder(final com.velocitypowered.natives.compression.@org.jspecify.annotations.Nullable VelocityCompressor compressor, final int threshold) {
        this.threshold = threshold;
        if (compressor == null) {
            this.encodeBuf = new byte[8192];
            this.deflater = new Deflater();
        } else {
            this.encodeBuf = null;
            this.deflater = null;
        }
        this.compressor = compressor;
        // Paper end - Use Velocity cipher
    }

    @Override
    protected void encode(final ChannelHandlerContext ctx, final ByteBuf uncompressed, final ByteBuf out) throws Exception { // Paper - Use Velocity cipher
        int uncompressedLength = uncompressed.readableBytes();
        if (uncompressedLength > 8388608) {
            throw new IllegalArgumentException("Packet too big (is " + uncompressedLength + ", should be less than 8388608)");
        }

        if (uncompressedLength < this.threshold) {
            VarInt.write(out, 0);
            out.writeBytes(uncompressed);
        } else {
            if (this.deflater != null) { // Paper - Use Velocity cipher
            byte[] input = new byte[uncompressedLength];
            uncompressed.readBytes(input);
            VarInt.write(out, input.length);
            this.deflater.setInput(input, 0, uncompressedLength);
            this.deflater.finish();

            while (!this.deflater.finished()) {
                int written = this.deflater.deflate(this.encodeBuf);
                out.writeBytes(this.encodeBuf, 0, written);
            }

            this.deflater.reset();
            // Paper start - Use Velocity cipher
                return;
            }

            VarInt.write(out, uncompressedLength);
            final ByteBuf compatibleIn = com.velocitypowered.natives.util.MoreByteBufUtils.ensureCompatible(ctx.alloc(), this.compressor, uncompressed);
            try {
                this.compressor.deflate(compatibleIn, out);
            } finally {
                compatibleIn.release();
            }
            // Paper end - Use Velocity cipher
        }
    }

    public int getThreshold() {
        return this.threshold;
    }

    public void setThreshold(final int threshold) {
        this.threshold = threshold;
    }

    // Paper start - Use Velocity cipher
    @Override
    protected ByteBuf allocateBuffer(final ChannelHandlerContext ctx, final ByteBuf msg, final boolean preferDirect) throws Exception {
        if (this.compressor != null) {
            // We allocate bytes to be compressed plus 1 byte. This covers two cases:
            //
            // - Compression
            //    According to https://github.com/ebiggers/libdeflate/blob/master/libdeflate.h#L103,
            //    if the data compresses well (and we do not have some pathological case) then the maximum
            //    size the compressed size will ever be is the input size minus one.
            // - Uncompressed
            //    This is fairly obvious - we will then have one more than the uncompressed size.
            final int initialBufferSize = msg.readableBytes() + 1;
            return com.velocitypowered.natives.util.MoreByteBufUtils.preferredBuffer(ctx.alloc(), this.compressor, initialBufferSize);
        }

        return super.allocateBuffer(ctx, msg, preferDirect);
    }

    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        if (this.compressor != null) {
            this.compressor.close();
        }
    }
    // Paper end - Use Velocity cipher
}
