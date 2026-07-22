package net.minecraft.core.dispenser;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LevelEvent;

public class DefaultDispenseItemBehavior implements DispenseItemBehavior {
    private static final int DEFAULT_ACCURACY = 6;
    private Direction direction; // Paper - cache facing direction

    @Override
    public final ItemStack dispense(final BlockSource source, final ItemStack dispensed) {
        this.direction = source.state().getValue(DispenserBlock.FACING); // Paper - cache facing direction
        ItemStack result = this.execute(source, dispensed);
        this.playSound(source);
        this.playAnimation(source, this.direction); // Paper - cache facing direction
        return result;
    }

    protected ItemStack execute(final BlockSource source, final ItemStack dispensed) {
        // Paper - cache facing direction
        Position position = DispenserBlock.getDispensePosition(source);
        ItemStack itemStack = dispensed.split(1);
        // CraftBukkit start
        if (!DefaultDispenseItemBehavior.spawnItem(source.level(), itemStack, 6, this.direction, position, source)) {
            dispensed.grow(1);
        }
        // CraftBukkit end
        return dispensed;
    }

    public static void spawnItem(final Level level, final ItemStack itemStack, final int accuracy, final Direction direction, final Position position) {
        // CraftBukkit start
        ItemEntity itemEntity = prepareItem(level, itemStack, accuracy, direction, position);
        level.addFreshEntity(itemEntity);
    }

    private static ItemEntity prepareItem(final Level level, final ItemStack itemStack, final int accuracy, final Direction direction, final Position position) {
        // CraftBukkit end
        double spawnX = position.x();
        double spawnY = position.y();
        double spawnZ = position.z();
        if (direction.getAxis() == Direction.Axis.Y) {
            spawnY -= 0.125;
        } else {
            spawnY -= 0.15625;
        }

        ItemEntity itemEntity = new ItemEntity(level, spawnX, spawnY, spawnZ, itemStack);
        RandomSource random = level.getRandom();
        double pow = random.nextDouble() * 0.1 + 0.2;
        itemEntity.setDeltaMovement(
            random.triangle(direction.getStepX() * pow, 0.0172275 * accuracy),
            random.triangle(0.2, 0.0172275 * accuracy),
            random.triangle(direction.getStepZ() * pow, 0.0172275 * accuracy)
        );
        return itemEntity; // CraftBukkit
    }

    // CraftBukkit start - void -> boolean return
    public static boolean spawnItem(Level level, ItemStack itemStack, int accuracy, Direction direction, Position position, BlockSource source) {
        if (itemStack.isEmpty()) return true;
        ItemEntity itemEntity = DefaultDispenseItemBehavior.prepareItem(level, itemStack, accuracy, direction, position);

        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
        org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack);

        org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(block, craftItem.clone(), org.bukkit.craftbukkit.util.CraftVector.toBukkit(itemEntity.getDeltaMovement()));
        level.getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return false;
        }

        itemEntity.setItem(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem()));
        itemEntity.setDeltaMovement(org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getVelocity()));

        if (source.state().is(net.minecraft.world.level.block.Blocks.DISPENSER) && !event.getItem().getType().equals(craftItem.getType())) {
            // Chain to handler for new item
            ItemStack eventStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
            DispenseItemBehavior dispenseBehavior = DispenserBlock.getDispenseBehavior(source, eventStack);
            if (dispenseBehavior != DispenseItemBehavior.NOOP && dispenseBehavior.getClass() != DefaultDispenseItemBehavior.class) {
                dispenseBehavior.dispense(source, eventStack);
            } else {
                level.addFreshEntity(itemEntity);
            }
            return false;
        }

        level.addFreshEntity(itemEntity);

        return true;
        // CraftBukkit end
    }

    protected void playSound(final BlockSource source) {
        playDefaultSound(source);
    }

    protected void playAnimation(final BlockSource source, final Direction direction) {
        playDefaultAnimation(source, direction);
    }

    private static void playDefaultSound(final BlockSource source) {
        source.level().levelEvent(LevelEvent.SOUND_DISPENSER_DISPENSE, source.pos(), 0);
    }

    private static void playDefaultAnimation(final BlockSource source, final Direction direction) {
        source.level().levelEvent(LevelEvent.PARTICLES_SHOOT_SMOKE, source.pos(), direction.get3DDataValue());
    }

    protected ItemStack consumeWithRemainder(final BlockSource source, final ItemStack dispensed, final ItemStack remainder) {
        dispensed.shrink(1);
        if (dispensed.isEmpty()) {
            return remainder;
        }

        this.addToInventoryOrDispense(source, remainder);
        return dispensed;
    }

    private void addToInventoryOrDispense(final BlockSource source, final ItemStack itemStack) {
        ItemStack remainder = source.blockEntity().insertItem(itemStack);
        if (!remainder.isEmpty()) {
            Direction direction = source.state().getValue(DispenserBlock.FACING);
            spawnItem(source.level(), remainder, 6, direction, DispenserBlock.getDispensePosition(source));
            playDefaultSound(source);
            playDefaultAnimation(source, direction);
        }
    }
}
