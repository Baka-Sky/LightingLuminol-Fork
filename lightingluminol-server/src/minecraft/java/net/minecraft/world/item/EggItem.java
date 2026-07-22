package net.minecraft.world.item;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.level.Level;

public class EggItem extends Item implements ProjectileItem {
    public static final float PROJECTILE_SHOOT_POWER = 1.5F;

    public EggItem(final Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        // Paper start
        final Projectile.Delayed<ThrownEgg> thrownEgg = Projectile.spawnProjectileFromRotationDelayed(ThrownEgg::new, (ServerLevel) level, itemStack, player, 0.0F, EggItem.PROJECTILE_SHOOT_POWER, 1.0F);
        com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) player.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack), (org.bukkit.entity.Projectile) thrownEgg.projectile().getBukkitEntity());
        if (event.callEvent() && thrownEgg.attemptSpawn()) {
            if (event.shouldConsume()) {
                itemStack.consume(1, player);
            } else {
                player.containerMenu.forceHeldSlot(hand);
            }
            level.playSound(
                    // Paper end
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.EGG_THROW,
                    SoundSource.PLAYERS,
                    0.5F,
                    0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F)
            );
            // Paper - move up

            player.awardStat(Stats.ITEM_USED.get(this));
            // Paper start
        } else {
            player.containerMenu.forceHeldSlot(hand);
            return InteractionResult.FAIL;
        }
        // CraftBukkit end
        return InteractionResult.SUCCESS;
    }

    @Override
    public Projectile asProjectile(final Level level, final Position position, final ItemStack itemStack, final Direction direction) {
        return new ThrownEgg(level, position.x(), position.y(), position.z(), itemStack);
    }
}
