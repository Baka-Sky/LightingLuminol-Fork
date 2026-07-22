package net.minecraft.world.entity.monster;

import java.util.Collection;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.SwellGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.animal.feline.Ocelot;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.jspecify.annotations.Nullable;

public class Creeper extends Monster {
    private static final EntityDataAccessor<Integer> DATA_SWELL_DIR = SynchedEntityData.defineId(Creeper.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_IS_POWERED = SynchedEntityData.defineId(Creeper.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_IS_IGNITED = SynchedEntityData.defineId(Creeper.class, EntityDataSerializers.BOOLEAN);
    private static final boolean DEFAULT_IGNITED = false;
    private static final boolean DEFAULT_POWERED = false;
    private static final short DEFAULT_MAX_SWELL = 30;
    private static final byte DEFAULT_EXPLOSION_RADIUS = 3;
    private int oldSwell;
    public int swell;
    public int maxSwell = 30;
    public int explosionRadius = 3;
    private boolean droppedSkulls;
    public @Nullable Entity entityIgniter; // CraftBukkit

    public Creeper(final EntityType<? extends Creeper> type, final Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new SwellGoal(this));
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, Ocelot.class, 6.0F, 1.0, 1.2));
        this.goalSelector.addGoal(3, new AvoidEntityGoal<>(this, Cat.class, 6.0F, 1.0, 1.2));
        this.goalSelector.addGoal(4, new MeleeAttackGoal(this, 1.0, false));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(2, new HurtByTargetGoal(this));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MOVEMENT_SPEED, 0.25);
    }

    @Override
    public int getMaxFallDistance() {
        return this.getTarget() == null ? this.getComfortableFallDistance(0.0F) : this.getComfortableFallDistance(this.getHealth() - 1.0F);
    }

    @Override
    public boolean causeFallDamage(final double fallDistance, final float damageModifier, final DamageSource damageSource) {
        boolean damaged = super.causeFallDamage(fallDistance, damageModifier, damageSource);
        this.swell += (int)(fallDistance * 1.5);
        if (this.swell > this.maxSwell - 5) {
            this.swell = this.maxSwell - 5;
        }

        return damaged;
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_SWELL_DIR, -1);
        entityData.define(DATA_IS_POWERED, false);
        entityData.define(DATA_IS_IGNITED, false);
    }

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putBoolean("powered", this.isPowered());
        output.putShort("Fuse", (short)this.maxSwell);
        output.putByte("ExplosionRadius", (byte)this.explosionRadius);
        output.putBoolean("ignited", this.isIgnited());
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        super.readAdditionalSaveData(input);
        this.entityData.set(DATA_IS_POWERED, input.getBooleanOr("powered", false));
        this.maxSwell = input.getShortOr("Fuse", (short)30);
        this.explosionRadius = input.getByteOr("ExplosionRadius", (byte)3);
        if (input.getBooleanOr("ignited", false)) {
            this.entityData.set(DATA_IS_IGNITED, true); // Paper - set directly to avoid firing event
        }
    }

    @Override
    public void tick() {
        if (this.isAlive()) {
            this.oldSwell = this.swell;
            if (this.isIgnited()) {
                this.setSwellDir(1);
            }

            int swellDir = this.getSwellDir();
            if (swellDir > 0 && this.swell == 0) {
                this.playSound(SoundEvents.CREEPER_PRIMED, 1.0F, 0.5F);
                this.gameEvent(GameEvent.PRIME_FUSE);
            }

            this.swell += swellDir;
            if (this.swell < 0) {
                this.swell = 0;
            }

            if (this.swell >= this.maxSwell) {
                this.swell = this.maxSwell;
                this.explodeCreeper();
            }
        }

        super.tick();
    }

    @Override
    public boolean setTarget(final @Nullable LivingEntity target, org.bukkit.event.entity.EntityTargetEvent.@Nullable TargetReason reason) { // CraftBukkit
        if (!(target instanceof Goat)) {
            return super.setTarget(target, reason); // CraftBukkit
        }
        return false; // CraftBukkit
    }

    @Override
    public SoundEvent getHurtSound(final DamageSource source) {
        return SoundEvents.CREEPER_HURT;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.CREEPER_DEATH;
    }

    @Override
    public boolean killedEntityPreEvent(final ServerLevel level, final LivingEntity entity, final DamageSource source) { // Paper
        if (this.shouldDropLoot(level) && this.isPowered() && !this.droppedSkulls) {
            entity.dropFromLootTable(level, source, false, BuiltInLootTables.CHARGED_CREEPER, itemStack -> {
                entity.spawnAtLocation(level, itemStack);
                entity.postDeathEventTasks.add(() -> this.droppedSkulls = true); // Paper
            });
        }

        return super.killedEntityPreEvent(level, entity, source); // Paper
    }

    @Override
    public boolean doHurtTarget(final ServerLevel level, final Entity target) {
        return true;
    }

    public boolean isPowered() {
        return this.entityData.get(DATA_IS_POWERED);
    }

    public float getSwelling(final float a) {
        return Mth.lerp(a, this.oldSwell, this.swell) / (this.maxSwell - 2);
    }

    public int getSwellDir() {
        return this.entityData.get(DATA_SWELL_DIR);
    }

    public void setSwellDir(final int dir) {
        this.entityData.set(DATA_SWELL_DIR, dir);
    }

    @Override
    public void thunderHit(final ServerLevel level, final LightningBolt lightningBolt) {
        super.thunderHit(level, lightningBolt);
        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.callCreeperPowerEvent(this, lightningBolt, org.bukkit.event.entity.CreeperPowerEvent.PowerCause.LIGHTNING).isCancelled()) {
            return;
        }
        // CraftBukkit end
        this.entityData.set(DATA_IS_POWERED, true);
    }

    // CraftBukkit start
    public void setPowered(boolean powered) {
        this.entityData.set(DATA_IS_POWERED, powered);
    }
    // CraftBukkit end

    @Override
    protected InteractionResult mobInteract(final Player player, final InteractionHand hand) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (itemStack.is(ItemTags.CREEPER_IGNITERS)) {
            SoundEvent soundEvent = itemStack.is(Items.FIRE_CHARGE) ? SoundEvents.FIRECHARGE_USE : SoundEvents.FLINTANDSTEEL_USE;
            this.level()
                .playSound(player, this.getX(), this.getY(), this.getZ(), soundEvent, this.getSoundSource(), 1.0F, this.random.nextFloat() * 0.4F + 0.8F);
            if (!this.level().isClientSide()) {
                this.entityIgniter = player; // CraftBukkit
                this.ignite();
                if (itemStack.getMaxDamage() == 0) { // CraftBukkit - fix MC-264285: unbreakable flint and steels are completely consumed when igniting a creeper
                    itemStack.shrink(1);
                } else {
                    itemStack.hurtAndBreak(1, player, hand.asEquipmentSlot());
                }
            }

            return InteractionResult.SUCCESS;
        } else {
            return super.mobInteract(player, hand);
        }
    }

    public void explodeCreeper() {
        if (this.level() instanceof ServerLevel level) {
            float explosionMultiplier = this.isPowered() ? 2.0F : 1.0F;
            // CraftBukkit start
            org.bukkit.event.entity.ExplosionPrimeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(this, this.explosionRadius * explosionMultiplier, false);
            if (!event.isCancelled()) {
            // CraftBukkit end
            this.dead = true;
            level.explode(this, this.getX(), this.getY(), this.getZ(), event.getRadius(), event.getFire(), Level.ExplosionInteraction.MOB); // CraftBukkit // Paper - fix DamageSource API (revert to vanilla, no, just no, don't change this)
            this.spawnLingeringCloud();
            this.triggerOnDeathMobEffects(level, Entity.RemovalReason.KILLED);
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.EXPLODE); // CraftBukkit - add Bukkit remove cause
            // CraftBukkit start
            } else {
                this.swell = 0;
                this.entityData.set(DATA_IS_IGNITED, false); // Paper
            }
            // CraftBukkit end
        }
    }

    private void spawnLingeringCloud() {
        Collection<MobEffectInstance> activeEffects = this.getActiveEffects();
        if (!activeEffects.isEmpty() && !this.level().paperConfig().entities.behavior.disableCreeperLingeringEffect) { // Paper - Option to disable creeper lingering effect
            AreaEffectCloud cloud = new AreaEffectCloud(this.level(), this.getX(), this.getY(), this.getZ());
            cloud.setOwner(this); // CraftBukkit
            cloud.setRadius(2.5F);
            cloud.setRadiusOnUse(-0.5F);
            cloud.setWaitTime(10);
            cloud.setDuration(300);
            cloud.setPotionDurationScale(0.25F);
            cloud.setRadiusPerTick(-cloud.getRadius() / cloud.getDuration());

            for (MobEffectInstance mobEffect : activeEffects) {
                cloud.addEffect(new MobEffectInstance(mobEffect));
            }

            this.level().addFreshEntity(cloud, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.EXPLOSION); // CraftBukkit
        }
    }

    // Paper start - Call CreeperIgniteEvent
    public void setIgnited(boolean ignited) {
        if (isIgnited() != ignited) {
            com.destroystokyo.paper.event.entity.CreeperIgniteEvent event = new com.destroystokyo.paper.event.entity.CreeperIgniteEvent((org.bukkit.entity.Creeper) getBukkitEntity(), ignited);
            if (event.callEvent()) {
                this.entityData.set(DATA_IS_IGNITED, event.isIgnited());
            }
        }
    }
    // Paper end - Call CreeperIgniteEvent

    public boolean isIgnited() {
        return this.entityData.get(DATA_IS_IGNITED);
    }

    public void ignite() {
        setIgnited(true); // Paper - Call CreeperIgniteEvent
    }
}
