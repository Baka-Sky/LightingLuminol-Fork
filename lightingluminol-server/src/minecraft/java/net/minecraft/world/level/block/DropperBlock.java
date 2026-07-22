package net.minecraft.world.level.block;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public class DropperBlock extends DispenserBlock {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final MapCodec<DropperBlock> CODEC = simpleCodec(DropperBlock::new);
    private static final DispenseItemBehavior DISPENSE_BEHAVIOUR = new DefaultDispenseItemBehavior();

    @Override
    public MapCodec<DropperBlock> codec() {
        return CODEC;
    }

    public DropperBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected DispenseItemBehavior getDispenseMethod(final Level level, final ItemStack itemStack) {
        return DISPENSE_BEHAVIOUR;
    }

    @Override
    public BlockEntity newBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        return new DropperBlockEntity(worldPosition, blockState);
    }

    @Override
    public void dispenseFrom(final ServerLevel level, final BlockState state, final BlockPos pos) {
        DispenserBlockEntity blockEntity = level.getBlockEntity(pos, BlockEntityTypes.DROPPER).orElse(null);
        if (blockEntity == null) {
            LOGGER.warn("Ignoring dispensing attempt for Dropper without matching block entity at {}", pos);
        } else {
            BlockSource source = new BlockSource(level, pos, state, blockEntity);
            int slot = blockEntity.getRandomSlot(level.getRandom());
            if (slot < 0) {
                if (org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFailedDispenseEvent(level, pos)) // Paper - Add BlockFailedDispenseEvent
                level.levelEvent(LevelEvent.SOUND_DISPENSER_FAIL, pos, 0);
            } else {
                ItemStack itemStack = blockEntity.getItem(slot);
                if (!itemStack.isEmpty()) {
                    Direction direction = level.getBlockState(pos).getValue(FACING);
                    Container into = HopperBlockEntity.getContainerAt(level, pos.relative(direction));
                    ItemStack remaining;
                    if (into == null) {
                        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockPreDispenseEvent(level, pos, itemStack, slot)) return; // Paper - Add BlockPreDispenseEvent
                        remaining = DISPENSE_BEHAVIOUR.dispense(source, itemStack);
                    } else {
                        // CraftBukkit start - Fire event when pushing items into other inventories
                        org.bukkit.craftbukkit.inventory.CraftItemStack oitemstack = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack.copyWithCount(1));

                        org.bukkit.inventory.Inventory destinationInventory;
                        // Have to special case large chests as they work oddly
                        if (into instanceof net.minecraft.world.CompoundContainer compoundContainer) {
                            destinationInventory = new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest(compoundContainer);
                        } else {
                            destinationInventory = into.getOwner().getInventory();
                        }

                        org.bukkit.event.inventory.InventoryMoveItemEvent event = new org.bukkit.event.inventory.InventoryMoveItemEvent(blockEntity.getOwner().getInventory(), oitemstack, destinationInventory, true);
                        if (!event.callEvent()) {
                            return;
                        }
                        remaining = HopperBlockEntity.addItem(blockEntity, into, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()), direction.getOpposite());
                        if (event.getItem().equals(oitemstack) && remaining.isEmpty()) {
                            // CraftBukkit end
                            remaining = itemStack.copy();
                            remaining.shrink(1);
                        } else {
                            remaining = itemStack.copy();
                        }
                    }

                    blockEntity.setItem(slot, remaining);
                }
            }
        }
    }
}
