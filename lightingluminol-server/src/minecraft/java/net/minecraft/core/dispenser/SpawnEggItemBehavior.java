package net.minecraft.core.dispenser;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.gameevent.GameEvent;

public class SpawnEggItemBehavior extends DefaultDispenseItemBehavior {
    public static final SpawnEggItemBehavior INSTANCE = new SpawnEggItemBehavior();

    @Override
    public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
        Direction direction = source.state().getValue(DispenserBlock.FACING);
        EntityType<?> type = SpawnEggItem.getType(dispensed);
        if (type == null) {
            return dispensed;
        }

        // Paper start - block dispense event
        ItemStack singleDispensed = dispensed.copyWithCount(1);
        final org.bukkit.craftbukkit.inventory.CraftItemStack eventItemCopy = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(singleDispensed);
        final org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(
            org.bukkit.craftbukkit.block.CraftBlock.at(source.level(), source.pos()),
            eventItemCopy,
            new org.bukkit.util.Vector(0, 0, 0)
        );
        if (!event.callEvent()) return dispensed;

        final boolean shrink = event.getItem().equals(eventItemCopy);
        if (!shrink) {
            final ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(event.getItem());
            final DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
            if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                dispenseBehavior.dispense(source, eventStack);
                return dispensed;
            }

            type = SpawnEggItem.getType(eventStack);
            singleDispensed = eventStack;
        }
        // Paper end - block dispense event
        try {
            Entity spawned = type.spawn(
                source.level(), singleDispensed, null, source.pos().relative(direction), EntitySpawnReason.DISPENSER, direction != Direction.UP, false // Paper - block dispense event - update used item stack
            );
            if (spawned == null) {
                return dispensed;
            }
        } catch (Exception e) {
            LOGGER.error("Error while dispensing spawn egg from dispenser at {}", source.pos(), e);
            return ItemStack.EMPTY;
        }

        if (shrink) dispensed.shrink(1); // Paper - block dispense event - only shrink if above logic requires it.
        source.level().gameEvent(null, GameEvent.ENTITY_PLACE, source.pos());
        return dispensed;
    }
}
