package net.minecraft.world.level.block.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

public abstract class StateHolder<O, S> implements ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccessStateHolder<O, S> { // Paper - optimise blockstate property access
    private static final int VALUE_NOT_FOUND = -1;
    public static final String NAME_TAG = "Name";
    public static final String PROPERTIES_TAG = "Properties";
    protected final O owner;
    private Property<?>[] propertyKeys; // Paper - optimise blockstate property access - remove final
    private Comparable<?>[] propertyValues; // Paper - optimise blockstate property access - remove final
    private S[][] neighbors;

    // Paper start - optimise blockstate property access
    protected ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.util.ZeroCollidingReferenceStateTable<O, S> optimisedTable;
    protected final long tableIndex;

    @Override
    public final long moonrise$getTableIndex() {
        return this.tableIndex;
    }

    @Override
    public final void moonrise$init(final Collection<S> states) {
        this.optimisedTable.loadInTable(states);

        // de-duplicate the tables and remove values, properties, neighbours arrays
        for (final S neighbour : states) {
            ((StateHolder<O, S>)(Object)(StateHolder<O, S>)neighbour).optimisedTable = this.optimisedTable;
            ((StateHolder<O, S>)(Object)(StateHolder<O, S>)neighbour).propertyKeys = null;
            ((StateHolder<O, S>)(Object)(StateHolder<O, S>)neighbour).propertyValues = null;
            ((StateHolder<O, S>)(Object)(StateHolder<O, S>)neighbour).neighbors = null;
        }
    }
    // Paper end - optimise blockstate property access

    protected StateHolder(final O owner, final Property<?>[] propertyKeys, final Comparable<?>[] propertyValues) {
        assert propertyKeys.length == propertyValues.length;
        this.owner = owner;
        this.propertyKeys = propertyKeys;
        this.propertyValues = propertyValues;

        // Paper start - optimise blockstate property access
        this.optimisedTable = new ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.util.ZeroCollidingReferenceStateTable<>(propertyKeys);
        this.tableIndex = this.optimisedTable.getIndex((StateHolder<O, S>)(Object)this, propertyKeys, propertyValues);
        // Paper end - optimise blockstate property access
    }

    public <T extends Comparable<T>> S cycle(final Property<T> property) {
        return this.setValue(property, findNextInCollection(property.getPossibleValues(), this.getValue(property)));
    }

    protected static <T> T findNextInCollection(final List<T> values, final T current) {
        int nextIndex = values.indexOf(current) + 1;
        return nextIndex == values.size() ? values.getFirst() : values.get(nextIndex);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append(this.owner);
        if (!this.isSingletonState()) {
            builder.append('[');
            builder.append(this.getValues().map(Property.Value::toString).collect(Collectors.joining(",")));
            builder.append(']');
        }

        return builder.toString();
    }

    @Override
    public final boolean equals(final Object obj) {
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    public Collection<Property<?>> getProperties() {
        return this.optimisedTable.getProperties(); // Paper - optimise blockstate property access
    }

    private int valueIndex(final Property<?> property) {
        for (int i = 0; i < this.propertyKeys.length; i++) {
            if (this.propertyKeys[i] == property) {
                return i;
            }
        }

        return -1;
    }

    public boolean hasProperty(final Property<?> property) {
        return property != null && this.optimisedTable.hasProperty(property); // Paper - optimise blockstate property access
    }

    private <T extends Comparable<T>> @Nullable T getNullableValue(final Property<T> property) {
        return property == null ? null : this.optimisedTable.get(this.tableIndex, property); // Paper - optimise blockstate property access
    }

    public <T extends Comparable<T>> T getValue(final Property<T> property) {
        // Paper start - optimise blockstate property access
        final T ret = this.optimisedTable.get(this.tableIndex, property);
        if (ret != null) {
            return ret;
        }
        throw new IllegalArgumentException("Cannot get property " + property + " as it does not exist in " + this.owner);
        // Paper end - optimise blockstate property access
    }

    public <T extends Comparable<T>> Optional<T> getOptionalValue(final Property<T> property) {
        return Optional.ofNullable(this.getNullableValue(property));
    }

    public <T extends Comparable<T>> T getValueOrElse(final Property<T> property, final T defaultValue) {
        return Objects.requireNonNullElse(this.getNullableValue(property), defaultValue);
    }

    public <T extends Comparable<T>, V extends T> S setValue(final Property<T> property, final V value) {
        // Paper start - optimise blockstate property access
        final S ret = this.optimisedTable.set(this.tableIndex, property, value);
        if (ret != null) {
            return ret;
        }
        throw new IllegalArgumentException("Cannot set property " + property + " to " + value + " on " + this.owner);
        // Paper end - optimise blockstate property access
    }

    public <T extends Comparable<T>, V extends T> S trySetValue(final Property<T> property, final V value) {
        // Paper start - optimise blockstate property access
        if (property == null) {
            return (S)(StateHolder<O, S>)(Object)this;
        }
        final S ret = this.optimisedTable.trySet(this.tableIndex, property, value, (S)(StateHolder<O, S>)(Object)this);
        if (ret != null) {
            return ret;
        }
        throw new IllegalArgumentException("Cannot set property " + property + " to " + value + " on " + this.owner);
        // Paper end - optimise blockstate property access
    }

    private <T extends Comparable<T>, V extends T> S setValueInternal(final Property<T> property, final int propertyIndex, final V value) {
        int valueIndex = property.getInternalIndex((T)value);
        if (valueIndex < 0) {
            throw new IllegalArgumentException("Cannot set property " + property + " to " + value + " on " + this.owner + ", it is not an allowed value");
        } else {
            return this.neighbors[propertyIndex][valueIndex];
        }
    }

    void initializeNeighbors(final S[][] neighbors) {
        if (this.neighbors != null) {
            throw new IllegalStateException();
        }

        this.neighbors = neighbors;
    }

    public boolean isSingletonState() {
        return this.optimisedTable.isSingletonState(); // Paper - optimise blockstate property access
    }

    public Stream<Property.Value<?>> getValues() {
        // Paper start - optimise blockstate property access
        return this.optimisedTable.getProperties().stream().map((final Property<?> prop) -> {
            return createValue(prop, StateHolder.this.getValue(prop));
        });
        // Paper end - optimise blockstate property access
    }

    public static <T extends Comparable<T>> Property.Value<T> createValue(final Property<T> propertyKey, final Comparable<?> propertyValue) { // Paper - public
        return new Property.Value<>(propertyKey, (T)propertyValue);
    }

    protected static <O, S extends StateHolder<O, S>> Codec<S> codec(
        final Codec<O> ownerCodec, final Function<O, S> defaultState, final Function<O, StateDefinition<O, S>> stateDefinition
    ) {
        return ownerCodec.dispatch(
            "Name",
            s -> s.owner,
            o -> {
                StateDefinition<O, S> definition = stateDefinition.apply((O)o);
                S defaultValue = defaultState.apply((O)o);
                return definition.isSingletonState()
                    ? MapCodec.unit(defaultValue)
                    : definition.propertiesCodec().codec().lenientOptionalFieldOf("Properties").xmap(oo -> oo.orElse(defaultValue), Optional::of);
            }
        );
    }
}
