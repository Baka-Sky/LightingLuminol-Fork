package net.minecraft.world.entity.decoration;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Rotations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class ArmorStand extends LivingEntity {
    public static final int WOBBLE_TIME = 5;
    private static final boolean ENABLE_ARMS = true;
    public static final Rotations DEFAULT_HEAD_POSE = new Rotations(0.0F, 0.0F, 0.0F);
    public static final Rotations DEFAULT_BODY_POSE = new Rotations(0.0F, 0.0F, 0.0F);
    public static final Rotations DEFAULT_LEFT_ARM_POSE = new Rotations(-10.0F, 0.0F, -10.0F);
    public static final Rotations DEFAULT_RIGHT_ARM_POSE = new Rotations(-15.0F, 0.0F, 10.0F);
    public static final Rotations DEFAULT_LEFT_LEG_POSE = new Rotations(-1.0F, 0.0F, -1.0F);
    public static final Rotations DEFAULT_RIGHT_LEG_POSE = new Rotations(1.0F, 0.0F, 1.0F);
    private static final EntityDimensions MARKER_DIMENSIONS = EntityDimensions.fixed(0.0F, 0.0F);
    private static final EntityDimensions BABY_DIMENSIONS = EntityTypes.ARMOR_STAND.getDimensions().scale(0.5F).withEyeHeight(0.9875F);
    private static final double FEET_OFFSET = 0.1;
    private static final double CHEST_OFFSET = 0.9;
    private static final double LEGS_OFFSET = 0.4;
    private static final double HEAD_OFFSET = 1.6;
    public static final int DISABLE_TAKING_OFFSET = 8;
    public static final int DISABLE_PUTTING_OFFSET = 16;
    public static final int CLIENT_FLAG_SMALL = 1;
    public static final int CLIENT_FLAG_SHOW_ARMS = 4;
    public static final int CLIENT_FLAG_NO_BASEPLATE = 8;
    public static final int CLIENT_FLAG_MARKER = 16;
    public static final EntityDataAccessor<Byte> DATA_CLIENT_FLAGS = SynchedEntityData.defineId(ArmorStand.class, EntityDataSerializers.BYTE);
    public static final EntityDataAccessor<Rotations> DATA_HEAD_POSE = SynchedEntityData.defineId(ArmorStand.class, EntityDataSerializers.ROTATIONS);
    public static final EntityDataAccessor<Rotations> DATA_BODY_POSE = SynchedEntityData.defineId(ArmorStand.class, EntityDataSerializers.ROTATIONS);
    public static final EntityDataAccessor<Rotations> DATA_LEFT_ARM_POSE = SynchedEntityData.defineId(ArmorStand.class, EntityDataSerializers.ROTATIONS);
    public static final EntityDataAccessor<Rotations> DATA_RIGHT_ARM_POSE = SynchedEntityData.defineId(ArmorStand.class, EntityDataSerializers.ROTATIONS);
    public static final EntityDataAccessor<Rotations> DATA_LEFT_LEG_POSE = SynchedEntityData.defineId(ArmorStand.class, EntityDataSerializers.ROTATIONS);
    public static final EntityDataAccessor<Rotations> DATA_RIGHT_LEG_POSE = SynchedEntityData.defineId(ArmorStand.class, EntityDataSerializers.ROTATIONS);
    private static final Predicate<Entity> RIDABLE_MINECARTS = entity -> entity instanceof AbstractMinecart minecart && minecart.isRideable();
    private static final boolean DEFAULT_INVISIBLE = false;
    private static final int DEFAULT_DISABLED_SLOTS = 0;
    private static final boolean DEFAULT_SMALL = false;
    private static final boolean DEFAULT_SHOW_ARMS = false;
    private static final boolean DEFAULT_NO_BASE_PLATE = false;
    private static final boolean DEFAULT_MARKER = false;
    private boolean invisible = false;
    public long lastHit;
    public int disabledSlots = 0;
    public boolean canMove = true; // Paper
    // Paper start - Allow ArmorStands not to tick
    public boolean canTick = true;
    public boolean canTickSetByAPI = false;
    private boolean noTickEquipmentDirty = false;
    // Paper end - Allow ArmorStands not to tick

    public ArmorStand(final EntityType<? extends ArmorStand> type, final Level level) {
        super(type, level);
        if (level != null) this.canTick = level.paperConfig().entities.armorStands.tick; // Paper - Allow ArmorStands not to tick
    }

    public ArmorStand(final Level level, final double x, final double y, final double z) {
        this(EntityTypes.ARMOR_STAND, level);
        this.setPos(x, y, z);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return createLivingAttributes().add(Attributes.STEP_HEIGHT, 0.0);
    }

    // CraftBukkit start - SPIGOT-3607, SPIGOT-3637
    @Override
    public float getBukkitYaw() {
        return this.getYRot();
    }
    // CraftBukkit end

    @Override
    public void refreshDimensions() {
        double oldX = this.getX();
        double oldY = this.getY();
        double oldZ = this.getZ();
        super.refreshDimensions();
        this.setPos(oldX, oldY, oldZ);
    }

    private boolean hasPhysics() {
        return !this.isMarker() && !this.isNoGravity();
    }

    @Override
    public boolean isEffectiveAi() {
        return super.isEffectiveAi() && this.hasPhysics();
    }

    @Override
    protected void defineSynchedData(final SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_CLIENT_FLAGS, (byte)0);
        entityData.define(DATA_HEAD_POSE, DEFAULT_HEAD_POSE);
        entityData.define(DATA_BODY_POSE, DEFAULT_BODY_POSE);
        entityData.define(DATA_LEFT_ARM_POSE, DEFAULT_LEFT_ARM_POSE);
        entityData.define(DATA_RIGHT_ARM_POSE, DEFAULT_RIGHT_ARM_POSE);
        entityData.define(DATA_LEFT_LEG_POSE, DEFAULT_LEFT_LEG_POSE);
        entityData.define(DATA_RIGHT_LEG_POSE, DEFAULT_RIGHT_LEG_POSE);
    }

    @Override
    public boolean canUseSlot(final EquipmentSlot slot) {
        return slot != EquipmentSlot.BODY && slot != EquipmentSlot.SADDLE && !this.isDisabled(slot);
    }

    // Paper start - Allow ArmorStands not to tick; Still update equipment
    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack, boolean silent) {
        super.setItemSlot(slot, stack, silent);
        this.noTickEquipmentDirty = true;
    }
    // Paper end - Allow ArmorStands not to tick; Still update equipment

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putBoolean("Invisible", this.isInvisible());
        output.putBoolean("Small", this.isSmall());
        output.putBoolean("ShowArms", this.showArms());
        output.putInt("DisabledSlots", this.disabledSlots);
        output.putBoolean("NoBasePlate", !this.showBasePlate());
        if (this.isMarker()) {
            output.putBoolean("Marker", this.isMarker());
        }

        output.store("Pose", ArmorStand.ArmorStandPose.CODEC, this.getArmorStandPose());
        if (this.canTickSetByAPI) output.putBoolean("Paper.CanTickOverride", this.canTick); // Paper - Allow ArmorStands not to tick
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        super.readAdditionalSaveData(input);
        this.setInvisible(input.getBooleanOr("Invisible", false));
        this.setSmall(input.getBooleanOr("Small", false));
        this.setShowArms(input.getBooleanOr("ShowArms", false));
        this.disabledSlots = input.getIntOr("DisabledSlots", 0);
        this.setNoBasePlate(input.getBooleanOr("NoBasePlate", false));
        this.setMarker(input.getBooleanOr("Marker", false));
        this.noPhysics = !this.hasPhysics();
        input.read("Pose", ArmorStand.ArmorStandPose.CODEC).ifPresent(this::setArmorStandPose);
        // Paper start - Allow ArmorStands not to tick
        if (input.getInt("Paper.CanTickOverride").isPresent()) { // Check if is set
            this.canTick = input.getBooleanOr("Paper.CanTickOverride", true);
            this.canTickSetByAPI = true;
        }
        // Paper end - Allow ArmorStands not to tick
    }

    @Override
    public boolean isCollidable(boolean ignoreClimbing) { // Paper - Climbing should not bypass cramming gamerule
        return false;
    }

    @Override
    protected void doPush(final Entity entity) {
    }

    @Override
    protected void pushEntities() {
        if (!this.level().paperConfig().entities.armorStands.doCollisionEntityLookups) return; // Paper - Option to prevent armor stands from doing entity lookups
        for (Entity entity : this.level().getEntitiesOfClass(AbstractMinecart.class, this.getBoundingBox(), RIDABLE_MINECARTS)) { // Paper - optimise collisions
            if (this.distanceToSqr(entity) <= 0.2) {
                entity.push(this);
            }
        }
    }

    @Override
    public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
        ItemStack itemStack = player.getItemInHand(hand);
        if (this.isMarker() || itemStack.is(Items.NAME_TAG)) {
            return super.interact(player, hand, location);
        }

        if (player.isSpectator()) {
            return InteractionResult.SUCCESS;
        }

        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS_SERVER;
        }

        EquipmentSlot itemInHandSlot = this.getEquipmentSlotForItem(itemStack);
        if (itemStack.isEmpty()) {
            EquipmentSlot clickedSlot = this.getClickedSlot(location);
            EquipmentSlot targetSlot = this.isDisabled(clickedSlot) ? itemInHandSlot : clickedSlot;
            if (this.hasItemInSlot(targetSlot) && this.swapItem(player, targetSlot, itemStack, hand)) {
                return InteractionResult.SUCCESS_SERVER;
            }
        } else {
            if (this.isDisabled(itemInHandSlot)) {
                return InteractionResult.FAIL;
            }

            if (itemInHandSlot.getType() == EquipmentSlot.Type.HAND && !this.showArms()) {
                return InteractionResult.FAIL;
            }

            if (this.swapItem(player, itemInHandSlot, itemStack, hand)) {
                return InteractionResult.SUCCESS_SERVER;
            }
        }

        return super.interact(player, hand, location);
    }

    private EquipmentSlot getClickedSlot(final Vec3 location) {
        EquipmentSlot slotClicked = EquipmentSlot.MAINHAND;
        boolean small = this.isSmall();
        double clickYPosition = location.y / (this.getScale() * this.getAgeScale());
        EquipmentSlot feet = EquipmentSlot.FEET;
        if (clickYPosition >= 0.1 && clickYPosition < 0.1 + (small ? 0.8 : 0.45) && this.hasItemInSlot(feet)) {
            slotClicked = EquipmentSlot.FEET;
        } else if (clickYPosition >= 0.9 + (small ? 0.3 : 0.0) && clickYPosition < 0.9 + (small ? 1.0 : 0.7) && this.hasItemInSlot(EquipmentSlot.CHEST)) {
            slotClicked = EquipmentSlot.CHEST;
        } else if (clickYPosition >= 0.4 && clickYPosition < 0.4 + (small ? 1.0 : 0.8) && this.hasItemInSlot(EquipmentSlot.LEGS)) {
            slotClicked = EquipmentSlot.LEGS;
        } else if (clickYPosition >= 1.6 && this.hasItemInSlot(EquipmentSlot.HEAD)) {
            slotClicked = EquipmentSlot.HEAD;
        } else if (!this.hasItemInSlot(EquipmentSlot.MAINHAND) && this.hasItemInSlot(EquipmentSlot.OFFHAND)) {
            slotClicked = EquipmentSlot.OFFHAND;
        }

        return slotClicked;
    }

    public boolean isDisabled(final EquipmentSlot slot) {
        return (this.disabledSlots & 1 << slot.getFilterBit(0)) != 0 || slot.getType() == EquipmentSlot.Type.HAND && !this.showArms();
    }

    private boolean swapItem(final Player player, final EquipmentSlot slot, final ItemStack playerItemStack, final InteractionHand hand) {
        ItemStack itemStack = this.getItemBySlot(slot);
        if (!itemStack.isEmpty() && (this.disabledSlots & 1 << slot.getFilterBit(8)) != 0) {
            return false;
        }

        if (itemStack.isEmpty() && (this.disabledSlots & 1 << slot.getFilterBit(16)) != 0) {
            return false;
        }

        // CraftBukkit start
        org.bukkit.inventory.ItemStack armorStandItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack);
        org.bukkit.inventory.ItemStack playerHeldItem = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(playerItemStack);

        org.bukkit.entity.Player player1 = (org.bukkit.entity.Player) player.getBukkitEntity();
        org.bukkit.entity.ArmorStand self = (org.bukkit.entity.ArmorStand) this.getBukkitEntity();

        org.bukkit.inventory.EquipmentSlot slot1 = org.bukkit.craftbukkit.CraftEquipmentSlot.getSlot(slot);
        org.bukkit.inventory.EquipmentSlot hand1 = org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand);
        org.bukkit.event.player.PlayerArmorStandManipulateEvent armorStandManipulateEvent = new org.bukkit.event.player.PlayerArmorStandManipulateEvent(player1, self, playerHeldItem, armorStandItem, slot1, hand1);
        this.level().getCraftServer().getPluginManager().callEvent(armorStandManipulateEvent);

        if (armorStandManipulateEvent.isCancelled()) {
            return true;
        }
        // CraftBukkit end

        if (player.hasInfiniteMaterials() && itemStack.isEmpty() && !playerItemStack.isEmpty()) {
            this.setItemSlot(slot, playerItemStack.copyWithCount(1));
            return true;
        }

        if (playerItemStack.isEmpty() || playerItemStack.getCount() <= 1) {
            this.setItemSlot(slot, playerItemStack);
            player.setItemInHand(hand, itemStack);
            return true;
        }

        if (!itemStack.isEmpty()) {
            return false;
        }

        this.setItemSlot(slot, playerItemStack.split(1));
        return true;
    }

    @Override
    public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
        if (this.isRemoved()) {
            return false;
        }

        if (!level.getGameRules().get(GameRules.MOB_GRIEFING) && source.getEntity() instanceof Mob) {
            return false;
        }

        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            // CraftBukkit start
            if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage)) {
                return false;
            }
            this.kill(level, source); // CraftBukkit
            // CraftBukkit end
            return false;
        }

        if (this.isInvulnerableTo(level, source) /*|| this.invisible*/ || this.isMarker()) { // CraftBukkit
            return false;
        }

        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            // CraftBukkit start
            if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage, true, this.invisible)) {
                return false;
            }
            // CraftBukkit end
            // Paper start - avoid duplicate event call
            org.bukkit.event.entity.EntityDeathEvent event = this.brokenByAnything(level, source);
            if (!event.isCancelled()) this.kill(level, source, false); // CraftBukkit
            // Paper end
            return false;
        }

        if (source.is(DamageTypeTags.IGNITES_ARMOR_STANDS)) {
            // CraftBukkit start
            if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage, true, this.invisible)) {
                return false;
            }
            // CraftBukkit end
            if (this.isOnFire()) {
                this.causeDamage(level, source, 0.15F);
            } else {
                this.igniteForSeconds(5.0F);
            }

            return false;
        } else if (source.is(DamageTypeTags.BURNS_ARMOR_STANDS) && this.getHealth() > 0.5F) {
            // CraftBukkit start
            if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage, true, this.invisible)) {
                return false;
            }
            // CraftBukkit end
            this.causeDamage(level, source, 4.0F);
            return false;
        } else {
            // CraftBukkit start
            if (org.bukkit.craftbukkit.event.CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, damage, true, this.invisible)) {
                return false;
            }
            // CraftBukkit end
            boolean allowIncrementalBreaking = source.is(DamageTypeTags.CAN_BREAK_ARMOR_STAND);
            boolean shouldKill = source.is(DamageTypeTags.ALWAYS_KILLS_ARMOR_STANDS);
            if (!allowIncrementalBreaking && !shouldKill) {
                return false;
            } else if (source.getEntity() instanceof Player player && !player.getAbilities().mayBuild) {
                return false;
            } else {
                if (source.isCreativePlayer()) {
                    this.playBrokenSound();
                    this.showBreakingParticles();
                    this.kill(level, source); // CraftBukkit
                    return true;
                }

                long time = level.getGameTime();
                if (time - this.lastHit > 5L && !shouldKill) {
                    level.broadcastEntityEvent(this, EntityEvent.ARMORSTAND_WOBBLE);
                    this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
                    this.lastHit = time;
                } else {
                    org.bukkit.event.entity.EntityDeathEvent event = this.brokenByPlayer(level, source); // Paper
                    this.showBreakingParticles();
                    if (!event.isCancelled()) this.kill(level, source, false); // Paper - we still need to kill to follow vanilla logic (emit the game event etc...)
                }

                return true;
            }
        }
    }

    @Override
    public void handleEntityEvent(final byte id) {
        if (id == EntityEvent.ARMORSTAND_WOBBLE) {
            if (this.level().isClientSide()) {
                this.level().playLocalSound(this.getX(), this.getY(), this.getZ(), SoundEvents.ARMOR_STAND_HIT, this.getSoundSource(), 0.3F, 1.0F, false);
                this.lastHit = this.level().getGameTime();
            }
        } else {
            super.handleEntityEvent(id);
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(final double distance) {
        double size = this.getBoundingBox().getSize() * 4.0;
        if (Double.isNaN(size) || size == 0.0) {
            size = 4.0;
        }

        size *= 64.0;
        return distance < size * size;
    }

    private void showBreakingParticles() {
        if (this.level() instanceof ServerLevel) {
            ((ServerLevel)this.level())
                .sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                    this.getX(),
                    this.getY(0.6666666666666666),
                    this.getZ(),
                    10,
                    this.getBbWidth() / 4.0F,
                    this.getBbHeight() / 4.0F,
                    this.getBbWidth() / 4.0F,
                    0.05
                );
        }
    }

    private void causeDamage(final ServerLevel level, final DamageSource source, final float dmg) {
        float health = this.getHealth();
        health -= dmg;
        if (health <= 0.5F) {
            // Paper start - avoid duplicate event call
            org.bukkit.event.entity.EntityDeathEvent event = this.brokenByAnything(level, source);
            if (!event.isCancelled()) this.kill(level, source, false); // CraftBukkit
            // Paper end
        } else {
            this.setHealth(health);
            this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
        }
    }

    private org.bukkit.event.entity.EntityDeathEvent brokenByPlayer(final ServerLevel level, final DamageSource source) { // Paper
        ItemStack result = new ItemStack(Items.ARMOR_STAND);
        result.set(DataComponents.CUSTOM_NAME, this.getCustomName());
        this.deathDropItems = new java.util.ArrayList<>(); // Paper
        this.deathDropItems.add(new DefaultDrop(result, stack -> Block.popResource(this.level(), this.blockPosition(), stack))); // CraftBukkit - add to drops // Paper - Restore vanilla drops behavior
        return this.brokenByAnything(level, source); // Paper
    }

    private org.bukkit.event.entity.EntityDeathEvent brokenByAnything(final ServerLevel level, final DamageSource source) { // Paper
        this.playBrokenSound();
        if (this.deathDropItems == null) this.deathDropItems = new java.util.ArrayList<>(); // Paper
        this.dropAllDeathLoot(level, source);

        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            this.postDeathEventTasks.add(() -> this.equipment.set(slot, ItemStack.EMPTY)); // Paper - move equipment removal past event call
            ItemStack itemStack = this.equipment.get(slot); // Paper
            if (!itemStack.isEmpty() && !EnchantmentHelper.has(itemStack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                this.deathDropItems.add(new DefaultDrop(itemStack, stack -> Block.popResource(this.level(), this.blockPosition().above(), stack))); // CraftBukkit - add to drops // Paper - Restore vanilla drops behavior; mirror so we can destroy it later - though this call site was safe & spawn drops correctly}
            }
        }
        // Paper start - call EntityDeathEvent
        org.bukkit.event.entity.EntityDeathEvent deathEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityDeathEvent(level, this, source, this.deathDropItems, true);
        this.deathDropItems = null;
        if (!deathEvent.isCancelled()) {
            this.postDeathEventTasks.forEach(Runnable::run);
        }
        this.postDeathEventTasks.clear();
        return deathEvent;
        // Paper end - call EntityDeathEvent
    }

    private void playBrokenSound() {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ARMOR_STAND_BREAK, this.getSoundSource(), 1.0F, 1.0F);
    }

    @Override
    protected void tickHeadTurn(final float yBodyRotT) {
        this.yBodyRotO = this.yRotO;
        this.yBodyRot = this.getYRot();
    }

    @Override
    public void travel(final Vec3 input) {
        if (this.hasPhysics()) {
            super.travel(input);
        }
    }

    @Override
    public void setYBodyRot(final float yBodyRot) {
        this.yBodyRotO = this.yRotO = yBodyRot;
        this.yHeadRotO = this.yHeadRot = yBodyRot;
    }

    @Override
    public void setYHeadRot(final float yHeadRot) {
        this.yBodyRotO = this.yRotO = yHeadRot;
        this.yHeadRotO = this.yHeadRot = yHeadRot;
    }

    @Override
    protected void updateInvisibilityStatus() {
        this.setInvisible(this.invisible);
    }

    @Override
    public void setInvisible(final boolean invisible) {
        this.invisible = invisible;
        super.setInvisible(invisible);
    }

    @Override
    public boolean isBaby() {
        return this.isSmall();
    }

    // Paper start - Allow ArmorStands not to tick
    @Override
    public void tick() {
        if (!this.canTick) {
            if (this.noTickEquipmentDirty) {
                this.noTickEquipmentDirty = false;
                this.detectEquipmentUpdates();
            }

            return;
        }
        super.tick();
    }
    // Paper end - Allow ArmorStands not to tick

    @Override
    public void kill(final ServerLevel level) {
        // Paper start - pass damage source for event
        this.kill(level, null);
    }

    public void kill(final ServerLevel level, final @Nullable DamageSource source) {
        this.kill(level, source, true);
    }

    public void kill(final ServerLevel level, final @Nullable DamageSource source, final boolean callEvent) {
        if (callEvent && !org.bukkit.craftbukkit.event.CraftEventFactory.callEntityDeathEvent(level, this, (source == null ? this.damageSources().genericKill() : source))) {
            return;
        }
        // Paper end
        this.remove(Entity.RemovalReason.KILLED, org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
        this.gameEvent(GameEvent.ENTITY_DIE);
    }

    @Override
    public boolean ignoreExplosion(final Explosion explosion) {
        return !explosion.shouldAffectBlocklikeEntities() || this.isInvisible();
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return this.isMarker() ? PushReaction.IGNORE : super.getPistonPushReaction();
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return this.isMarker();
    }

    public void setSmall(final boolean value) {
        this.entityData.set(DATA_CLIENT_FLAGS, this.setBit(this.entityData.get(DATA_CLIENT_FLAGS), CLIENT_FLAG_SMALL, value));
    }

    public boolean isSmall() {
        return (this.entityData.get(DATA_CLIENT_FLAGS) & 1) != 0;
    }

    public void setShowArms(final boolean value) {
        this.entityData.set(DATA_CLIENT_FLAGS, this.setBit(this.entityData.get(DATA_CLIENT_FLAGS), CLIENT_FLAG_SHOW_ARMS, value));
    }

    public boolean showArms() {
        return (this.entityData.get(DATA_CLIENT_FLAGS) & 4) != 0;
    }

    public void setNoBasePlate(final boolean value) {
        this.entityData.set(DATA_CLIENT_FLAGS, this.setBit(this.entityData.get(DATA_CLIENT_FLAGS), CLIENT_FLAG_NO_BASEPLATE, value));
    }

    public boolean showBasePlate() {
        return (this.entityData.get(DATA_CLIENT_FLAGS) & 8) == 0;
    }

    public void setMarker(final boolean value) {
        this.entityData.set(DATA_CLIENT_FLAGS, this.setBit(this.entityData.get(DATA_CLIENT_FLAGS), CLIENT_FLAG_MARKER, value));
    }

    public boolean isMarker() {
        return (this.entityData.get(DATA_CLIENT_FLAGS) & 16) != 0;
    }

    private byte setBit(byte data, final int bit, final boolean value) {
        if (value) {
            data = (byte)(data | bit);
        } else {
            data = (byte)(data & ~bit);
        }

        return data;
    }

    public void setHeadPose(final Rotations headPose) {
        this.entityData.set(DATA_HEAD_POSE, headPose);
    }

    public void setBodyPose(final Rotations bodyPose) {
        this.entityData.set(DATA_BODY_POSE, bodyPose);
    }

    public void setLeftArmPose(final Rotations leftArmPose) {
        this.entityData.set(DATA_LEFT_ARM_POSE, leftArmPose);
    }

    public void setRightArmPose(final Rotations rightArmPose) {
        this.entityData.set(DATA_RIGHT_ARM_POSE, rightArmPose);
    }

    public void setLeftLegPose(final Rotations leftLegPose) {
        this.entityData.set(DATA_LEFT_LEG_POSE, leftLegPose);
    }

    public void setRightLegPose(final Rotations rightLegPose) {
        this.entityData.set(DATA_RIGHT_LEG_POSE, rightLegPose);
    }

    public Rotations getHeadPose() {
        return this.entityData.get(DATA_HEAD_POSE);
    }

    public Rotations getBodyPose() {
        return this.entityData.get(DATA_BODY_POSE);
    }

    public Rotations getLeftArmPose() {
        return this.entityData.get(DATA_LEFT_ARM_POSE);
    }

    public Rotations getRightArmPose() {
        return this.entityData.get(DATA_RIGHT_ARM_POSE);
    }

    public Rotations getLeftLegPose() {
        return this.entityData.get(DATA_LEFT_LEG_POSE);
    }

    public Rotations getRightLegPose() {
        return this.entityData.get(DATA_RIGHT_LEG_POSE);
    }

    @Override
    public boolean isPickable() {
        return super.isPickable() && !this.isMarker();
    }

    @Override
    public boolean skipAttackInteraction(final Entity source) {
        return source instanceof Player playerSource && !this.level().mayInteract(playerSource, this.blockPosition());
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    @Override
    public LivingEntity.Fallsounds getFallSounds() {
        return new LivingEntity.Fallsounds(SoundEvents.ARMOR_STAND_FALL, SoundEvents.ARMOR_STAND_FALL);
    }

    @Override
    public @Nullable SoundEvent getHurtSound(final DamageSource source) {
        return SoundEvents.ARMOR_STAND_HIT;
    }

    @Override
    public @Nullable SoundEvent getDeathSound() {
        return SoundEvents.ARMOR_STAND_BREAK;
    }

    @Override
    public void thunderHit(final ServerLevel level, final LightningBolt lightningBolt) {
    }

    @Override
    public boolean isAffectedByPotions() {
        return false;
    }

    @Override
    public void onSyncedDataUpdated(final EntityDataAccessor<?> accessor) {
        if (DATA_CLIENT_FLAGS.equals(accessor)) {
            this.refreshDimensions();
            this.blocksBuilding = !this.isMarker();
        }

        super.onSyncedDataUpdated(accessor);
    }

    @Override
    public boolean attackable() {
        return false;
    }

    @Override
    public EntityDimensions getDefaultDimensions(final Pose pose) {
        return this.getDimensionsMarker(this.isMarker());
    }

    private EntityDimensions getDimensionsMarker(final boolean isMarker) {
        if (isMarker) {
            return MARKER_DIMENSIONS;
        } else {
            return this.isBaby() ? BABY_DIMENSIONS : this.getType().getDimensions();
        }
    }

    @Override
    public Vec3 getLightProbePosition(final float partialTickTime) {
        if (this.isMarker()) {
            AABB box = this.getDimensionsMarker(false).makeBoundingBox(this.position());
            BlockPos probePos = this.blockPosition();
            int brightestLight = Integer.MIN_VALUE;

            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                int blockBrightness = Math.max(this.level().getBrightness(LightLayer.BLOCK, pos), this.level().getBrightness(LightLayer.SKY, pos));
                if (blockBrightness == 15) {
                    return Vec3.atCenterOf(pos);
                }

                if (blockBrightness > brightestLight) {
                    brightestLight = blockBrightness;
                    probePos = pos.immutable();
                }
            }

            return Vec3.atCenterOf(probePos);
        } else {
            return super.getLightProbePosition(partialTickTime);
        }
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(Items.ARMOR_STAND);
    }

    @Override
    public boolean canBeSeenByAnyone() {
        return !this.isInvisible() && !this.isMarker();
    }

    public void setArmorStandPose(final ArmorStand.ArmorStandPose pose) {
        this.setHeadPose(pose.head());
        this.setBodyPose(pose.body());
        this.setLeftArmPose(pose.leftArm());
        this.setRightArmPose(pose.rightArm());
        this.setLeftLegPose(pose.leftLeg());
        this.setRightLegPose(pose.rightLeg());
    }

    public ArmorStand.ArmorStandPose getArmorStandPose() {
        return new ArmorStand.ArmorStandPose(
            this.getHeadPose(), this.getBodyPose(), this.getLeftArmPose(), this.getRightArmPose(), this.getLeftLegPose(), this.getRightLegPose()
        );
    }

    public record ArmorStandPose(Rotations head, Rotations body, Rotations leftArm, Rotations rightArm, Rotations leftLeg, Rotations rightLeg) {
        public static final ArmorStand.ArmorStandPose DEFAULT = new ArmorStand.ArmorStandPose(
            ArmorStand.DEFAULT_HEAD_POSE,
            ArmorStand.DEFAULT_BODY_POSE,
            ArmorStand.DEFAULT_LEFT_ARM_POSE,
            ArmorStand.DEFAULT_RIGHT_ARM_POSE,
            ArmorStand.DEFAULT_LEFT_LEG_POSE,
            ArmorStand.DEFAULT_RIGHT_LEG_POSE
        );
        public static final Codec<ArmorStand.ArmorStandPose> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                    Rotations.CODEC.optionalFieldOf("Head", ArmorStand.DEFAULT_HEAD_POSE).forGetter(ArmorStand.ArmorStandPose::head),
                    Rotations.CODEC.optionalFieldOf("Body", ArmorStand.DEFAULT_BODY_POSE).forGetter(ArmorStand.ArmorStandPose::body),
                    Rotations.CODEC.optionalFieldOf("LeftArm", ArmorStand.DEFAULT_LEFT_ARM_POSE).forGetter(ArmorStand.ArmorStandPose::leftArm),
                    Rotations.CODEC.optionalFieldOf("RightArm", ArmorStand.DEFAULT_RIGHT_ARM_POSE).forGetter(ArmorStand.ArmorStandPose::rightArm),
                    Rotations.CODEC.optionalFieldOf("LeftLeg", ArmorStand.DEFAULT_LEFT_LEG_POSE).forGetter(ArmorStand.ArmorStandPose::leftLeg),
                    Rotations.CODEC.optionalFieldOf("RightLeg", ArmorStand.DEFAULT_RIGHT_LEG_POSE).forGetter(ArmorStand.ArmorStandPose::rightLeg)
                )
                .apply(i, ArmorStand.ArmorStandPose::new)
        );
    }

    // Paper start
    @Override
    public void move(net.minecraft.world.entity.MoverType type, Vec3 movement) {
        if (this.canMove) {
            super.move(type, movement);
        }
    }
    // Paper end
}
