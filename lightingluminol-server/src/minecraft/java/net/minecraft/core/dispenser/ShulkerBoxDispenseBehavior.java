package net.minecraft.core.dispenser;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.DispenserBlock;
import org.slf4j.Logger;

public class ShulkerBoxDispenseBehavior extends OptionalDispenseItemBehavior {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
        this.setSuccess(false);
        if (dispensed.getItem() instanceof BlockItem blockItem) {
            Direction facing = source.state().getValue(DispenserBlock.FACING);
            BlockPos relativePos = source.pos().relative(facing);
            Direction clickedFace = source.level().isEmptyBlock(relativePos.below()) ? facing : Direction.UP;

            // CraftBukkit start
            org.bukkit.block.Block bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at(source.level(), source.pos());
            org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(dispensed.copyWithCount(1));

            org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(bukkitBlock, craftItem.clone(), org.bukkit.craftbukkit.util.CraftVector.toBukkit(relativePos));
            source.level().getCraftServer().getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return dispensed;
            }

            if (!event.getItem().equals(craftItem)) {
                // Chain to handler for new item
                ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
                DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
                if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                    dispenseBehavior.dispense(source, eventStack);
                    return dispensed;
                }
            }
            // CraftBukkit end
            try {
                // Paper start - track changed items in the dispense event
                this.setSuccess(blockItem.place(new DirectionalPlaceContext(source.level(), relativePos, facing, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()), clickedFace)).consumesAction());
                if (this.isSuccess()) {
                    dispensed.shrink(1); // vanilla shrink is in the place function above, manually handle it here
                }
                // Paper end - track changed items in the dispense event
            } catch (Exception e) {
                LOGGER.error("Error trying to place shulker box at {}", relativePos, e);
            }
        }

        return dispensed;
    }
}
