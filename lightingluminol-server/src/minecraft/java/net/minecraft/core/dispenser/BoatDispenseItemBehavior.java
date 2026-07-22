package net.minecraft.core.dispenser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

public class BoatDispenseItemBehavior extends DefaultDispenseItemBehavior {
    private final DefaultDispenseItemBehavior defaultDispenseItemBehavior = new DefaultDispenseItemBehavior();
    private final EntityType<? extends AbstractBoat> type;

    public BoatDispenseItemBehavior(final EntityType<? extends AbstractBoat> type) {
        this.type = type;
    }

    @Override
    public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
        Direction direction = source.state().getValue(DispenserBlock.FACING);
        ServerLevel level = source.level();
        Vec3 center = source.center();
        double justOutsideDispenser = 0.5625 + this.type.getWidth() / 2.0;
        double spawnX = center.x() + direction.getStepX() * justOutsideDispenser;
        double spawnY = center.y() + direction.getStepY() * 1.125F;
        double spawnZ = center.z() + direction.getStepZ() * justOutsideDispenser;
        BlockPos frontPos = source.pos().relative(direction);
        double yOffset;
        if (level.getFluidState(frontPos).is(FluidTags.WATER)) {
            yOffset = 1.0;
        } else {
            if (!level.getBlockState(frontPos).isAir() || !level.getFluidState(frontPos.below()).is(FluidTags.WATER)) {
                return this.defaultDispenseItemBehavior.dispense(source, dispensed);
            }

            yOffset = 0.0;
        }

        // CraftBukkit start
        ItemStack singleItemStack = dispensed.copyWithCount(1);
        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
        org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(singleItemStack);

        org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(block, craftItem.clone(), new org.bukkit.util.Vector(spawnX, spawnY + yOffset, spawnZ));
        level.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return dispensed;
        }

        boolean shrink = true;
        if (!event.getItem().equals(craftItem)) {
            shrink = false;
            // Chain to handler for new item
            ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
            DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
            if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior != this) {
                dispenseBehavior.dispense(source, eventStack);
                return dispensed;
            }
        }
        // CraftBukkit end
        AbstractBoat boat = this.type.create(level, EntitySpawnReason.DISPENSER);
        if (boat != null) {
            boat.setInitialPos(event.getVelocity().getX(), event.getVelocity().getY(), event.getVelocity().getZ()); // CraftBukkit
            EntityType.<AbstractBoat>createDefaultStackConfig(level, dispensed, null).apply(boat);
            boat.setYRot(direction.toYRot());
            if (level.addFreshEntity(boat) && shrink) dispensed.shrink(1); // Paper - if entity add was successful and supposed to shrink
        }

        return dispensed;
    }

    @Override
    protected void playSound(final BlockSource source) {
        source.level().levelEvent(LevelEvent.SOUND_DISPENSER_DISPENSE, source.pos(), 0);
    }
}
