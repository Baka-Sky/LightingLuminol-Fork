package net.minecraft.network;

import io.netty.buffer.ByteBuf;

public class VarLong {
    private static final int MAX_VARLONG_SIZE = 10;
    private static final int DATA_BITS_MASK = 127;
    private static final int CONTINUATION_BIT_MASK = 128;
    private static final int DATA_BITS_PER_BYTE = 7;

    public static int getByteSize(final long value) {
        for (int i = 1; i < MAX_VARLONG_SIZE; i++) {
            if ((value & -1L << i * DATA_BITS_PER_BYTE) == 0L) {
                return i;
            }
        }

        return MAX_VARLONG_SIZE;
    }

    public static boolean hasContinuationBit(final byte in) {
        return (in & CONTINUATION_BIT_MASK) == CONTINUATION_BIT_MASK;
    }

    public static long read(final ByteBuf input) {
        long out = 0L;
        int bytes = 0;

        byte in;
        do {
            in = input.readByte();
            out |= (long)(in & (byte)DATA_BITS_MASK) << bytes++ * DATA_BITS_PER_BYTE;
            if (bytes > MAX_VARLONG_SIZE) {
                throw new RuntimeException("VarLong too big");
            }
        } while (hasContinuationBit(in));

        return out;
    }

    public static ByteBuf write(final ByteBuf output, long value) {
        while ((value & -128L) != 0L) {
            output.writeByte((int)(value & DATA_BITS_MASK) | CONTINUATION_BIT_MASK);
            value >>>= DATA_BITS_PER_BYTE;
        }

        output.writeByte((int)value);
        return output;
    }
}
