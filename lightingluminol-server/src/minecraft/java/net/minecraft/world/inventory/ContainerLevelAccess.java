package net.minecraft.world.inventory;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public interface ContainerLevelAccess {
    ContainerLevelAccess NULL = new ContainerLevelAccess() {
        @Override
        public <T> Optional<T> evaluate(final BiFunction<Level, BlockPos, T> action) {
            return Optional.empty();
        }
        // Paper start - fix menus with empty level accesses
        @Override
        public org.bukkit.Location getLocation() {
            return null;
        }
        // Paper end - fix menus with empty level accesses
    };

    static ContainerLevelAccess create(final Level level, final BlockPos pos) {
        return new ContainerLevelAccess() {
            @Override
            public <T> Optional<T> evaluate(final BiFunction<Level, BlockPos, T> action) {
                return Optional.of(action.apply(level, pos));
            }
            // CraftBukkit start
            @Override
            public Level getWorld() {
                return level;
            }

            @Override
            public BlockPos getPosition() {
                return pos;
            }
            // CraftBukkit end
            // Paper start - Add missing InventoryHolders
            @Override
            public boolean isBlock() {
                return true;
            }
            // Paper end - Add missing InventoryHolders
        };
    }

    <T> Optional<T> evaluate(BiFunction<Level, BlockPos, T> action);

    default <T> T evaluate(final BiFunction<Level, BlockPos, T> action, final T defaultValue) {
        return this.evaluate(action).orElse(defaultValue);
    }

    default void execute(final BiConsumer<Level, BlockPos> action) {
        this.evaluate((level, pos) -> {
            action.accept(level, pos);
            return Optional.empty();
        });
    }
    // CraftBukkit start
    default Level getWorld() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    default BlockPos getPosition() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    default org.bukkit.Location getLocation() {
        return org.bukkit.craftbukkit.util.CraftLocation.toBukkit(this.getPosition(), this.getWorld());
    }
    // CraftBukkit end
    // Paper start - Add missing InventoryHolders
    default boolean isBlock() {
        return false;
    }

    default org.bukkit.inventory.@org.jspecify.annotations.Nullable BlockInventoryHolder createBlockHolder(AbstractContainerMenu menu) {
        if (!this.isBlock()) {
            return null;
        }
        return new org.bukkit.craftbukkit.inventory.CraftBlockInventoryHolder(this, menu.getBukkitView().getTopInventory());
    }
    // Paper end - Add missing InventoryHolders
}
