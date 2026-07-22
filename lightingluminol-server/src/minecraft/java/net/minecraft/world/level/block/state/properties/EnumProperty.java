package net.minecraft.world.level.block.state.properties;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.util.StringRepresentable;

public final class EnumProperty<T extends Enum<T> & StringRepresentable> extends Property<T> implements ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccess<T> { // Paper - optimise blockstate property access
    private final List<T> values;
    private final Map<String, T> names;
    private final int[] ordinalToIndex;

    // Paper start - optimise blockstate property access
    private int[] idLookupTable;

    @Override
    public final int moonrise$getIdFor(final T value) {
        final Class<T> target = this.getValueClass();
        return ((value.getClass() != target && value.getDeclaringClass() != target)) ? -1 : this.idLookupTable[value.ordinal()];
    }

    private void init() {
        final java.util.Collection<T> values = this.getPossibleValues();
        final Class<T> clazz = this.getValueClass();

        int id = 0;
        this.idLookupTable = new int[clazz.getEnumConstants().length];
        Arrays.fill(this.idLookupTable, -1);
        final T[] byId = (T[])java.lang.reflect.Array.newInstance(clazz, values.size());

        for (final T value : values) {
            final int valueId = id++;
            this.idLookupTable[value.ordinal()] = valueId;
            byId[valueId] = value;
        }

        this.moonrise$setById(byId);
    }
    // Paper end - optimise blockstate property access

    private EnumProperty(final String name, final Class<T> clazz, final List<T> values) {
        super(name, clazz);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Trying to make empty EnumProperty '" + name + "'");
        }

        this.values = List.copyOf(values);
        T[] allEnumValues = clazz.getEnumConstants();
        this.ordinalToIndex = new int[allEnumValues.length];

        for (T value : allEnumValues) {
            this.ordinalToIndex[value.ordinal()] = values.indexOf(value);
        }

        Builder<String, T> names = ImmutableMap.builder();

        for (T value : values) {
            String key = value.getSerializedName();
            names.put(key, value);
        }

        this.names = names.buildOrThrow();
        this.init(); // Paper - optimise blockstate property access
    }

    @Override
    public List<T> getPossibleValues() {
        return this.values;
    }

    @Override
    public Optional<T> getValue(final String name) {
        return Optional.ofNullable(this.names.get(name));
    }

    @Override
    public String getName(final T value) {
        return value.getSerializedName();
    }

    @Override
    public int getInternalIndex(final T value) {
        return this.ordinalToIndex[value.ordinal()];
    }

    public boolean equals_unused(final Object o) { // Paper - Perf: Optimize hashCode/equals
        return this == o || o instanceof EnumProperty<?> that && super.equals(o) && this.values.equals(that.values);
    }

    @Override
    public int generateHashCode() {
        int result = super.generateHashCode();
        return 31 * result + this.values.hashCode();
    }

    public static <T extends Enum<T> & StringRepresentable> EnumProperty<T> create(final String name, final Class<T> clazz) {
        return create(name, clazz, t -> true);
    }

    public static <T extends Enum<T> & StringRepresentable> EnumProperty<T> create(final String name, final Class<T> clazz, final Predicate<T> filter) {
        return create(name, clazz, Arrays.<T>stream(clazz.getEnumConstants()).filter(filter).collect(Collectors.toList()));
    }

    @SafeVarargs
    public static <T extends Enum<T> & StringRepresentable> EnumProperty<T> create(final String name, final Class<T> clazz, final T... values) {
        return create(name, clazz, List.of(values));
    }

    public static <T extends Enum<T> & StringRepresentable> EnumProperty<T> create(final String name, final Class<T> clazz, final List<T> values) {
        return new EnumProperty<>(name, clazz, values);
    }
}
