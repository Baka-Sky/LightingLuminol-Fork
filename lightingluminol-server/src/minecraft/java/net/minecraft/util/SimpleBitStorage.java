package net.minecraft.util;

import java.util.function.IntConsumer;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;

public class SimpleBitStorage implements BitStorage {
    private static final int[] MAGIC = new int[]{
        -1,
        -1,
        0,
        Integer.MIN_VALUE,
        0,
        0,
        1431655765,
        1431655765,
        0,
        Integer.MIN_VALUE,
        0,
        1,
        858993459,
        858993459,
        0,
        715827882,
        715827882,
        0,
        613566756,
        613566756,
        0,
        Integer.MIN_VALUE,
        0,
        2,
        477218588,
        477218588,
        0,
        429496729,
        429496729,
        0,
        390451572,
        390451572,
        0,
        357913941,
        357913941,
        0,
        330382099,
        330382099,
        0,
        306783378,
        306783378,
        0,
        286331153,
        286331153,
        0,
        Integer.MIN_VALUE,
        0,
        3,
        252645135,
        252645135,
        0,
        238609294,
        238609294,
        0,
        226050910,
        226050910,
        0,
        214748364,
        214748364,
        0,
        204522252,
        204522252,
        0,
        195225786,
        195225786,
        0,
        186737708,
        186737708,
        0,
        178956970,
        178956970,
        0,
        171798691,
        171798691,
        0,
        165191049,
        165191049,
        0,
        159072862,
        159072862,
        0,
        153391689,
        153391689,
        0,
        148102320,
        148102320,
        0,
        143165576,
        143165576,
        0,
        138547332,
        138547332,
        0,
        Integer.MIN_VALUE,
        0,
        4,
        130150524,
        130150524,
        0,
        126322567,
        126322567,
        0,
        122713351,
        122713351,
        0,
        119304647,
        119304647,
        0,
        116080197,
        116080197,
        0,
        113025455,
        113025455,
        0,
        110127366,
        110127366,
        0,
        107374182,
        107374182,
        0,
        104755299,
        104755299,
        0,
        102261126,
        102261126,
        0,
        99882960,
        99882960,
        0,
        97612893,
        97612893,
        0,
        95443717,
        95443717,
        0,
        93368854,
        93368854,
        0,
        91382282,
        91382282,
        0,
        89478485,
        89478485,
        0,
        87652393,
        87652393,
        0,
        85899345,
        85899345,
        0,
        84215045,
        84215045,
        0,
        82595524,
        82595524,
        0,
        81037118,
        81037118,
        0,
        79536431,
        79536431,
        0,
        78090314,
        78090314,
        0,
        76695844,
        76695844,
        0,
        75350303,
        75350303,
        0,
        74051160,
        74051160,
        0,
        72796055,
        72796055,
        0,
        71582788,
        71582788,
        0,
        70409299,
        70409299,
        0,
        69273666,
        69273666,
        0,
        68174084,
        68174084,
        0,
        Integer.MIN_VALUE,
        0,
        5
    };
    private final long[] data;
    private final int bits;
    private final long mask;
    private final int size;
    private final int valuesPerLong;
    private final int divideMul; private final long divideMulUnsigned; // Paper - Perf: Optimize SimpleBitStorage; referenced in b(int) with 2 Integer.toUnsignedLong calls
    private final int divideAdd; private final long divideAddUnsigned; // Paper - Perf: Optimize SimpleBitStorage
    private final int divideShift;

    // Paper start - optimise bitstorage read/write operations
    private static final int[] BETTER_MAGIC = new int[33];
    static {
        // 20 bits of precision
        // since index is always [0, 4095] (i.e 12 bits), multiplication by a magic value here (20 bits)
        // fits exactly in an int and allows us to use integer arithmetic
        for (int bits = 1; bits < BETTER_MAGIC.length; ++bits) {
            BETTER_MAGIC[bits] = (int)ca.spottedleaf.common.util.IntegerUtil.getUnsignedDivisorMagic(64L / bits, 20);
        }
    }
    private final int magic;
    private final int mulBits;
    // Paper end - optimise bitstorage read/write operations

