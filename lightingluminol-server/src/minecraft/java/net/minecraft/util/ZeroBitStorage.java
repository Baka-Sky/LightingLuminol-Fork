package net.minecraft.util;

import java.util.Arrays;
import java.util.function.IntConsumer;
import org.apache.commons.lang3.Validate;

public class ZeroBitStorage implements BitStorage {
    public static final long[] RAW = new long[0];
    private final int size;

    public ZeroBitStorage(final int size) {
        this.size = size;
    }

    @Override
    public final int getAndSet(final int index, final int value) { // Paper - Perf: Optimize SimpleBitStorage
        //Validate.inclusiveBetween(0L, this.size - 1, index); // Paper - Perf: Optimize SimpleBitStorage
        //Validate.inclusiveBetween(0L, 0L, value); // Paper - Perf: Optimize SimpleBitStorage
        return 0;
    }

    @Override
    public final void set(final int index, final int value) { // Paper - Perf: Optimize SimpleBitStorage
        //Validate.inclusiveBetween(0L, this.size - 1, index); // Paper - Perf: Optimize SimpleBitStorage
        //Validate.inclusiveBetween(0L, 0L, value); // Paper - Perf: Optimize SimpleBitStorage
    }

    @Override
    public final int get(final int index) { // Paper - Perf: Optimize SimpleBitStorage
        //Validate.inclusiveBetween(0L, this.size - 1, index); // Paper - Perf: Optimize SimpleBitStorage
        return 0;
    }

    @Override
    public long[] getRaw() {
        return RAW;
    }

    @Override
    public int getSize() {
        return this.size;
    }

    @Override
    public int getBits() {
        return 0;
    }

    @Override
    public void getAll(final IntConsumer output) {
        for (int i = 0; i < this.size; i++) {
            output.accept(0);
        }
    }

    @Override
    public void unpack(final int[] output) {
        Arrays.fill(output, 0, this.size, 0);
    }

    @Override
    public BitStorage copy() {
        return this;
    }

    // Paper start - block counting
    @Override
    public final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.ShortArrayList> moonrise$countEntries() {
        final int size = this.size;

        final short[] raw = new short[size];
        for (int i = 0; i < size; ++i) {
            raw[i] = (short)i;
        }

        final it.unimi.dsi.fastutil.shorts.ShortArrayList coordinates = it.unimi.dsi.fastutil.shorts.ShortArrayList.wrap(raw, size);

        final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.ShortArrayList> ret = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(1);
        ret.put(0, coordinates);
        return ret;
    }
    // Paper end - block counting
}
