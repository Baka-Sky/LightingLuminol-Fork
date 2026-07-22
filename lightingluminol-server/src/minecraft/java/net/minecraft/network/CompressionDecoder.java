package net.minecraft.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class CompressionDecoder extends ByteToMessageDecoder {
    public static final int MAXIMUM_COMPRESSED_LENGTH = 2097152;
    public static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8388608;
    private com.velocitypowered.natives.compression.VelocityCompressor compressor; // Paper - Use Velocity cipher
    private Inflater inflater;
    private int threshold;
    private boolean validateDecompressed;

    // Paper start - Use Velocity cipher
    @io.papermc.paper.annotation.DoNotUse
    public CompressionDecoder(final int threshold, final boolean validateDecompressed) {
        this(null, threshold, validateDecompressed);
    }
    public CompressionDecoder(final com.velocitypowered.natives.compression.VelocityCompressor compressor, final int threshold, final boolean validateDecompressed) {
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
        this.inflater = compressor == null ? new Inflater() : null;
        this.compressor = compressor;
        // Paper end - Use Velocity cipher
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out) throws Exception {
        int uncompressedLength = VarInt.read(in);
        if (uncompressedLength == 0) {
            out.add(in.readBytes(in.readableBytes()));
        } else {
            if (this.validateDecompressed) {
                if (uncompressedLength < this.threshold) {
                    throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is below server threshold of " + this.threshold);
                }

                if (uncompressedLength > 8388608) {
                    throw new DecoderException("Badly compressed packet - size of " + uncompressedLength + " is larger than protocol maximum of 8388608");
                }
            }

            if (inflater != null) { // Paper - Use Velocity cipher; fallback to vanilla inflater
            this.setupInflaterInput(in);
            ByteBuf output = this.inflate(ctx, uncompressedLength);
            this.inflater.reset();
            out.add(output);
            return; // Paper - Use Velocity cipher
            } // Paper - use velocity compression

            // Paper start - Use Velocity cipher
            final ByteBuf compatibleIn = com.velocitypowered.natives.util.MoreByteBufUtils.ensureCompatible(ctx.alloc(), this.compressor, in);
            final ByteBuf uncompressed = com.velocitypowered.natives.util.MoreByteBufUtils.preferredBuffer(ctx.alloc(), this.compressor, uncompressedLength);
            try {
                this.compressor.inflate(compatibleIn, uncompressed, uncompressedLength);
                out.add(uncompressed);
                in.clear();
            } catch (final Exception e) {
                uncompressed.release();
                throw e;
            } finally {
                compatibleIn.release();
            }
            // Paper end - Use Velocity cipher
        }
    }

    // Paper start - Use Velocity cipher
    @Override
    public void handlerRemoved0(final ChannelHandlerContext ctx) {
        if (this.compressor != null) {
            this.compressor.close();
        }
    }
    // Paper end - Use Velocity cipher

    private void setupInflaterInput(final ByteBuf in) {
        ByteBuffer input;
        if (in.nioBufferCount() > 0) {
            input = in.nioBuffer();
            in.skipBytes(in.readableBytes());
        } else {
            input = ByteBuffer.allocateDirect(in.readableBytes());
            in.readBytes(input);
            input.flip();
        }

        this.inflater.setInput(input);
    }

    private ByteBuf inflate(final ChannelHandlerContext ctx, final int uncompressedLength) throws DataFormatException {
        ByteBuf output = ctx.alloc().directBuffer(uncompressedLength);

        try {
            ByteBuffer nioBuffer = output.internalNioBuffer(0, uncompressedLength);
            int pos = nioBuffer.position();
            this.inflater.inflate(nioBuffer);
            int actualUncompressedLength = nioBuffer.position() - pos;
            if (actualUncompressedLength != uncompressedLength) {
                throw new DecoderException(
                    "Badly compressed packet - actual length of uncompressed payload "
                        + actualUncompressedLength
                        + " is does not match declared size "
                        + uncompressedLength
                );
            }

            output.writerIndex(output.writerIndex() + actualUncompressedLength);
            return output;
        } catch (Exception e) {
            output.release();
            throw e;
        }
    }

    // Paper start - Use Velocity cipher
    public void setThreshold(final com.velocitypowered.natives.compression.VelocityCompressor compressor, final int threshold, final boolean validateDecompressed) {
        if (this.compressor == null && compressor != null) { // Only re-configure once. Re-reconfiguring would require closing the native compressor.
            this.compressor = compressor;
            this.inflater = null;
        }
        // Paper end - Use Velocity cipher
        this.threshold = threshold;
        this.validateDecompressed = validateDecompressed;
    }
}
