package net.minecraft.world.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

class SaturationMobEffect extends InstantaneousMobEffect {
    protected SaturationMobEffect(final MobEffectCategory category, final int color) {
        super(category, color);
    }

    @Override
    public boolean applyEffectTick(final ServerLevel level, final LivingEntity mob, final int amplification) {
        if (mob instanceof Player player) {
            // CraftBukkit start
            int oldFoodLevel = player.getFoodData().getFoodLevel();
            org.bukkit.event.entity.FoodLevelChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callFoodLevelChangeEvent(player, amplification + 1 + oldFoodLevel);
            if (!event.isCancelled()) {
                player.getFoodData().eat(event.getFoodLevel() - oldFoodLevel, 1.0F);
            }

            ((org.bukkit.craftbukkit.entity.CraftPlayer) player.getBukkitEntity()).sendHealthUpdate();
            // CraftBukkit end
        }

        return true;
    }
}
