package net.minecraft.world.entity.projectile.hurtingprojectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class LargeFireball extends Fireball {
    private static final byte DEFAULT_EXPLOSION_POWER = 1;
    public int explosionPower = 1;

    public LargeFireball(final EntityType<? extends LargeFireball> type, final Level level) {
        super(type, level);
        this.isIncendiary = (level instanceof ServerLevel serverLevel) && serverLevel.getGameRules().get(GameRules.MOB_GRIEFING); // CraftBukkit
    }

    public LargeFireball(final Level level, final LivingEntity mob, final Vec3 direction, final int explosionPower) {
        super(EntityTypes.FIREBALL, mob, direction, level);
        this.explosionPower = explosionPower;
        this.isIncendiary = (level instanceof ServerLevel serverLevel) && serverLevel.getGameRules().get(GameRules.MOB_GRIEFING); // CraftBukkit
    }

    @Override
    protected void onHit(final HitResult hitResult) {
        super.onHit(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            // boolean grief = serverLevel.getGameRules().get(GameRules.MOB_GRIEFING); // CraftBukkit - baked into fields (see constructor)
            // CraftBukkit start - fire ExplosionPrimeEvent
            org.bukkit.event.entity.ExplosionPrimeEvent event = new org.bukkit.event.entity.ExplosionPrimeEvent((org.bukkit.entity.Explosive) this.getBukkitEntity());
            if (event.callEvent()) {
                this.level().explode(this, this.getX(), this.getY(), this.getZ(), event.getRadius(), event.getFire(), Level.ExplosionInteraction.MOB);
            }
            // CraftBukkit end - fire ExplosionPrimeEvent
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
        }
    }

    @Override
    protected void onHitEntity(final EntityHitResult hitResult) {
        super.onHitEntity(hitResult);
        if (this.level() instanceof ServerLevel serverLevel) {
            Entity var6 = hitResult.getEntity();
            Entity owner = this.getOwner();
            DamageSource damageSource = this.damageSources().fireball(this, owner);
            var6.hurtServer(serverLevel, damageSource, 6.0F);
            EnchantmentHelper.doPostAttackEffects(serverLevel, var6, damageSource);
        }
    }

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putByte("ExplosionPower", (byte)this.explosionPower);
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        super.readAdditionalSaveData(input);
        this.bukkitYield = this.explosionPower = input.getByteOr("ExplosionPower", (byte)1); // CraftBukkit - set bukkitYield when setting explosionPower
    }
}
