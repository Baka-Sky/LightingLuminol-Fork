package net.minecraft.world.entity.projectile.throwableitemprojectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ThrownExperienceBottle extends ThrowableItemProjectile {
    public ThrownExperienceBottle(final EntityType<? extends ThrownExperienceBottle> type, final Level level) {
        super(type, level);
    }

    public ThrownExperienceBottle(final Level level, final LivingEntity mob, final ItemStack itemStack) {
        super(EntityTypes.EXPERIENCE_BOTTLE, mob, level, itemStack);
    }

    public ThrownExperienceBottle(final Level level, final double x, final double y, final double z, final ItemStack itemStack) {
        super(EntityTypes.EXPERIENCE_BOTTLE, x, y, z, level, itemStack);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.EXPERIENCE_BOTTLE;
    }

    @Override
    protected double getDefaultGravity() {
        return 0.07;
    }

    @Override
    protected void onHit(final HitResult hitResult) {
        super.onHit(hitResult);
        if (this.level() instanceof ServerLevel level) {
            // CraftBukkit - moved to after event
            int xpCount = 3 + this.random.nextInt(5) + this.random.nextInt(5);
            // Paper start - exp bottle event
            org.bukkit.event.entity.ExpBottleEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callExpBottleEvent(this, hitResult, xpCount);
            xpCount = event.getExperience();
            // Paper end - exp bottle event
            if (hitResult instanceof BlockHitResult blockHitResult) {
                Vec3 blockNormalHit = blockHitResult.getDirection().getUnitVec3();
                ExperienceOrb.awardWithDirection(level, hitResult.getLocation(), blockNormalHit, xpCount, org.bukkit.entity.ExperienceOrb.SpawnReason.EXP_BOTTLE, this.getOwner(), this); // Paper
            } else {
                ExperienceOrb.awardWithDirection(level, hitResult.getLocation(), this.getDeltaMovement().scale(-1.0), xpCount, org.bukkit.entity.ExperienceOrb.SpawnReason.EXP_BOTTLE, this.getOwner(), this); // Paper
            }
            // Paper start - exp bottle event
            if (event.getShowEffect()) {
                level.levelEvent(LevelEvent.PARTICLES_SPELL_POTION_SPLASH, this.blockPosition(), net.minecraft.world.item.alchemy.PotionContents.BASE_POTION_COLOR);
            }
            // Paper end - exp bottle event

            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
        }
    }
}
