package net.minecraft.world.entity.projectile.hurtingprojectile;

import java.util.List;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class DragonFireball extends AbstractHurtingProjectile {
    public static final float SPLASH_RANGE = 4.0F;

    public DragonFireball(final EntityType<? extends DragonFireball> type, final Level level) {
        super(type, level);
    }

    public DragonFireball(final Level level, final LivingEntity mob, final Vec3 direction) {
        super(EntityTypes.DRAGON_FIREBALL, mob, direction, level);
    }

    @Override
    protected void onHit(final HitResult hitResult) {
        super.onHit(hitResult);
        if (hitResult.getType() != HitResult.Type.ENTITY || !this.ownedBy(((EntityHitResult)hitResult).getEntity())) {
            if (!this.level().isClientSide()) {
                List<LivingEntity> entitiesOfClass = this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(4.0, 2.0, 4.0));
                AreaEffectCloud cloud = new AreaEffectCloud(this.level(), this.getX(), this.getY(), this.getZ());
                if (this.getOwner() instanceof LivingEntity livingEntity) {
                    cloud.setOwner(livingEntity);
                }

                cloud.setCustomParticle(PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F));
                cloud.setRadius(3.0F);
                cloud.setDuration(600);
                cloud.setRadiusPerTick((7.0F - cloud.getRadius()) / cloud.getDuration());
                cloud.setPotionDurationScale(0.25F);
                cloud.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 1));
                if (!entitiesOfClass.isEmpty()) {
                    for (LivingEntity entity : entitiesOfClass) {
                        double dist = this.distanceToSqr(entity);
                        if (dist < 16.0) {
                            cloud.setPos(entity.getX(), entity.getY(), entity.getZ());
                            break;
                        }
                    }
                }

                if (new com.destroystokyo.paper.event.entity.EnderDragonFireballHitEvent((org.bukkit.entity.DragonFireball) this.getBukkitEntity(), entitiesOfClass.stream().map(LivingEntity::getBukkitLivingEntity).collect(java.util.stream.Collectors.toList()), (org.bukkit.entity.AreaEffectCloud) cloud.getBukkitEntity()).callEvent()) { // Paper - EnderDragon Events
                this.level().levelEvent(LevelEvent.PARTICLES_DRAGON_FIREBALL_SPLASH, this.blockPosition(), this.isSilent() ? -1 : 1);
                this.level().addFreshEntity(cloud, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.EXPLOSION); // Paper - use correct spawn reason
                } else cloud.discard(null); // Paper - EnderDragon Events
                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
            }
        }
    }

    @Override
    protected ParticleOptions getTrailParticle() {
        return PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F);
    }

    @Override
    protected boolean shouldBurn() {
        return false;
    }
}