    public SimpleBitStorage(final int bits, final int size, final int[] values) {
        this(bits, size);
        int outputIndex = 0;

        int inputOffset;
        for (inputOffset = 0; inputOffset <= size - this.valuesPerLong; inputOffset += this.valuesPerLong) {
            long packedValue = 0L;

            for (int indexInLong = this.valuesPerLong - 1; indexInLong >= 0; indexInLong--) {
                packedValue <<= bits;
                packedValue |= values[inputOffset + indexInLong] & this.mask;
            }

            this.data[outputIndex++] = packedValue;
        }

        int remainderCount = size - inputOffset;
        if (remainderCount > 0) {
            long lastPackedValue = 0L;

            for (int indexInLong = remainderCount - 1; indexInLong >= 0; indexInLong--) {
                lastPackedValue <<= bits;
                lastPackedValue |= values[inputOffset + indexInLong] & this.mask;
            }

            this.data[outputIndex] = lastPackedValue;
        }
    }

    public SimpleBitStorage(final int bits, final int size) {
        this(bits, size, (long[])null);
    }

    public SimpleBitStorage(final int bits, final int size, final long @Nullable [] data) {
        Validate.inclusiveBetween(1L, 32L, bits);
        this.size = size;
        this.bits = bits;
        this.mask = (1L << bits) - 1L;
        this.valuesPerLong = (char)(64 / bits);
        int row = 3 * (this.valuesPerLong - 1);
        this.divideMul = MAGIC[row + 0]; this.divideMulUnsigned = Integer.toUnsignedLong(this.divideMul); // Paper - Perf: Optimize SimpleBitStorage
        this.divideAdd = MAGIC[row + 1]; this.divideAddUnsigned = Integer.toUnsignedLong(this.divideAdd); // Paper - Perf: Optimize SimpleBitStorage
        this.divideShift = MAGIC[row + 2];
        int requiredLength = (size + this.valuesPerLong - 1) / this.valuesPerLong;
        if (data != null) {
            if (data.length != requiredLength) {
                throw new SimpleBitStorage.InitializationException("Invalid length given for storage, got: " + data.length + " but expected: " + requiredLength);
            }

            this.data = data;
        } else {
            this.data = new long[requiredLength];
        }
        // Paper start - optimise bitstorage read/write operations
        this.magic = BETTER_MAGIC[this.bits];
        this.mulBits = (64 / this.bits) * this.bits;
        if (this.size > 4096) {
            throw new IllegalStateException("Size > 4096 not supported");
        }
        // Paper end - optimise bitstorage read/write operations
    }

    private int cellIndex(final int bitIndex) {
        return (int)(bitIndex * this.divideMulUnsigned + this.divideAddUnsigned >> 32 >> this.divideShift); // Paper - Perf: Optimize SimpleBitStorage
    }

    @Override
    public final int getAndSet(final int index, final int value) { // Paper - Perf: Optimize SimpleBitStorage
        // Paper start - optimise bitstorage read/write operations
        final int full = this.magic * index; // 20 bits of magic + 12 bits of index = barely int
        final int divQ = full >>> 20;
        final int divR = (full & 0xFFFFF) * this.mulBits >>> 20;

        final long[] dataArray = this.data;

        final long data = dataArray[divQ];
        final long mask = this.mask;

        final long write = data & ~(mask << divR) | ((long)value & mask) << divR;

        dataArray[divQ] = write;

        return (int)(data >>> divR & mask);
        // Paper end - optimise bitstorage read/write operations
    }

    @Override
    public final void set(final int index, final int value) { // Paper - Perf: Optimize SimpleBitStorage
        // Paper start - optimise bitstorage read/write operations
        final int full = this.magic * index; // 20 bits of magic + 12 bits of index = barely int
        final int divQ = full >>> 20;
        final int divR = (full & 0xFFFFF) * this.mulBits >>> 20;

        final long[] dataArray = this.data;

        final long data = dataArray[divQ];
        final long mask = this.mask;

        final long write = data & ~(mask << divR) | ((long)value & mask) << divR;

        dataArray[divQ] = write;
        // Paper end - optimise bitstorage read/write operations
    }

