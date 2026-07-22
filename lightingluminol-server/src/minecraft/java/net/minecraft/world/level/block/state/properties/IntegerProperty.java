package net.minecraft.world.level.block.state.properties;

import it.unimi.dsi.fastutil.ints.IntImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

public final class IntegerProperty extends Property<Integer> implements ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccess<Integer> { // Paper - optimise blockstate property access
    private final IntImmutableList values;
    public final int min;
    public final int max;

    // Paper start - optimise blockstate property access
    @Override
    public final int moonrise$getIdFor(final Integer value) {
        final int val = value.intValue();
        final int ret = val - this.min;

        return ret | ((this.max - ret) >> 31);
    }

    private void init() {
        final int min = this.min;
        final int max = this.max;

        final Integer[] byId = new Integer[max - min + 1];
        for (int i = min; i <= max; ++i) {
            byId[i - min] = Integer.valueOf(i);
        }

        this.moonrise$setById(byId);
    }
    // Paper end - optimise blockstate property access

    private IntegerProperty(final String name, final int min, final int max) {
        super(name, Integer.class);
        if (min < 0) {
            throw new IllegalArgumentException("Min value of " + name + " must be 0 or greater");
        }

        if (max <= min) {
            throw new IllegalArgumentException("Max value of " + name + " must be greater than min (" + min + ")");
        }

        this.min = min;
        this.max = max;
        this.values = IntImmutableList.toList(IntStream.range(min, max + 1));
        this.init(); // Paper - optimise blockstate property access
    }

    @Override
    public List<Integer> getPossibleValues() {
        return this.values;
    }

    public boolean equals_unused(final Object o) { // Paper - Perf: Optimize hashCode/equals
        return this == o || o instanceof IntegerProperty that && super.equals(o) && this.values.equals(that.values);
    }

    @Override
    public int generateHashCode() {
        return 31 * super.generateHashCode() + this.values.hashCode();
    }

    public static IntegerProperty create(final String name, final int min, final int max) {
        return new IntegerProperty(name, min, max);
    }

    @Override
    public Optional<Integer> getValue(final String name) {
        try {
            int value = Integer.parseInt(name);
            return value >= this.min && value <= this.max ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public String getName(final Integer value) {
        return value.toString();
    }

    @Override
    public int getInternalIndex(final Integer value) {
        return value <= this.max ? value - this.min : -1;
    }
}
