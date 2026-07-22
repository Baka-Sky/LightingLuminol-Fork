package net.minecraft.world.entity;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class ExperienceOrb extends Entity {
    protected static final EntityDataAccessor<Integer> DATA_VALUE = SynchedEntityData.defineId(ExperienceOrb.class, EntityDataSerializers.INT);
    private static final int LIFETIME = 6000;
    private static final int ENTITY_SCAN_PERIOD = 20;
    private static final int MAX_FOLLOW_DIST = 8;
    private static final int ORB_GROUPS_PER_AREA = 40;
    private static final double ORB_MERGE_DISTANCE = 0.5;
    private static final short DEFAULT_HEALTH = 5;
    private static final short DEFAULT_AGE = 0;
    private static final short DEFAULT_VALUE = 0;
    private static final int DEFAULT_COUNT = 1;
    private int age = 0;
    private int health = 5;
    public int count = 1;
    private @Nullable Player followingPlayer;
    private final InterpolationHandler interpolation = new InterpolationHandler(this);
    // Paper start
    public java.util.@Nullable UUID sourceEntityId;
    public java.util.@Nullable UUID triggerEntityId;
    public org.bukkit.entity.ExperienceOrb.SpawnReason spawnReason = org.bukkit.entity.ExperienceOrb.SpawnReason.UNKNOWN;

    private void loadPaperNBT(ValueInput input) {
        input.read("Paper.ExpData", net.minecraft.nbt.CompoundTag.CODEC).ifPresent(expData -> {
            this.sourceEntityId = expData.read("source", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
            this.triggerEntityId = expData.read("trigger", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
            expData.getString("reason").ifPresent(reason -> {
                try {
                    this.spawnReason = org.bukkit.entity.ExperienceOrb.SpawnReason.valueOf(reason);
                } catch (Exception e) {
                    this.level().getCraftServer().getLogger().warning("Invalid spawnReason set for experience orb: " + e.getMessage() + " - " + reason);
                }
            });
        });
    }
    private void savePaperNBT(ValueOutput output) {
        net.minecraft.nbt.CompoundTag expData = new net.minecraft.nbt.CompoundTag();
        expData.storeNullable("source", net.minecraft.core.UUIDUtil.CODEC, this.sourceEntityId);
        expData.storeNullable("trigger", net.minecraft.core.UUIDUtil.CODEC, this.triggerEntityId);
        if (this.spawnReason != org.bukkit.entity.ExperienceOrb.SpawnReason.UNKNOWN) {
            expData.putString("reason", this.spawnReason.name());
        }
        output.store("Paper.ExpData", net.minecraft.nbt.CompoundTag.CODEC, expData);
    }
    // Paper end
    @Deprecated @io.papermc.paper.annotation.DoNotUse // Paper - overload ctor
    public ExperienceOrb(final Level level, final double x, final double y, final double z, final int value) {
    // Paper start - add reasons for orbs
        this(level, x, y, z, value, null, null, null);
    }
    public ExperienceOrb(final Level level, final double x, final double y, final double z, final int value, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
        this(level, new Vec3(x, y, z), Vec3.ZERO, value, reason, triggerId, sourceId);
    // Paper end - add reasons for orbs
    }

    @Deprecated @io.papermc.paper.annotation.DoNotUse // Paper - overload ctor
    public ExperienceOrb(final Level level, final Vec3 pos, final Vec3 roughly, final int value) {
    // Paper start - add reasons for orbs
        this(level, pos, roughly, value, null, null, null);
    }
    public ExperienceOrb(final Level level, final Vec3 pos, final Vec3 roughly, final int value, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
    // Paper end - add reasons for orbs
        this(EntityTypes.EXPERIENCE_ORB, level);
        // Paper start - add reasons for orbs
        this.sourceEntityId = sourceId != null ? sourceId.getUUID() : null;
        this.triggerEntityId = triggerId != null ? triggerId.getUUID() : null;
        this.spawnReason = reason != null ? reason : org.bukkit.entity.ExperienceOrb.SpawnReason.UNKNOWN;
        // Paper end - add reasons for orbs
        this.setPos(pos);
        if (!level.isClientSide()) {
            this.setYRot(this.random.nextFloat() * 360.0F);
            Vec3 randomMovement = new Vec3(
                (this.random.nextDouble() * 0.2 - 0.1) * 2.0, this.random.nextDouble() * 0.2 * 2.0, (this.random.nextDouble() * 0.2 - 0.1) * 2.0
            );
            if (roughly.lengthSqr() > 0.0 && roughly.dot(randomMovement) < 0.0) {
                randomMovement = randomMovement.scale(-1.0);
            }

            double size = this.getBoundingBox().getSize();
            this.setPos(pos.add(roughly.normalize().scale(size * 0.5)));
            this.setDeltaMovement(randomMovement);
            if (!level.noCollision(this.getBoundingBox())) {
                this.unstuckIfPossible(size);
            }
        }

        this.setValue(value);
    }

    public ExperienceOrb(final EntityType<? extends ExperienceOrb> type, final Level level) {
        super(type, level);
    }

    protected void unstuckIfPossible(final double maxDistance) {
        Vec3 center = this.position().add(0.0, this.getBbHeight() / 2.0, 0.0);
        VoxelShape allowedCenters = Shapes.create(AABB.ofSize(center, maxDistance, maxDistance, maxDistance));
        this.level()
            .findFreePosition(this, allowedCenters, center, this.getBbWidth(), this.getBbHeight(), this.getBbWidth())
            .ifPresent(pos -> this.setPos(pos.add(0.0, -this.getBbHeight() / 2.0, 0.0)));
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
        entityData.define(DATA_VALUE, 0);
    }

    @Override
    protected double getDefaultGravity() {
        return 0.03;
    }

    @Override
    public void tick() {
        this.interpolation.interpolate();
        if (this.firstTick && this.level().isClientSide()) {
            this.firstTick = false;
        } else {
            super.tick();
            boolean colliding = !this.level().noCollision(this.getBoundingBox());
            if (this.isEyeInFluid(FluidTags.WATER)) {
                this.setUnderwaterMovement();
            } else if (!colliding) {
                this.applyGravity();
            }

            if (this.level().getFluidState(this.blockPosition()).is(FluidTags.LAVA)) {
                this.setDeltaMovement(
                    (this.random.nextFloat() - this.random.nextFloat()) * 0.2F, 0.2F, (this.random.nextFloat() - this.random.nextFloat()) * 0.2F
                );
            }

            if (this.tickCount % 20 == 1) {
                this.scanForMerges();
            }

            this.followNearbyPlayer();
            if (this.followingPlayer == null && !this.level().isClientSide() && colliding) {
                boolean nextColliding = !this.level().noCollision(this.getBoundingBox().move(this.getDeltaMovement()));
                if (nextColliding) {
                    this.moveTowardsClosestSpace(this.getX(), (this.getBoundingBox().minY + this.getBoundingBox().maxY) / 2.0, this.getZ());
                    this.needsSync = true;
                }
            }

            double fallSpeed = this.getDeltaMovement().y;
            this.move(MoverType.SELF, this.getDeltaMovement());
            this.applyEffectsFromBlocks();
            float friction = this.getAirDrag();
            if (this.onGround()) {
                friction *= this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction();
            }

            this.setDeltaMovement(this.getDeltaMovement().scale(friction));
            if (this.verticalCollisionBelow && fallSpeed < -this.getGravity()) {
                this.setDeltaMovement(new Vec3(this.getDeltaMovement().x, -fallSpeed * 0.4, this.getDeltaMovement().z));
            }

            this.age++;
            if (this.age >= 6000) {
                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
            }
        }
    }

    @Override
    protected float getAirDrag() {
        return 0.98F;
    }

    private void followNearbyPlayer() {
        Player prevTarget = this.followingPlayer; // CraftBukkit - store old target
        if (this.followingPlayer == null || this.followingPlayer.isSpectator() || this.followingPlayer.distanceToSqr(this) > 64.0) {
            Player nearestPlayer = this.level().getNearestPlayer(this, 8.0);
            if (nearestPlayer != null && !nearestPlayer.isSpectator() && !nearestPlayer.isDeadOrDying()) {
                this.followingPlayer = nearestPlayer;
            } else {
                this.followingPlayer = null;
            }
        }

        // CraftBukkit start
        boolean cancelled = false;
        if (this.followingPlayer != prevTarget) {
            org.bukkit.event.entity.EntityTargetLivingEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTargetLivingEvent(
                this, this.followingPlayer, (this.followingPlayer != null) ? org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_PLAYER : org.bukkit.event.entity.EntityTargetEvent.TargetReason.FORGOT_TARGET
            );
            LivingEntity target = (event.getTarget() == null) ? null : ((org.bukkit.craftbukkit.entity.CraftLivingEntity) event.getTarget()).getHandle();
            cancelled = event.isCancelled();

            if (cancelled) {
                this.followingPlayer = prevTarget;
            } else {
                this.followingPlayer = (target instanceof Player) ? (Player) target : null;
            }
        }

        if (this.followingPlayer != null && !cancelled) {
            // CraftBukkit end
            Vec3 delta = new Vec3(
                this.followingPlayer.getX() - this.getX(),
                this.followingPlayer.getY() + this.followingPlayer.getEyeHeight() / 2.0 - this.getY(),
                this.followingPlayer.getZ() - this.getZ()
            );
            double length = delta.lengthSqr();
            double power = 1.0 - Math.sqrt(length) / 8.0;
            this.setDeltaMovement(this.getDeltaMovement().add(delta.normalize().scale(power * power * 0.1)));
        }
    }

    @Override
    public BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return this.getOnPos(0.999999F);
    }

    private void scanForMerges() {
        if (this.level() instanceof ServerLevel) {
            for (ExperienceOrb orb : this.level().getEntities(EntityTypeTest.forClass(ExperienceOrb.class), this.getBoundingBox().inflate(0.5), this::canMerge)) {
                this.merge(orb);
            }
        }
    }

    public static void award(final ServerLevel level, final Vec3 pos, final int amount) {
        awardWithDirection(level, pos, Vec3.ZERO, amount);
    }

    public static void awardWithDirection(final ServerLevel level, final Vec3 pos, final Vec3 roughDirection, int amount) {
    // Paper start - add reason to orbs
        awardWithDirection(level, pos, roughDirection, amount, null, null, null);
    }
    public static void awardWithDirection(final ServerLevel level, final Vec3 pos, final Vec3 roughDirection, int amount, org.bukkit.entity.ExperienceOrb.@Nullable SpawnReason reason, final @Nullable Entity triggerId, final @Nullable Entity sourceId) {
    // Paper end - add reason to orbs
        while (amount > 0) {
            int newCount = getExperienceValue(amount);
            amount -= newCount;
            if (!tryMergeToExisting(level, pos, newCount)) {
                level.addFreshEntity(new ExperienceOrb(level, pos, roughDirection, newCount, reason, triggerId, sourceId)); // Paper - add reason to orbs
            }
        }
    }

    private static boolean tryMergeToExisting(final ServerLevel level, final Vec3 pos, final int value) {
        // Paper - TODO some other event for this kind of merge
        AABB box = AABB.ofSize(pos, 1.0, 1.0, 1.0);
        int id = level.getRandom().nextInt(io.papermc.paper.configuration.GlobalConfiguration.get().misc.xpOrbGroupsPerArea.or(ORB_GROUPS_PER_AREA)); // Paper - Configure how many orb groups per area
        List<ExperienceOrb> orbs = level.getEntities(EntityTypeTest.forClass(ExperienceOrb.class), box, orbx -> canMerge(orbx, id, value));
        if (!orbs.isEmpty()) {
            ExperienceOrb orb = orbs.get(0);
            orb.count++;
            orb.age = 0;
            return true;
        } else {
            return false;
        }
    }

    private boolean canMerge(final ExperienceOrb orb) {
        return orb != this && canMerge(orb, this.getId(), this.getValue());
    }

    private static boolean canMerge(final ExperienceOrb orb, final int id, final int value) {
        return !orb.isRemoved() && (orb.getId() - id) % io.papermc.paper.configuration.GlobalConfiguration.get().misc.xpOrbGroupsPerArea.or(ORB_GROUPS_PER_AREA) == 0 && orb.getValue() == value; // Paper - Configure how many orbs will merge together
    }

    private void merge(final ExperienceOrb orb) {
        // Paper start - call orb merge event
        if (!new com.destroystokyo.paper.event.entity.ExperienceOrbMergeEvent((org.bukkit.entity.ExperienceOrb) this.getBukkitEntity(), (org.bukkit.entity.ExperienceOrb) orb.getBukkitEntity()).callEvent()) {
            return;
        }
        // Paper end - call orb merge event
        this.count = this.count + orb.count;
        this.age = Math.min(this.age, orb.age);
        orb.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.MERGE); // CraftBukkit - add Bukkit remove cause
    }

    private void setUnderwaterMovement() {
        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(movement.x * 0.99F, Math.min(movement.y + 5.0E-4F, 0.06F), movement.z * 0.99F);
    }

    @Override
    protected void doWaterSplashEffect() {
    }

    @Override
    public final boolean hurtClient(final DamageSource source) {
        return !this.isInvulnerableToBase(source);
    }

    @Override
    public final boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
        if (this.isInvulnerableToBase(source)) {
            return false;
        }

        this.markHurt();
        this.health = (int)(this.health - damage);
        if (this.health <= 0) {
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
        }

        return true;
    }

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        output.putShort("Health", (short)this.health);
        output.putShort("Age", (short)this.age);
        output.putInt("Value", this.getValue()); // Paper - save as Integer
        output.putInt("Count", this.count);
        this.savePaperNBT(output); // Paper
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        this.health = input.getShortOr("Health", (short)5);
        this.age = input.getShortOr("Age", (short)0);
        this.setValue(input.getIntOr("Value", 0)); // Paper - load as Integer
        this.count = input.read("Count", ExtraCodecs.POSITIVE_INT).orElse(1);
        this.loadPaperNBT(input); // Paper
    }

    @Override
    public void playerTouch(final Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (player.takeXpDelay == 0 && new com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent(serverPlayer.getBukkitEntity(), (org.bukkit.entity.ExperienceOrb) this.getBukkitEntity()).callEvent()) { // Paper - PlayerPickupExperienceEvent
                player.takeXpDelay = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerXpCooldownEvent(player, 2, org.bukkit.event.player.PlayerExpCooldownChangeEvent.ChangeReason.PICKUP_ORB).getNewCooldown(); // CraftBukkit - entity.takeXpDelay = 2;
                player.take(this, 1);
                int remaining = this.repairPlayerItems(serverPlayer, this.getValue());
                if (remaining > 0) {
                    player.giveExperiencePoints(org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerExpChangeEvent(player, this, remaining).getAmount()); // CraftBukkit - remaining -> event.getAmount() // Paper - supply experience orb
                }

                this.count--;
                if (this.count == 0) {
                    this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
                }
            }
        }
    }

    private int repairPlayerItems(final ServerPlayer player, final int amount) {
        Optional<EnchantedItemInUse> selected = EnchantmentHelper.getRandomItemWith(EnchantmentEffectComponents.REPAIR_WITH_XP, player, ItemStack::isDamaged);
        if (selected.isPresent()) {
            ItemStack itemStack = selected.get().itemStack();
            int toRepairFromXpAmount = EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(), itemStack, amount);
            int repair = Math.min(toRepairFromXpAmount, itemStack.getDamageValue());
            // CraftBukkit start
            // Paper start - mending event
            final int consumedExperience = repair > 0 ? repair * amount / toRepairFromXpAmount : 0;
            org.bukkit.event.player.PlayerItemMendEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerItemMendEvent(player, this, itemStack, selected.get().inSlot(), repair, consumedExperience);
            // Paper end - mending event
            repair = event.getRepairAmount();
            if (event.isCancelled()) {
                return amount;
            }
            // CraftBukkit end
            itemStack.setDamageValue(itemStack.getDamageValue() - repair);
            if (repair > 0) {
                int remaining = amount - repair * amount / toRepairFromXpAmount; // Paper - diff on change - expand PlayerMendEvents
                if (remaining > 0) {
                    return this.repairPlayerItems(player, remaining);
                }
            }

            return 0;
        } else {
            return amount;
        }
    }

    public int getValue() {
        return this.entityData.get(DATA_VALUE);
    }

    public void setValue(final int value) {
        this.entityData.set(DATA_VALUE, value);
    }

    public int getIcon() {
        int value = this.getValue();
        if (value >= 2477) {
            return 10;
        } else if (value >= 1237) {
            return 9;
        } else if (value >= 617) {
            return 8;
        } else if (value >= 307) {
            return 7;
        } else if (value >= 149) {
            return 6;
        } else if (value >= 73) {
            return 5;
        } else if (value >= 37) {
            return 4;
        } else if (value >= 17) {
            return 3;
        } else if (value >= 7) {
            return 2;
        } else {
            return value >= 3 ? 1 : 0;
        }
    }

    public static int getExperienceValue(final int maxValue) {
        // CraftBukkit start
        if (maxValue > 162670129) return maxValue - 100000;
        if (maxValue > 81335063) return 81335063;
        if (maxValue > 40667527) return 40667527;
        if (maxValue > 20333759) return 20333759;
        if (maxValue > 10166857) return 10166857;
        if (maxValue > 5083423) return 5083423;
        if (maxValue > 2541701) return 2541701;
        if (maxValue > 1270849) return 1270849;
        if (maxValue > 635413) return 635413;
        if (maxValue > 317701) return 317701;
        if (maxValue > 158849) return 158849;
        if (maxValue > 79423) return 79423;
        if (maxValue > 39709) return 39709;
        if (maxValue > 19853) return 19853;
        if (maxValue > 9923) return 9923;
        if (maxValue > 4957) return 4957;
        // CraftBukkit end
        if (maxValue >= 2477) {
            return 2477;
        } else if (maxValue >= 1237) {
            return 1237;
        } else if (maxValue >= 617) {
            return 617;
        } else if (maxValue >= 307) {
            return 307;
        } else if (maxValue >= 149) {
            return 149;
        } else if (maxValue >= 73) {
            return 73;
        } else if (maxValue >= 37) {
            return 37;
        } else if (maxValue >= 17) {
            return 17;
        } else if (maxValue >= 7) {
            return 7;
        } else {
            return maxValue >= 3 ? 3 : 1;
        }
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.AMBIENT;
    }

    @Override
    public InterpolationHandler getInterpolation() {
        return this.interpolation;
    }
}
