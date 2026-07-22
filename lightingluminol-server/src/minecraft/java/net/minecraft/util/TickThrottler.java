package net.minecraft.util;

public class TickThrottler {
    private final int incrementStep;
    private final int threshold;
    private final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(); // CraftBukkit - multithreaded field

    public TickThrottler(final int incrementStep, final int threshold) {
        this.incrementStep = incrementStep;
        this.threshold = threshold;
    }

    public void increment() {
        this.count.addAndGet(this.incrementStep); // CraftBukkit - use thread-safe field access instead
    }

    public void tick() {
        // CraftBukkit start
        for (int val; (val = this.count.get()) > 0 && !this.count.compareAndSet(val, val - 1); ) ;
        /* Use thread-safe field access instead
        if (this.count > 0) {
            this.count--;
        }
        */
        // CraftBukkit end
    }

    public boolean isUnderThreshold() {
        // CraftBukkit start - use thread-safe field access instead
        return this.threshold <= 0 || this.count.get() < this.threshold;
    }

    public boolean isIncrementAndUnderThreshold() {
        return this.isIncrementAndUnderThreshold(this.incrementStep, this.threshold);
    }

    public boolean isIncrementAndUnderThreshold(int incrementStep, int threshold) {
        return this.count.addAndGet(incrementStep) < threshold;
        // CraftBukkit end
    }
}
