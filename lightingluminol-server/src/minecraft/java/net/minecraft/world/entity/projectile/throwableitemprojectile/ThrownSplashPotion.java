package net.minecraft.world.entity.projectile.throwableitemprojectile;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

public class ThrownSplashPotion extends AbstractThrownPotion {
    public ThrownSplashPotion(final EntityType<? extends ThrownSplashPotion> type, final Level level) {
        super(type, level);
    }

    public ThrownSplashPotion(final Level level, final LivingEntity owner, final ItemStack itemStack) {
        super(EntityTypes.SPLASH_POTION, level, owner, itemStack);
    }

    public ThrownSplashPotion(final Level level, final double x, final double y, final double z, final ItemStack itemStack) {
        super(EntityTypes.SPLASH_POTION, level, x, y, z, itemStack);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.SPLASH_POTION;
    }

    @Override
    public boolean onHitAsPotion(final ServerLevel level, final ItemStack potionItem, final @org.jspecify.annotations.Nullable HitResult hitResult) { // Paper - More projectile API
        PotionContents contents = potionItem.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        float durationScale = potionItem.getOrDefault(DataComponents.POTION_DURATION_SCALE, 1.0F);
        Iterable<MobEffectInstance> mobEffects = contents.getAllEffects();
        AABB potionAabb = hitResult == null ? this.getBoundingBox() : this.getBoundingBox().move(hitResult.getLocation().subtract(this.position())); // Paper - More projectile API
        AABB effectAabb = potionAabb.inflate(4.0, 2.0, 4.0);
        List<LivingEntity> entities = this.level().getEntitiesOfClass(LivingEntity.class, effectAabb);
        java.util.Map<org.bukkit.entity.LivingEntity, Double> affected = new java.util.HashMap<>(); // CraftBukkit
        float margin = ProjectileUtil.computeMargin(this);
        if (!entities.isEmpty()) {
            Entity effectSource = this.getEffectSource();

            for (LivingEntity entity : entities) {
                if (entity.isAffectedByPotions()) {
                    double dist = potionAabb.distanceToSqr(entity.getBoundingBox().inflate(margin));
                    if (dist < 16.0) {
                        double scale = 1.0 - Math.sqrt(dist) / 4.0; // Paper - diff on change, used when calling the splash event for water splash potions
                        // CraftBukkit start
                        affected.put(entity.getBukkitLivingEntity(), scale);
                    }
                }
            }
        }
        org.bukkit.event.entity.PotionSplashEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPotionSplashEvent(this, hitResult, affected);
        if (!event.isCancelled() && !entities.isEmpty()) { // do not process effects if there are no effects to process
            Entity effectSource = this.getEffectSource();
            for (org.bukkit.entity.LivingEntity victim : event.getAffectedEntities()) {
                if (!(victim instanceof org.bukkit.craftbukkit.entity.CraftLivingEntity craftLivingEntity)) {
                    continue;
                }
                LivingEntity entity = craftLivingEntity.getHandle();
                double scale = event.getIntensity(victim);
                {
                    {
                // CraftBukkit end
                        for (MobEffectInstance effectInstance : mobEffects) {
                            Holder<MobEffect> effect = effectInstance.getEffect();
                            if (effect.value().isInstantaneous()) {
                                effect.value().applyInstantaneousEffect(level, this, this.getOwner(), entity, effectInstance.getAmplifier(), scale);
                            } else {
                                int duration = effectInstance.mapDuration(d -> (int)(scale * d * durationScale + 0.5));
                                MobEffectInstance newEffect = new MobEffectInstance(
                                    effect, duration, effectInstance.getAmplifier(), effectInstance.isAmbient(), effectInstance.isVisible()
                                );
                                if (!newEffect.endsWithin(20)) {
                                    entity.addEffect(newEffect, effectSource, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.POTION_SPLASH); // CraftBukkit
                                }
                            }
                        }
                    }
                }
            }
        }
        return !event.isCancelled(); // Paper - Fix potions splash events
    }
}
