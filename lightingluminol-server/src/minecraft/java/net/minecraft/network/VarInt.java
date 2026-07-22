package net.minecraft.network;

import io.netty.buffer.ByteBuf;

public class VarInt {
    public static final int MAX_VARINT_SIZE = 5;
    private static final int DATA_BITS_MASK = 127;
    private static final int CONTINUATION_BIT_MASK = 128;
    private static final int DATA_BITS_PER_BYTE = 7;

    public static int getByteSize(final int value) {
        // Paper start - Optimize VarInts
        return VARINT_EXACT_BYTE_LENGTHS[Integer.numberOfLeadingZeros(value)];
    }
    private static final int[] VARINT_EXACT_BYTE_LENGTHS = new int[33];
    static {
        for (int i = 0; i <= 32; ++i) {
            VARINT_EXACT_BYTE_LENGTHS[i] = (int) Math.ceil((31.0 - (i - 1)) / 7.0);
        }
        VARINT_EXACT_BYTE_LENGTHS[32] = 1; // Special case for the number 0.
    }
    public static int getByteSizeSlow(final int value) {
        // Paper end - Optimize VarInts
        for (int i = 1; i < MAX_VARINT_SIZE; i++) {
            if ((value & -1 << i * DATA_BITS_PER_BYTE) == 0) {
                return i;
            }
        }

        return MAX_VARINT_SIZE;
    }

    public static boolean hasContinuationBit(final byte in) {
        return (in & CONTINUATION_BIT_MASK) == CONTINUATION_BIT_MASK;
    }

    public static int read(final ByteBuf input) {
        int out = 0;
        int bytes = 0;

        byte in;
        do {
            in = input.readByte();
            out |= (in & (byte)DATA_BITS_MASK) << bytes++ * DATA_BITS_PER_BYTE;
            if (bytes > MAX_VARINT_SIZE) {
                throw new RuntimeException("VarInt too big");
            }
        } while (hasContinuationBit(in));

        return out;
    }

    public static ByteBuf write(final ByteBuf output, int value) {
        // Paper start - Optimize VarInts
        // Peel the one and two byte count cases explicitly as they are the most common VarInt sizes
        // that the proxy will write, to improve inlining.
        if ((value & (0xFFFFFFFF << 7)) == 0) {
            output.writeByte(value);
        } else if ((value & (0xFFFFFFFF << 14)) == 0) {
            int s = (value & 0x7F | 0x80) << 8 | (value >>> 7);
            output.writeShort(s);
        } else {
            writeSlow(output, value);
        }
        return output;
    }
    public static ByteBuf writeSlow(final ByteBuf output, int value) {
        // Paper end - Optimize VarInts
        while ((value & -128) != 0) {
            output.writeByte(value & DATA_BITS_MASK | CONTINUATION_BIT_MASK);
            value >>>= DATA_BITS_PER_BYTE;
        }

        output.writeByte(value);
        return output;
    }
}
