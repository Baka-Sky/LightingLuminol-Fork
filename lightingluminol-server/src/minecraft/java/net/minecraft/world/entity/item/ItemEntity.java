package net.minecraft.world.entity.item;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class ItemEntity extends Entity implements TraceableEntity {
    private static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(ItemEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final float FLOAT_HEIGHT = 0.1F;
    public static final float EYE_HEIGHT = 0.2125F;
    private static final int LIFETIME = 6000;
    private static final int INFINITE_PICKUP_DELAY = 32767;
    private static final int INFINITE_LIFETIME = -32768;
    public static final int DEFAULT_HEALTH = 5;
    private static final short DEFAULT_AGE = 0;
    private static final short DEFAULT_PICKUP_DELAY = 0;
    public int age = 0;
    public int pickupDelay = 0;
    public int health = 5;
    public @Nullable EntityReference<Entity> thrower;
    public @Nullable UUID target;
    public final float bobOffs = this.random.nextFloat() * (float) Math.PI * 2.0F;
    public boolean canMobPickup = true; // Paper - Item#canEntityPickup
    private int despawnRate = -1; // Paper - Alternative item-despawn-rate
    public net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API

    public ItemEntity(final EntityType<? extends ItemEntity> type, final Level level) {
        super(type, level);
        this.setYRot(this.random.nextFloat() * 360.0F);
    }

    public ItemEntity(final Level level, final double x, final double y, final double z, final ItemStack itemStack) {
        this(EntityTypes.ITEM, level);
        this.setPos(x, y, z);
        this.setItem(itemStack);
        this.setDeltaMovement(this.random.nextDouble() * 0.2 - 0.1, 0.2, this.random.nextDouble() * 0.2 - 0.1);
    }

    public ItemEntity(
        final Level level,
        final double x,
        final double y,
        final double z,
        final ItemStack itemStack,
        final double deltaX,
        final double deltaY,
        final double deltaZ
    ) {
        this(EntityTypes.ITEM, level);
        this.setPos(x, y, z);
        this.setItem(itemStack);
        this.setDeltaMovement(deltaX, deltaY, deltaZ);
    }

    // Paper start - Require item entities to send their location precisely (Fixes MC-4)
    {
        if (io.papermc.paper.configuration.GlobalConfiguration.get().misc.sendFullPosForItemEntities) {
            this.setRequiresPrecisePosition(true);
        }
    }
    // Paper end - Require item entities to send their location precisely (Fixes MC-4)

    @Override
    public boolean dampensVibrations() {
        return this.getItem().is(ItemTags.DAMPENS_VIBRATIONS);
    }

    @Override
    public @Nullable Entity getOwner() {
        return EntityReference.getEntity(this.thrower, this.level());
    }

    @Override
    public void restoreFrom(final Entity oldEntity) {
        super.restoreFrom(oldEntity);
        if (oldEntity instanceof ItemEntity item) {
            this.thrower = item.thrower;
        }
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
        entityData.define(DATA_ITEM, ItemStack.EMPTY);
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    // Paper start - EAR 2
    @Override
    public void inactiveTick() {
        super.inactiveTick();
        if (this.pickupDelay > 0 && this.pickupDelay != 32767) {
            this.pickupDelay--;
        }
        if (this.age != -32768) {
            this.age++;
        }

        if (!this.level().isClientSide() && this.age >= this.despawnRate) {// Paper - Alternative item-despawn-rate
            // CraftBukkit start - fire ItemDespawnEvent
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callItemDespawnEvent(this).isCancelled()) {
                this.age = 0;
                return;
            }
            // CraftBukkit end
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
        }
    }
    // Paper end - EAR 2

    @Override
    public void tick() {
        if (this.getItem().isEmpty()) {
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
        } else {
            super.tick();
            if (this.pickupDelay > 0 && this.pickupDelay != 32767) {
                this.pickupDelay--;
            }

            this.xo = this.getX();
            this.yo = this.getY();
            this.zo = this.getZ();
            Vec3 oldMovement = this.getDeltaMovement();
            if (this.isInWater() && this.getFluidHeight(FluidTags.WATER) > 0.1F) {
                this.setUnderwaterMovement();
            } else if (this.isInLava() && this.getFluidHeight(FluidTags.LAVA) > 0.1F) {
                this.setUnderLavaMovement();
            } else {
                this.applyGravity();
            }

            if (this.level().isClientSide()) {
                this.noPhysics = false;
            } else {
                this.noPhysics = !this.level().noCollision(this, this.getBoundingBox().deflate(1.0E-7));
                if (this.noPhysics) {
                    this.moveTowardsClosestSpace(this.getX(), (this.getBoundingBox().minY + this.getBoundingBox().maxY) / 2.0, this.getZ());
                }
            }

            if (this.onGround() && !(this.getDeltaMovement().horizontalDistanceSqr() > 1.0E-5F) && (this.tickCount + this.getId()) % 4 != 0) { // Paper - Diff on change; ActivationRange immunity
                this.applyEffectsFromBlocksForLastMovements();
            } else {
                this.move(MoverType.SELF, this.getDeltaMovement());
                this.applyEffectsFromBlocks();
                float airDrag = this.getAirDrag();
                float groundFriction = airDrag;
                // Paper start - Friction API
                if (this.frictionState == net.kyori.adventure.util.TriState.FALSE) {
                    groundFriction = 1F;
                } else if (this.onGround()) {
                // Paper end - Friction API
                    groundFriction *= this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction();
                }

                this.setDeltaMovement(this.getDeltaMovement().multiply(groundFriction, airDrag, groundFriction));
                if (this.onGround()) {
                    Vec3 movement = this.getDeltaMovement();
                    if (movement.y < 0.0) {
                        this.setDeltaMovement(movement.multiply(1.0, -0.5, 1.0));
                    }
                }
            }

            boolean moved = Mth.floor(this.xo) != Mth.floor(this.getX())
                || Mth.floor(this.yo) != Mth.floor(this.getY())
                || Mth.floor(this.zo) != Mth.floor(this.getZ());
            int rate = moved ? 2 : 40;
            if (this.tickCount % rate == 0 && !this.level().isClientSide() && this.isMergable()) {
                this.mergeWithNeighbours();
            }

            if (this.age != -32768) {
                this.age++;
            }

            this.needsSync = this.needsSync | this.updateFluidInteraction();
            if (!this.level().isClientSide()) {
                double value = this.getDeltaMovement().subtract(oldMovement).lengthSqr();
                if (value > 0.01) {
                    this.needsSync = true;
                }
            }

            if (!this.level().isClientSide() && this.age >= this.despawnRate) { // Spigot // Paper - Alternative item-despawn-rate
                // CraftBukkit start - fire ItemDespawnEvent
                if (org.bukkit.craftbukkit.event.CraftEventFactory.callItemDespawnEvent(this).isCancelled()) {
                    this.age = 0;
                    return;
                }
                // CraftBukkit end
                this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
            }
        }
    }

    @Override
    public BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return this.getOnPos(0.999999F);
    }

    private void setUnderwaterMovement() {
        this.setFluidMovement(0.99F);
    }

    private void setUnderLavaMovement() {
        this.setFluidMovement(0.95F);
    }

    private void setFluidMovement(final double multiplier) {
        Vec3 movement = this.getDeltaMovement();
        this.setDeltaMovement(movement.x * multiplier, movement.y + (movement.y < 0.06F ? 5.0E-4F : 0.0F), movement.z * multiplier);
    }

    private void mergeWithNeighbours() {
        if (this.isMergable()) {
            double radius = this.level().spigotConfig.itemMerge; // Spigot
            for (ItemEntity entity : this.level()
                .getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(radius, this.level().paperConfig().entities.behavior.onlyMergeItemsHorizontally ? 0 : radius - 0.5D, radius), other -> other != this && other.isMergable())) { // Spigot // Paper - configuration to only merge items horizontally
                if (entity.isMergable()) {
                    // Paper start - Fix items merging through walls
                    if (this.level().paperConfig().fixes.fixItemsMergingThroughWalls) {
                        if (this.level().clipDirect(this.position(), entity.position(),
                            net.minecraft.world.phys.shapes.CollisionContext.of(this)) == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                            continue;
                        }
                    }
                    // Paper end - Fix items merging through walls
                    this.tryToMerge(entity);
                    if (this.isRemoved()) {
                        break;
                    }
                }
            }
        }
    }

    private boolean isMergable() {
        ItemStack item = this.getItem();
        return this.isAlive() && this.pickupDelay != 32767 && this.age != -32768 && this.age < this.despawnRate && item.getCount() < item.getMaxStackSize(); // Paper - Alternative item-despawn-rate
    }

    private void tryToMerge(final ItemEntity other) {
        ItemStack thisItemStack = this.getItem();
        ItemStack otherItemStack = other.getItem();
        if (Objects.equals(this.target, other.target) && areMergable(thisItemStack, otherItemStack)) {
            if (otherItemStack.getCount() < thisItemStack.getCount()) {
                merge(this, thisItemStack, other, otherItemStack);
            } else {
                merge(other, otherItemStack, this, thisItemStack);
            }
        }
    }

    public static boolean areMergable(final ItemStack thisItemStack, final ItemStack otherItemStack) {
        return otherItemStack.getCount() + thisItemStack.getCount() <= otherItemStack.getMaxStackSize()
            && ItemStack.isSameItemSameComponents(thisItemStack, otherItemStack);
    }

    public static ItemStack merge(final ItemStack toStack, final ItemStack fromStack, final int maxCount) {
        int delta = Math.min(Math.min(toStack.getMaxStackSize(), maxCount) - toStack.getCount(), fromStack.getCount());
        ItemStack newToStack = toStack.copyWithCount(toStack.getCount() + delta);
        fromStack.shrink(delta);
        return newToStack;
    }

    private static void merge(final ItemEntity toItem, final ItemStack toStack, final ItemStack fromStack) {
        ItemStack newToStack = merge(toStack, fromStack, 64);
        toItem.setItem(newToStack);
    }

    private static void merge(final ItemEntity toItem, final ItemStack toStack, final ItemEntity fromItem, final ItemStack fromStack) {
        // CraftBukkit start
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.callItemMergeEvent(fromItem, toItem)) {
            return;
        }
        // CraftBukkit end
        merge(toItem, toStack, fromStack);
        toItem.pickupDelay = Math.max(toItem.pickupDelay, fromItem.pickupDelay);
        toItem.age = Math.min(toItem.age, fromItem.age);
        if (fromStack.isEmpty()) {
            fromItem.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.MERGE); // CraftBukkit - add Bukkit remove cause
        }
    }

    @Override
    public boolean fireImmune() {
        return !this.getItem().canBeHurtBy(this.damageSources().inFire()) || super.fireImmune();
    }

    @Override
    protected boolean shouldPlayLavaHurtSound() {
        return this.health <= 0 || this.tickCount % 10 == 0;
    }

    @Override
    public final boolean hurtClient(final DamageSource source) {
        return !this.isInvulnerableToBase(source) && this.getItem().canBeHurtBy(source);
    }

    @Override
    public final boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
        if (this.isInvulnerableToBase(source)) {
            return false;
        }

        if (!level.getGameRules().get(GameRules.MOB_GRIEFING) && source.getEntity() instanceof Mob) {
            return false;
        }

        if (!this.getItem().canBeHurtBy(source)) {
            return false;
        }

        // CraftBukkit start
        if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage)) {
            return false;
        }
        // CraftBukkit end
        this.markHurt();
        this.health = (int)(this.health - damage);
        this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
        if (this.health <= 0) {
            this.getItem().onDestroyed(this);
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
        }

        return true;
    }

    @Override
    public boolean ignoreExplosion(final Explosion explosion) {
        return !explosion.shouldAffectBlocklikeEntities() || super.ignoreExplosion(explosion);
    }

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        output.putShort("Health", (short)this.health);
        output.putShort("Age", (short)this.age);
        output.putShort("PickupDelay", (short)this.pickupDelay);
        EntityReference.store(this.thrower, output, "Thrower");
        output.storeNullable("Owner", UUIDUtil.CODEC, this.target);
        if (!this.getItem().isEmpty()) {
            output.store("Item", ItemStack.CODEC, this.getItem());
        }
        // Paper start - Friction API
        if (this.frictionState != net.kyori.adventure.util.TriState.NOT_SET) {
            output.putString("Paper.FrictionState", this.frictionState.toString());
        }
        // Paper end - Friction API
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        this.health = input.getShortOr("Health", (short)5);
        this.age = input.getShortOr("Age", (short)0);
        this.pickupDelay = input.getShortOr("PickupDelay", (short)0);
        this.target = input.read("Owner", UUIDUtil.CODEC).orElse(null);
        this.thrower = EntityReference.read(input, "Thrower");
        this.setItem(input.read("Item", ItemStack.CODEC).orElse(ItemStack.EMPTY));
        // Paper start - Friction API
        input.getString("Paper.FrictionState").ifPresent(frictionState -> {
            try {
                this.frictionState = net.kyori.adventure.util.TriState.valueOf(frictionState);
            } catch (Exception ignored) {
                com.mojang.logging.LogUtils.getLogger().error("Unknown friction state {} for {}", frictionState, this);
            }
        });
        // Paper end - Friction API
        if (this.getItem().isEmpty()) {
            this.discard(null); // CraftBukkit - add Bukkit remove cause
        }
    }

    @Override
    public void playerTouch(final Player player) {
        if (!this.level().isClientSide()) {
            ItemStack itemStack = this.getItem();
            Item item = itemStack.getItem();
            int orgCount = itemStack.getCount();
            // CraftBukkit start - fire PlayerPickupItemEvent
            int canHold = player.getInventory().canHold(itemStack);
            int remaining = orgCount - canHold;
            boolean flyAtPlayer = false; // Paper

            // Paper start - PlayerAttemptPickupItemEvent
            if (this.pickupDelay <= 0) {
                org.bukkit.event.player.PlayerAttemptPickupItemEvent attemptEvent = new org.bukkit.event.player.PlayerAttemptPickupItemEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                this.level().getCraftServer().getPluginManager().callEvent(attemptEvent);

                flyAtPlayer = attemptEvent.getFlyAtPlayer();
                if (attemptEvent.isCancelled()) {
                    if (flyAtPlayer) {
                        player.take(this, orgCount);
                    }

                    return;
                }
            }

            if (this.pickupDelay <= 0 && canHold > 0) {
                itemStack.setCount(canHold);
                // Call legacy event
                org.bukkit.event.player.PlayerPickupItemEvent playerEvent = new org.bukkit.event.player.PlayerPickupItemEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                playerEvent.setCancelled(!playerEvent.getPlayer().getCanPickupItems());
                this.level().getCraftServer().getPluginManager().callEvent(playerEvent);
                flyAtPlayer = playerEvent.getFlyAtPlayer(); // Paper
                if (playerEvent.isCancelled()) {
                    itemStack.setCount(orgCount); // SPIGOT-5294 - restore count
                    // Paper start
                    if (flyAtPlayer) {
                        player.take(this, orgCount);
                    }
                    // Paper end
                    return;
                }

                // Call newer event afterwards
                org.bukkit.event.entity.EntityPickupItemEvent entityEvent = new org.bukkit.event.entity.EntityPickupItemEvent(player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                entityEvent.setCancelled(!entityEvent.getEntity().getCanPickupItems());
                this.level().getCraftServer().getPluginManager().callEvent(entityEvent);
                if (entityEvent.isCancelled()) {
                    itemStack.setCount(orgCount); // SPIGOT-5294 - restore count
                    return;
                }

                // Update the ItemStack if it was changed in the event
                ItemStack current = this.getItem();
                if (!itemStack.equals(current)) {
                    itemStack = current;
                } else {
                    itemStack.setCount(canHold + remaining); // = i
                }

                // Possibly < 0; fix here so we do not have to modify code below
                this.pickupDelay = 0;
            } else if (this.pickupDelay == 0) {
                // ensure that the code below isn't triggered if canHold says we can't pick the items up
                this.pickupDelay = -1;
            }
            // CraftBukkit end
            // Paper end - PlayerAttemptPickupItemEvent
            if (this.pickupDelay == 0 && (this.target == null || this.target.equals(player.getUUID())) && player.getInventory().add(itemStack)) {
                if (flyAtPlayer) // Paper - PlayerPickupItemEvent
                player.take(this, orgCount);
                if (itemStack.isEmpty()) {
                    this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
                    itemStack.setCount(orgCount);
                }

                player.awardStat(Stats.ITEM_PICKED_UP.get(item), orgCount);
                player.onItemPickup(this);
            }
        }
    }

    @Override
    public Component getName() {
        Component name = this.getCustomName();
        return name != null ? name : this.getItem().getItemName();
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    // Folia start - region threading
    @Override
    public void postChangeDimension() {
        super.postChangeDimension();
        if (!this.level().isClientSide() && this instanceof ItemEntity item) {
            item.mergeWithNeighbours();
        }
    }
    // Folia end - region threading

    @Override
    public @Nullable Entity teleport(final TeleportTransition transition) {
        Entity entity = super.teleport(transition);
        if (entity != null) entity.postChangeDimension(); // Folia - region threading - move to post change
        if (!this.level().isClientSide() && entity instanceof ItemEntity item) {
            item.mergeWithNeighbours();
        }

        return entity;
    }

    public ItemStack getItem() {
        return this.getEntityData().get(DATA_ITEM);
    }

    public void setItem(final ItemStack itemStack) {
        this.getEntityData().set(DATA_ITEM, itemStack);
        this.despawnRate = this.level().paperConfig().entities.spawning.altItemDespawnRate.enabled ? this.level().paperConfig().entities.spawning.altItemDespawnRate.items.getOrDefault(itemStack.getItem(), this.level().spigotConfig.itemDespawnRate) : this.level().spigotConfig.itemDespawnRate; // Paper - Alternative item-despawn-rate
    }

    public void setTarget(final @Nullable UUID target) {
        this.target = target;
    }

    public void setThrower(final Entity thrower) {
        this.thrower = EntityReference.of(thrower);
    }

    public int getAge() {
        return this.age;
    }

    public void setDefaultPickUpDelay() {
        this.pickupDelay = 10;
    }

    public void setNoPickUpDelay() {
        this.pickupDelay = 0;
    }

    public void setNeverPickUp() {
        this.pickupDelay = 32767;
    }

    public void setPickUpDelay(final int ticks) {
        this.pickupDelay = ticks;
    }

    public boolean hasPickUpDelay() {
        return this.pickupDelay > 0;
    }

    public void setUnlimitedLifetime() {
        this.age = -32768;
    }

    public void setExtendedLifetime() {
        this.age = -6000;
    }

    public void makeFakeItem() {
        this.setNeverPickUp();
        this.age = this.despawnRate - 1; // Spigot // Paper - Alternative item-despawn-rate
    }

    public static float getSpin(final float ageInTicks, final float bobOffset) {
        return ageInTicks / 20.0F + bobOffset;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.AMBIENT;
    }

    @Override
    public float getVisualRotationYInDegrees() {
        return 180.0F - getSpin(this.getAge() + 0.5F, this.bobOffs) / (Mth.PI * 2.0F) * 360.0F;
    }

    @Override
    public @Nullable SlotAccess getSlot(final int slot) {
        return slot == 0 ? SlotAccess.of(this::getItem, this::setItem) : super.getSlot(slot);
    }
}
