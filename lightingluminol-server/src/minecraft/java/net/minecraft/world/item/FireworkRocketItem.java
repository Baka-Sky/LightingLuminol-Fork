package net.minecraft.world.item;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

public class FireworkRocketItem extends Item implements ProjectileItem {
    public static final byte[] CRAFTABLE_DURATIONS = new byte[]{1, 2, 3};
    public static final double ROCKET_PLACEMENT_OFFSET = 0.15;

    public FireworkRocketItem(final Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player != null && player.isFallFlying()) {
            return InteractionResult.PASS;
        }

        if (level instanceof ServerLevel serverLevel) {
            ItemStack itemStack = context.getItemInHand();
            Vec3 clickLocation = context.getClickLocation();
            Direction direction = context.getClickedFace();
            final Projectile.Delayed<FireworkRocketEntity> fireworkRocketEntity = Projectile.spawnProjectileDelayed( // Paper - PlayerLaunchProjectileEvent
                new FireworkRocketEntity(
                    level,
                    context.getPlayer(),
                    clickLocation.x + direction.getStepX() * 0.15,
                    clickLocation.y + direction.getStepY() * 0.15,
                    clickLocation.z + direction.getStepZ() * 0.15,
                    itemStack
                ),
                serverLevel,
                itemStack, f -> f.spawningEntity = context.getPlayer() == null ? null : context.getPlayer().getUUID() // Paper - firework api - assign spawning entity uuid
            );
            // Paper start - PlayerLaunchProjectileEvent
            com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) context.getPlayer().getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack), (org.bukkit.entity.Firework) fireworkRocketEntity.projectile().getBukkitEntity());
            if (!event.callEvent() || !fireworkRocketEntity.attemptSpawn()) return InteractionResult.PASS;
            if (event.shouldConsume() && !context.getPlayer().hasInfiniteMaterials()) itemStack.shrink(1);
            else context.getPlayer().containerMenu.forceHeldSlot(context.getHand());
            // Paper end - PlayerLaunchProjectileEvent
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
        if (player.isFallFlying()) {
            ItemStack itemStack = player.getItemInHand(hand);
            if (level instanceof ServerLevel serverLevel) {
                // Paper start - PlayerElytraBoostEvent
                final Projectile.Delayed<FireworkRocketEntity> delayed = Projectile.spawnProjectileDelayed(new FireworkRocketEntity(level, itemStack, player), serverLevel, itemStack, f -> f.spawningEntity = player.getUUID()); // Paper - firework api - assign spawning entity uuid
                com.destroystokyo.paper.event.player.PlayerElytraBoostEvent event = new com.destroystokyo.paper.event.player.PlayerElytraBoostEvent((org.bukkit.entity.Player) player.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack), (org.bukkit.entity.Firework) delayed.projectile().getBukkitEntity(), org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand));
                if (event.callEvent() && delayed.attemptSpawn()) {
                    player.awardStat(Stats.ITEM_USED.get(this)); // Moved up from below
                    if (player.dropAllLeashConnections(player, hand)) { // Paper - PlayerUnleashEntityEvent
                        level.playSound(null, player, SoundEvents.LEAD_BREAK, SoundSource.NEUTRAL, 1.0F, 1.0F);
                    }
                    if (event.shouldConsume() && !player.hasInfiniteMaterials()) {
                        itemStack.shrink(1); // Moved up from below
                    } else {
                        player.containerMenu.forceHeldSlot(hand);
                    }
                } else {
                    player.containerMenu.forceHeldSlot(hand);
                }
                // Moved up consume and changed consume to shrink
                // Paper end - PlayerElytraBoostEvent
            }

            return InteractionResult.SUCCESS;
        } else {
            return InteractionResult.PASS;
        }
    }

    @Override
    public Projectile asProjectile(final Level level, final Position position, final ItemStack itemStack, final Direction direction) {
        return new FireworkRocketEntity(level, itemStack.copyWithCount(1), position.x(), position.y(), position.z(), true);
    }

    @Override
    public ProjectileItem.DispenseConfig createDispenseConfig() {
        return ProjectileItem.DispenseConfig.builder()
            .positionFunction(FireworkRocketItem::getEntityJustOutsideOfBlockPos)
            .uncertainty(1.0F)
            .power(0.5F)
            .overrideDispenseEvent(LevelEvent.SOUND_FIREWORK_SHOOT)
            .build();
    }

    private static Vec3 getEntityJustOutsideOfBlockPos(final BlockSource source, final Direction direction) {
        return source.center()
            .add(direction.getStepX() * 0.5000099999997474, direction.getStepY() * 0.5000099999997474, direction.getStepZ() * 0.5000099999997474);
    }
}
