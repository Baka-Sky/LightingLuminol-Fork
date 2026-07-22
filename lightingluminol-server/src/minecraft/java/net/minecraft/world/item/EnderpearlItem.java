package net.minecraft.world.item;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.Level;

public class EnderpearlItem extends Item {
    public static final float PROJECTILE_SHOOT_POWER = 1.5F;

    public EnderpearlItem(final Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            // CraftBukkit start
            // Paper start - PlayerLaunchProjectileEvent
            final Projectile.Delayed<ThrownEnderpearl> thrownEnderpearl = Projectile.spawnProjectileFromRotationDelayed(ThrownEnderpearl::new, serverLevel, itemStack, player, 0.0F, EnderpearlItem.PROJECTILE_SHOOT_POWER, 1.0F);
            com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) player.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack), (org.bukkit.entity.Projectile) thrownEnderpearl.projectile().getBukkitEntity());
            if (event.callEvent() && thrownEnderpearl.attemptSpawn()) {
                if (event.shouldConsume()) {
                    itemStack.consume(1, player);
                } else {
                    player.containerMenu.forceHeldSlot(hand);
                }

                level.playSound(
                    null,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    SoundEvents.ENDER_PEARL_THROW,
                    SoundSource.NEUTRAL,
                    0.5F,
                    0.4F / (level.getRandom().nextFloat() * 0.4F + 0.8F)
                );
                player.awardStat(Stats.ITEM_USED.get(this));
            } else {
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    serverPlayer.deregisterEnderPearl(thrownEnderpearl.projectile());
                    serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundCooldownPacket(player.getCooldowns().getCooldownGroup(itemStack), 0)); // prevent visual desync of cooldown on the slot
                }
                // Paper end - PlayerLaunchProjectileEvent
                player.containerMenu.forceHeldSlot(hand);
                return InteractionResult.FAIL;
            }
        }
        // CraftBukkit end

        // Paper - PlayerLaunchProjectileEvent - moved up
        return InteractionResult.SUCCESS;
    }
}
