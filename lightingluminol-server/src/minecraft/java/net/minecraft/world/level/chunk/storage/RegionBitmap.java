package net.minecraft.world.level.chunk.storage;

import com.google.common.annotations.VisibleForTesting;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.BitSet;

public class RegionBitmap {
    private final BitSet used = new BitSet();

    // Paper start - Attempt to recalculate regionfile header if it is corrupt
    public final void copyFrom(RegionBitmap other) {
        BitSet thisBitset = this.used;
        BitSet otherBitset = other.used;

        for (int i = 0; i < Math.max(thisBitset.size(), otherBitset.size()); ++i) {
            thisBitset.set(i, otherBitset.get(i));
        }
    }

    public final boolean tryAllocate(int from, int length) {
        BitSet bitset = this.used;
        int firstSet = bitset.nextSetBit(from);
        if (firstSet > 0 && firstSet < (from + length)) {
            return false;
        }
        bitset.set(from, from + length);
        return true;
    }
    // Paper end - Attempt to recalculate regionfile header if it is corrupt

    public void force(final int position, final int size) {
        this.used.set(position, position + size);
    }

    public void free(final int position, final int size) {
        this.used.clear(position, position + size);
    }

    public int allocate(final int size) {
        int current = 0;

        while (true) {
            int freeStart = this.used.nextClearBit(current);
            int freeEnd = this.used.nextSetBit(freeStart);
            if (freeEnd == -1 || freeEnd - freeStart >= size) {
                this.force(freeStart, size);
                return freeStart;
            }

            current = freeEnd;
        }
    }

    @VisibleForTesting
    public IntSet getUsed() {
        return this.used.stream().collect(IntArraySet::new, IntCollection::add, IntCollection::addAll);
    }
}