    @Override
    public final int get(final int index) { // Paper - Perf: Optimize SimpleBitStorage
        // Paper start - optimise bitstorage read/write operations
        final int full = this.magic * index; // 20 bits of magic + 12 bits of index = barely int
        final int divQ = full >>> 20;
        final int divR = (full & 0xFFFFF) * this.mulBits >>> 20;

        return (int)(this.data[divQ] >>> divR & this.mask);
        // Paper end - optimise bitstorage read/write operations
    }

    @Override
    public long[] getRaw() {
        return this.data;
    }

    @Override
    public int getSize() {
        return this.size;
    }

    @Override
    public int getBits() {
        return this.bits;
    }

    @Override
    public void getAll(final IntConsumer output) {
        int count = 0;

        for (long cellValue : this.data) {
            for (int value = 0; value < this.valuesPerLong; value++) {
                output.accept((int)(cellValue & this.mask));
                cellValue >>= this.bits;
                if (++count >= this.size) {
                    return;
                }
            }
        }
    }

    @Override
    public void unpack(final int[] output) {
        int dataLength = this.data.length;
        int outputOffset = 0;

        for (int i = 0; i < dataLength - 1; i++) {
            long cellValue = this.data[i];

            for (int indexInLong = 0; indexInLong < this.valuesPerLong; indexInLong++) {
                output[outputOffset + indexInLong] = (int)(cellValue & this.mask);
                cellValue >>= this.bits;
            }

            outputOffset += this.valuesPerLong;
        }

        int remainder = this.size - outputOffset;
        if (remainder > 0) {
            long cellValue = this.data[dataLength - 1];

            for (int indexInLong = 0; indexInLong < remainder; indexInLong++) {
                output[outputOffset + indexInLong] = (int)(cellValue & this.mask);
                cellValue >>= this.bits;
            }
        }
    }

    @Override
    public BitStorage copy() {
        return new SimpleBitStorage(this.bits, this.size, (long[])this.data.clone());
    }

    // Paper start - block counting
    @Override
    public final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.ShortArrayList> moonrise$countEntries() {
        final int valuesPerLong = this.valuesPerLong;
        final int bits = this.bits;
        final long mask = (1L << bits) - 1L;
        final int size = this.size;

        if (bits <= 6) {
            final it.unimi.dsi.fastutil.shorts.ShortArrayList[] byId = new it.unimi.dsi.fastutil.shorts.ShortArrayList[1 << bits];
            final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.ShortArrayList> ret = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(1 << bits);

            int index = 0;

            for (long value : this.data) {
                int li = 0;
                do {
                    final int paletteIdx = (int)(value & mask);
                    value >>= bits;
                    ++li;

                    final it.unimi.dsi.fastutil.shorts.ShortArrayList coords = byId[paletteIdx];
                    if (coords != null) {
                        coords.add((short)index++);
                        continue;
                    } else {
                        final it.unimi.dsi.fastutil.shorts.ShortArrayList newCoords = new it.unimi.dsi.fastutil.shorts.ShortArrayList(64);
                        byId[paletteIdx] = newCoords;
                        newCoords.add((short)index++);
                        ret.put(paletteIdx, newCoords);
                        continue;
                    }
                } while (li < valuesPerLong && index < size);
            }

            return ret;
        } else {
            final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.ShortArrayList> ret = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(
                1 << 6
            );

            int index = 0;

            for (long value : this.data) {
                int li = 0;
                do {
                    final int paletteIdx = (int)(value & mask);
                    value >>= bits;
                    ++li;

                    ret.computeIfAbsent(paletteIdx, (final int key) -> {
                        return new it.unimi.dsi.fastutil.shorts.ShortArrayList(64);
                    }).add((short)index++);
                } while (li < valuesPerLong && index < size);
            }

            return ret;
        }
    }
    // Paper end - block counting

    public static class InitializationException extends RuntimeException {
        private InitializationException(final String message) {
            super(message);
        }
    }
}
