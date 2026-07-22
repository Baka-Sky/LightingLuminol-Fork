package net.minecraft.world.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

class HealOrHarmMobEffect extends InstantaneousMobEffect {
    private final boolean isHarm;

    public HealOrHarmMobEffect(final MobEffectCategory category, final int color, final boolean isHarm) {
        super(category, color);
        this.isHarm = isHarm;
    }

    @Override
    public boolean applyEffectTick(final ServerLevel level, final LivingEntity mob, final int amplification) {
        if (this.isHarm == mob.isInvertedHealAndHarm()) {
            mob.heal(Math.max(4 << amplification, 0), org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC); // CraftBukkit
        } else {
            mob.hurtServer(level, mob.damageSources().magic(), 6 << amplification);
        }

        return true;
    }

    @Override
    public void applyInstantaneousEffect(
        final ServerLevel serverLevel,
        final @Nullable Entity source,
        final @Nullable Entity owner,
        final LivingEntity mob,
        final int amplification,
        final double scale
    ) {
        if (!new io.papermc.paper.event.entity.EntityEffectTickEvent(mob.getBukkitLivingEntity(), org.bukkit.craftbukkit.potion.CraftPotionEffectType.minecraftToBukkit(this), amplification).callEvent()) { return; } // Paper - Add EntityEffectTickEvent
        if (this.isHarm == mob.isInvertedHealAndHarm()) {
            int amount = (int)(scale * (4 << amplification) + 0.5);
            mob.heal(amount, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC); // CraftBukkit
        } else {
            int amount = (int)(scale * (6 << amplification) + 0.5);
            if (source == null) {
                mob.hurtServer(serverLevel, mob.damageSources().magic(), amount);
            } else {
                mob.hurtServer(serverLevel, mob.damageSources().indirectMagic(source, owner), amount);
            }
        }
    }
}
