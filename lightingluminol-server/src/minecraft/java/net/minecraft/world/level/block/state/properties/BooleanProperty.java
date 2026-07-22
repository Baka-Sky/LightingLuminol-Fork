package net.minecraft.world.level.block.state.properties;

import java.util.List;
import java.util.Optional;

public final class BooleanProperty extends Property<Boolean> implements ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccess<Boolean> { // Paper - optimise blockstate property access
    private static final List<Boolean> VALUES = List.of(true, false);
    private static final int TRUE_INDEX = 0;
    private static final int FALSE_INDEX = 1;

    // Paper start - optimise blockstate property access
    private static final Boolean[] BY_ID = new Boolean[]{ Boolean.FALSE, Boolean.TRUE };

    @Override
    public final int moonrise$getIdFor(final Boolean value) {
        return value.booleanValue() ? 1 : 0;
    }
    // Paper end - optimise blockstate property access

    private BooleanProperty(final String name) {
        super(name, Boolean.class);
        this.moonrise$setById(BY_ID); // Paper - optimise blockstate property access
    }

    @Override
    public List<Boolean> getPossibleValues() {
        return VALUES;
    }

    public static BooleanProperty create(final String name) {
        return new BooleanProperty(name);
    }

    @Override
    public Optional<Boolean> getValue(final String name) {
        return switch (name) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    @Override
    public String getName(final Boolean value) {
        return value.toString();
    }

    @Override
    public int getInternalIndex(final Boolean value) {
        return value ? 0 : 1;
    }
}
