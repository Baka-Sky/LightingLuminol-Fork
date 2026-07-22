package net.minecraft.core.dispenser;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;

public class ShearsDispenseItemBehavior extends OptionalDispenseItemBehavior {
    @Override
    protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
        ServerLevel level = source.level();
        // CraftBukkit start
        org.bukkit.block.Block bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
        org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(dispensed); // Paper - ignore stack size on damageable items
        org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(bukkitBlock, craftItem.clone(), new org.bukkit.util.Vector(0, 0, 0));
        level.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            this.setSuccess(false);
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
        if (!level.isClientSide()) {
            BlockPos pos = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
            this.setSuccess(tryShearBeehive(level, dispensed, pos) || tryShearEntity(level, pos, dispensed, bukkitBlock, craftItem)); // CraftBukkit
            if (this.isSuccess()) {
                dispensed.hurtAndBreak(1, level, null, item -> {});
            }
        }

        return dispensed;
    }

    private static boolean tryShearBeehive(final ServerLevel level, final ItemStack tool, final BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.BEEHIVES, s -> s.hasProperty(BeehiveBlock.HONEY_LEVEL) && s.getBlock() instanceof BeehiveBlock)) {
            int honeyLevel = state.getValue(BeehiveBlock.HONEY_LEVEL);
            if (honeyLevel >= 5) {
                level.playSound(null, pos, SoundEvents.BEEHIVE_SHEAR, SoundSource.BLOCKS, 1.0F, 1.0F);
                BeehiveBlock.dropHoneycomb(level, tool, state, level.getBlockEntity(pos), null, pos);
                ((BeehiveBlock)state.getBlock()).releaseBeesAndResetHoneyLevel(level, state, pos, null, BeehiveBlockEntity.BeeReleaseStatus.BEE_RELEASED);
                level.gameEvent(null, GameEvent.SHEAR, pos);
                return true;
            }
        }

        return false;
    }

    private static boolean tryShearEntity(final ServerLevel level, final BlockPos pos, final ItemStack tool, org.bukkit.block.Block bukkitBlock, org.bukkit.craftbukkit.inventory.CraftItemStack craftItem) { // CraftBukkit - add args
        for (Entity entity : level.getEntitiesOfClass(Entity.class, new AABB(pos), EntitySelector.NO_SPECTATORS)) {
            if (entity.shearOffAllLeashConnections(null)) {
                return true;
            }

            if (entity.isAlive() && entity instanceof Shearable shearable && shearable.readyForShearing()) {
                // CraftBukkit start
                // Paper start - Add drops to shear events
                org.bukkit.event.block.BlockShearEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockShearEntityEvent(entity, bukkitBlock, craftItem, shearable.generateDefaultDrops(level, tool));
                if (event.isCancelled()) {
                    // Paper end - Add drops to shear events
                    continue;
                }
                // CraftBukkit end
                shearable.shear(level, SoundSource.BLOCKS, tool, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getDrops())); // Paper - Add drops to shear events
                level.gameEvent(null, GameEvent.SHEAR, pos);
                return true;
            }
        }

        return false;
    }
}
