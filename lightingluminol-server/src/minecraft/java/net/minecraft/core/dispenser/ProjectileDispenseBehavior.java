package net.minecraft.core.dispenser;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.level.block.DispenserBlock;

public class ProjectileDispenseBehavior extends DefaultDispenseItemBehavior {
    private final ProjectileItem projectileItem;
    private final ProjectileItem.DispenseConfig dispenseConfig;

    public ProjectileDispenseBehavior(final Item item) {
        if (item instanceof ProjectileItem projectileItem) {
            this.projectileItem = projectileItem;
            this.dispenseConfig = projectileItem.createDispenseConfig();
        } else {
            throw new IllegalArgumentException(item + " not instance of " + ProjectileItem.class.getSimpleName());
        }
    }

    @Override
    public ItemStack execute(final BlockSource source, final ItemStack dispensed) {
        ServerLevel level = source.level();
        Direction direction = source.state().getValue(DispenserBlock.FACING);
        Position position = this.dispenseConfig.positionFunction().getDispensePosition(source, direction);
        ItemStack singleItemStack = dispensed.copyWithCount(1); // Paper - shrink below and single item in event
        org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(level, source.pos());
        org.bukkit.craftbukkit.inventory.CraftItemStack craftItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(singleItemStack);

        org.bukkit.event.block.BlockDispenseEvent event = new org.bukkit.event.block.BlockDispenseEvent(block, craftItem.clone(), new org.bukkit.util.Vector(direction.getStepX(), direction.getStepY(), direction.getStepZ()));
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

        // SPIGOT-7923: Avoid create projectiles with empty item
        if (!singleItemStack.isEmpty()) {
            Projectile projectile = Projectile.spawnProjectileUsingShoot(this.projectileItem.asProjectile(level, position, org.bukkit.craftbukkit.inventory.CraftItemStack.unwrap(event.getItem()), direction), level, singleItemStack, event.getVelocity().getX(), event.getVelocity().getY(), event.getVelocity().getZ(), this.dispenseConfig.power(), this.dispenseConfig.uncertainty()); // Paper - track changed items in the dispense event; unwrap is safe here because all uses of the stack make their own copies
            projectile.projectileSource = new org.bukkit.craftbukkit.projectiles.CraftBlockProjectileSource(source.blockEntity());
        }
        if (shrink) dispensed.shrink(1);
        // CraftBukkit end
        return dispensed;
    }

    @Override
    protected void playSound(final BlockSource source) {
        source.level().levelEvent(this.dispenseConfig.overrideDispenseEvent().orElse(1002), source.pos(), 0);
    }
}
