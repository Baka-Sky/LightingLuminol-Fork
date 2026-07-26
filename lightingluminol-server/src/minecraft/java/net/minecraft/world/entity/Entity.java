package net.minecraft.world.entity;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.ImmutableList.Builder;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.floats.FloatArraySet;
import it.unimi.dsi.fastutil.floats.FloatArrays;
import it.unimi.dsi.fastutil.floats.FloatSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.TypedInstance;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SyncedDataHolder;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.BlockUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.debug.DebugEntityBlockIntersection;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Nameable;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class Entity
    implements Nameable,
    EntityAccess,
    ScoreHolder,
    SyncedDataHolder,
    DataComponentGetter,
    ItemOwner,
    SlotProvider,
    DebugValueSource,
    TypedInstance<EntityType<?>>, ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity, ca.spottedleaf.moonrise.patches.entity_tracker.EntityTrackerEntity { // Paper - rewrite chunk system // Paper - optimise entity tracker
    // CraftBukkit start
    private static final int CURRENT_LEVEL = 2;
    static boolean isLevelAtLeast(ValueInput input, int level) {
        return input.getIntOr("Bukkit.updateLevel", CURRENT_LEVEL) >= level;
    }

    // Paper start - Share random for entities to make them more random
    public static RandomSource SHARED_RANDOM = io.papermc.paper.threadedregions.util.ThreadLocalRandomSource.INSTANCE; // Folia - region threading
    // Paper start - replace random
    private static final class RandomRandomSource extends ca.spottedleaf.moonrise.common.util.ThreadUnsafeRandom {
        public RandomRandomSource() {
            this(net.minecraft.world.level.levelgen.RandomSupport.generateUniqueSeed());
        }

        public RandomRandomSource(long seed) {
            super(seed);
        }

        // Paper end - replace random
        private boolean locked = false;

        @Override
        public synchronized void setSeed(long seed) {
            if (locked) {
                LOGGER.error("Ignoring setSeed on Entity.SHARED_RANDOM", new Throwable());
            } else {
                super.setSeed(seed);
                locked = true;
            }
        }

        // Paper - replace random
    }
    // Paper end - Share random for entities to make them more random
    public org.bukkit.event.entity.CreatureSpawnEvent.@Nullable SpawnReason spawnReason; // Paper - Entity#getEntitySpawnReason

    private volatile org.bukkit.craftbukkit.entity.@Nullable CraftEntity bukkitEntity; // Paper - Folia schedulers - volatile

    public org.bukkit.craftbukkit.entity.CraftEntity getBukkitEntity() {
        if (this.bukkitEntity == null) {
            // Paper start - Folia schedulers
            synchronized (this) {
                if (this.bukkitEntity == null) {
                    return this.bukkitEntity = org.bukkit.craftbukkit.entity.CraftEntity.getEntity(this.level.getCraftServer(), this);
                }
            }
            // Paper end - Folia schedulers
        }
        return this.bukkitEntity;
    }
    // Paper start
    public org.bukkit.craftbukkit.entity.@Nullable CraftEntity getBukkitEntityRaw() {
        return this.bukkitEntity;
    }
    // Paper end
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String TAG_ID = "id";
    public static final String TAG_UUID = "UUID";
    public static final String TAG_PASSENGERS = "Passengers";
    public static final String TAG_DATA = "data";
    public static final String TAG_POS = "Pos";
    public static final String TAG_MOTION = "Motion";
    public static final String TAG_ROTATION = "Rotation";
    public static final String TAG_PORTAL_COOLDOWN = "PortalCooldown";
    public static final String TAG_NO_GRAVITY = "NoGravity";
    public static final String TAG_AIR = "Air";
    public static final String TAG_ON_GROUND = "OnGround";
    public static final String TAG_FALL_DISTANCE = "fall_distance";
    public static final String TAG_FIRE = "Fire";
    public static final String TAG_SILENT = "Silent";
    public static final String TAG_GLOWING = "Glowing";
    public static final String TAG_INVULNERABLE = "Invulnerable";
    public static final String TAG_CUSTOM_NAME = "CustomName";
    public static final int INVALID_ENTITY_ID = 0;
    public static final int CONTENTS_SLOT_INDEX = 0;
    public static final int BOARDING_COOLDOWN = 60;
    public static final int TOTAL_AIR_SUPPLY = 300;
    public static final int MAX_ENTITY_TAG_COUNT = 1024;
    private static final Codec<List<String>> TAG_LIST_CODEC = Codec.STRING.sizeLimitedListOf(1024);
    public static final double DEFAULT_NAME_TAG_DISTANCE = 64.0;
    public static final double DEFAULT_BELOW_NAME_DISTANCE = 10.0;
    public static final double MAX_NAME_TAG_DISTANCE = 512.0;
    public static final float DELTA_AFFECTED_BY_BLOCKS_BELOW_0_2 = 0.2F;
    public static final double DELTA_AFFECTED_BY_BLOCKS_BELOW_0_5 = 0.500001;
    public static final double DELTA_AFFECTED_BY_BLOCKS_BELOW_1_0 = 0.999999;
    public static final int BASE_TICKS_REQUIRED_TO_FREEZE = 140;
    public static final int FREEZE_HURT_FREQUENCY = 40;
    public static final int BASE_SAFE_FALL_DISTANCE = 3;
    private static final AABB INITIAL_AABB = new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    private static final double WATER_FLOW_SCALE = 0.014;
    private static final double LAVA_FAST_FLOW_SCALE = 0.007;
    private static final double LAVA_SLOW_FLOW_SCALE = 0.0023333333333333335;
    private static final int MAX_BLOCK_ITERATIONS_ALONG_TRAVEL_PER_TICK = 16;
    private static final double MAX_MOVEMENT_RESETTING_TRACE_DISTANCE = 8.0;
    private static double viewScale = 1.0;
    private final EntityType<?> type;
    private boolean requiresPrecisePosition;
    private int id = 0;
    public boolean blocksBuilding;
    public ImmutableList<Entity> passengers = ImmutableList.of();
    protected int boardingCooldown;
    private @Nullable Entity vehicle;
    private Level level;
    public double xo;
    public double yo;
    public double zo;
    private Vec3 position;
    private BlockPos blockPosition;
    private ChunkPos chunkPosition;
    private Vec3 deltaMovement = Vec3.ZERO;
    private float yRot;
    private float xRot;
    public float yRotO;
    public float xRotO;
    private AABB bb = INITIAL_AABB;
    public boolean onGround;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean verticalCollisionBelow;
    public boolean minorHorizontalCollision;
    public boolean hurtMarked;
    protected Vec3 stuckSpeedMultiplier = Vec3.ZERO;
    private Entity.@Nullable RemovalReason removalReason;
    public static final float DEFAULT_BB_WIDTH = 0.6F;
    public static final float DEFAULT_BB_HEIGHT = 1.8F;
    public float moveDist;
    public float flyDist;
    public double fallDistance;
    private float nextStep = 1.0F;
    public double xOld;
    public double yOld;
    public double zOld;
    public boolean noPhysics;
    public final RandomSource random = meow.bacteriawa.lightingluminol.config.FixesConfig.UseVanillaRandomSource.enabled ? RandomSource.create() : SHARED_RANDOM; // Paper - Share random for entities to make them more random // LightingLuminol - vanilla random
    public int tickCount;
    private int remainingFireTicks;
    private final EntityFluidInteraction fluidInteraction = new EntityFluidInteraction(Set.of(FluidTags.WATER, FluidTags.LAVA));
    protected boolean wasTouchingWater;
    protected boolean wasEyeInWater;
    public int invulnerableTime;
    protected boolean firstTick = true;
    protected final SynchedEntityData entityData;
    protected static final EntityDataAccessor<Byte> DATA_SHARED_FLAGS_ID = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BYTE);
    protected static final int FLAG_ONFIRE = 0;
    private static final int FLAG_SHIFT_KEY_DOWN = 1;
    private static final int FLAG_SPRINTING = 3;
    private static final int FLAG_SWIMMING = 4;
    public static final int FLAG_INVISIBLE = 5;
    protected static final int FLAG_GLOWING = 6;
    protected static final int FLAG_FALL_FLYING = 7;
    private static final EntityDataAccessor<Integer> DATA_AIR_SUPPLY_ID = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<Component>> DATA_CUSTOM_NAME = SynchedEntityData.defineId(
        Entity.class, EntityDataSerializers.OPTIONAL_COMPONENT
    );
    private static final EntityDataAccessor<Boolean> DATA_CUSTOM_NAME_VISIBLE = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_SILENT = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_NO_GRAVITY = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.BOOLEAN);
    protected static final EntityDataAccessor<Pose> DATA_POSE = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.POSE);
    public static final EntityDataAccessor<Integer> DATA_TICKS_FROZEN = SynchedEntityData.defineId(Entity.class, EntityDataSerializers.INT);
    private EntityInLevelCallback levelCallback = EntityInLevelCallback.NULL;
    private final VecDeltaCodec packetPositionCodec = new VecDeltaCodec();
    public boolean needsSync;
    public boolean syncPosition;
    public @Nullable PortalProcessor portalProcess;
    private int portalCooldown;
    private boolean invulnerable;
    protected UUID uuid = Mth.createInsecureUUID(this.random);
    protected String stringUUID = this.uuid.toString();
    private boolean hasGlowingTag;
    private final Set<String> tags = new io.papermc.paper.util.SizeLimitedSet<>(new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>(), MAX_ENTITY_TAG_COUNT); // Paper - fully limit tag size - replace set impl
    private final double[] pistonDeltas = new double[]{0.0, 0.0, 0.0};
    private long pistonDeltasGameTime = Long.MIN_VALUE; // Folia - region threading
    private EntityDimensions dimensions;
    private float eyeHeight;
    public boolean isInPowderSnow;
    public boolean wasInPowderSnow;
    public Optional<BlockPos> mainSupportingBlockPos = Optional.empty();
    private boolean onGroundNoBlocks = false;
    private float crystalSoundIntensity;
    private int lastCrystalSoundPlayTick;
    private boolean hasVisualFire;
    private Vec3 lastKnownSpeed = Vec3.ZERO;
    private @Nullable Vec3 lastKnownPosition;
    public net.kyori.adventure.util.TriState visualFire = net.kyori.adventure.util.TriState.NOT_SET; // Paper - improve visual fire API
    private @Nullable BlockState inBlockState = null;
    public static final int MAX_MOVEMENTS_HANDELED_PER_TICK = 100;
    private final ArrayDeque<Entity.Movement> movementThisTick = new ArrayDeque<>(100);
    private final List<Entity.Movement> finalMovementsThisTick = new ObjectArrayList<>();
    private final LongSet visitedBlocks = new LongOpenHashSet();
    private final InsideBlockEffectApplier.StepBasedCollector insideEffectCollector = new InsideBlockEffectApplier.StepBasedCollector();
    private CustomData customData = CustomData.EMPTY;
    // CraftBukkit start
    public boolean persist = true;
    public boolean visibleByDefault = true;
    public boolean valid;
    public boolean inWorld = false;
    public boolean generation;
    public int maxAirTicks = this.getDefaultMaxAirSupply(); // CraftBukkit - SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
    public org.bukkit.projectiles.@Nullable ProjectileSource projectileSource; // For projectiles only // Paper - Refresh ProjectileSource for projectiles
    public boolean lastDamageCancelled; // SPIGOT-5339, SPIGOT-6252, SPIGOT-6777: Keep track if the event was canceled
    public boolean persistentInvisibility = false;
    public @Nullable BlockPos lastLavaContact;
    // Marks an entity, that it was removed by a plugin via Entity#remove
    // Main use case currently is for SPIGOT-7487, preventing dropping of leash when leash is removed
    public boolean pluginRemoved = false;
    protected int numCollisions = 0; // Paper - Cap entity collisions
    public boolean fromNetherPortal; // Paper - Add option to nerf pigmen from nether portals
    public boolean spawnedViaMobSpawner; // Paper - Yes this name is similar to above, upstream took the better one
    // Paper start
    public @Nullable Vec3 origin;
    public @Nullable UUID originWorld;
    // Paper end
    public boolean freezeLocked = false; // Paper - Freeze Tick Lock API
    public boolean fixedPose = false; // Paper - Expand Pose API
    private final int despawnTime; // Paper - entity despawn time limit
    public int totalEntityAge; // Paper - age-like counter for all entities
    public final io.papermc.paper.entity.activation.ActivationType activationType = io.papermc.paper.entity.activation.ActivationType.activationTypeFor(this); // Paper - EAR 2/tracking ranges
    // Paper start - EAR 2
    public final boolean defaultActivationState;
    public long activatedTick = Integer.MIN_VALUE;
    public boolean isTemporarilyActive;
    public long activatedImmunityTick = Integer.MIN_VALUE;

    public void inactiveTick() {
    }
    // Paper end - EAR 2
    // CraftBukkit end

    // Paper start
    public final AABB getBoundingBoxAt(double x, double y, double z) {
        return this.dimensions.makeBoundingBox(x, y, z);
    }
    // Paper end
    // Paper start - rewrite chunk system
    private final boolean isHardColliding = this.moonrise$isHardCollidingUncached();
    private net.minecraft.server.level.FullChunkStatus chunkStatus;
    private ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData chunkData;
    private int sectionX = Integer.MIN_VALUE;
    private int sectionY = Integer.MIN_VALUE;
    private int sectionZ = Integer.MIN_VALUE;
    private boolean updatingSectionStatus;

    @Override
    public final boolean moonrise$isHardColliding() {
        return this.isHardColliding;
    }

    @Override
    public final net.minecraft.server.level.FullChunkStatus moonrise$getChunkStatus() {
        return this.chunkStatus;
    }

    @Override
    public final void moonrise$setChunkStatus(final net.minecraft.server.level.FullChunkStatus status) {
        this.chunkStatus = status;
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData moonrise$getChunkData() {
        return this.chunkData;
    }

    @Override
    public final void moonrise$setChunkData(final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData chunkData) {
        this.chunkData = chunkData;
    }

    @Override
    public final int moonrise$getSectionX() {
        return this.sectionX;
    }

    @Override
    public final void moonrise$setSectionX(final int x) {
        this.sectionX = x;
    }

    @Override
    public final int moonrise$getSectionY() {
        return this.sectionY;
    }

    @Override
    public final void moonrise$setSectionY(final int y) {
        this.sectionY = y;
    }

    @Override
    public final int moonrise$getSectionZ() {
        return this.sectionZ;
    }

    @Override
    public final void moonrise$setSectionZ(final int z) {
        this.sectionZ = z;
    }

    @Override
    public final boolean moonrise$isUpdatingSectionStatus() {
        return this.updatingSectionStatus;
    }

    @Override
    public final void moonrise$setUpdatingSectionStatus(final boolean to) {
        this.updatingSectionStatus = to;
    }

    @Override
    public final boolean moonrise$hasAnyPlayerPassengers() {
        if (this.passengers.isEmpty()) {
            return false;
        }
        return this.getIndirectPassengersStream().anyMatch((entity) -> entity instanceof Player);
    }
    // Paper end - rewrite chunk system
    // Paper start - optimise collisions
    private static float[] calculateStepHeights(final AABB box, final List<VoxelShape> voxels, final List<AABB> aabbs, final float stepHeight,
                                                final float collidedY) {
        final FloatArraySet ret = new FloatArraySet();

        for (int i = 0, len = voxels.size(); i < len; ++i) {
            final VoxelShape shape = voxels.get(i);

            final double[] yCoords = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape).moonrise$rootCoordinatesY();
            final double yOffset = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)shape).moonrise$offsetY();

            for (final double yUnoffset : yCoords) {
                final double y = yUnoffset + yOffset;

                final float step = (float)(y - box.minY);

                if (step > stepHeight) {
                    break;
                }

                if (step < 0.0f || !(step != collidedY)) {
                    continue;
                }

                ret.add(step);
            }
        }

        for (int i = 0, len = aabbs.size(); i < len; ++i) {
            final AABB shape = aabbs.get(i);

            final float step1 = (float)(shape.minY - box.minY);
            final float step2 = (float)(shape.maxY - box.minY);

            if (!(step1 < 0.0f) && step1 != collidedY && !(step1 > stepHeight)) {
                ret.add(step1);
            }

            if (!(step2 < 0.0f) && step2 != collidedY && !(step2 > stepHeight)) {
                ret.add(step2);
            }
        }

        final float[] steps = ret.toFloatArray();
        FloatArrays.unstableSort(steps);
        return steps;
    }
    // Paper end - optimise collisions
    // Paper start - optimise entity tracker
    private net.minecraft.server.level.ChunkMap.TrackedEntity trackedEntity;

    @Override
    public final net.minecraft.server.level.ChunkMap.TrackedEntity moonrise$getTrackedEntity() {
        return this.trackedEntity;
    }

    @Override
    public final void moonrise$setTrackedEntity(final net.minecraft.server.level.ChunkMap.TrackedEntity trackedEntity) {
        this.trackedEntity = trackedEntity;
    }

    private static void collectIndirectPassengers(final List<Entity> into, final List<Entity> from) {
        for (final Entity passenger : from) {
            into.add(passenger);
            collectIndirectPassengers(into, ((Entity)(Object)passenger).passengers);
        }
    }
    // Paper end - optimise entity tracker
    // Folia start - region ticking
    public void updateTicks(long fromTickOffset, long fromRedstoneTimeOffset) {
        if (this.activatedTick != Integer.MIN_VALUE) {
            this.activatedTick += fromTickOffset;
        }
        if (this.activatedImmunityTick != Integer.MIN_VALUE) {
            this.activatedImmunityTick += fromTickOffset;
        }
        if (this.pistonDeltasGameTime != Long.MIN_VALUE) {
            this.pistonDeltasGameTime += fromRedstoneTimeOffset;
        }
    }

    public boolean canBeSpectated() {
        return !this.getBukkitEntity().taskScheduler.isRetiredOffThread();
    }
    // Folia end - region ticking

    public Entity(final EntityType<?> type, final Level level) {
        this.type = type;
        this.level = level;
        this.id = level.getNextEntityId();
        this.dimensions = type.getDimensions();
        this.position = Vec3.ZERO;
        this.blockPosition = BlockPos.ZERO;
        this.chunkPosition = ChunkPos.ZERO;
        // Paper start - EAR 2
        if (level != null) {
            this.defaultActivationState = io.papermc.paper.entity.activation.ActivationRange.initializeEntityActivationState(this, level.spigotConfig);
        } else {
            this.defaultActivationState = false;
        }
        // Paper end - EAR 2
        SynchedEntityData.Builder entityDataBuilder = new SynchedEntityData.Builder(this);
        entityDataBuilder.define(DATA_SHARED_FLAGS_ID, (byte)0);
        entityDataBuilder.define(DATA_AIR_SUPPLY_ID, this.getMaxAirSupply());
        entityDataBuilder.define(DATA_CUSTOM_NAME_VISIBLE, false);
        entityDataBuilder.define(DATA_CUSTOM_NAME, Optional.empty());
        entityDataBuilder.define(DATA_SILENT, false);
        entityDataBuilder.define(DATA_NO_GRAVITY, false);
        entityDataBuilder.define(DATA_POSE, Pose.STANDING);
        entityDataBuilder.define(DATA_TICKS_FROZEN, 0);
        this.defineSynchedData(entityDataBuilder);
        this.entityData = entityDataBuilder.build();
        this.setPos(0.0, 0.0, 0.0);
        this.eyeHeight = this.dimensions.eyeHeight();
        this.despawnTime = level == null || type == EntityTypes.PLAYER ? -1 : level.paperConfig().entities.spawning.despawnTime.getOrDefault(type, io.papermc.paper.configuration.type.number.IntOr.Disabled.DISABLED).or(-1); // Paper - entity despawn time limit
    }

    public boolean isColliding(final BlockPos pos, final BlockState state) {
        VoxelShape movedBlockShape = state.getCollisionShape(this.level(), pos, CollisionContext.of(this)).move(pos);
        return Shapes.joinIsNotEmpty(movedBlockShape, Shapes.create(this.getBoundingBox()), BooleanOp.AND);
    }

    public int getTeamColor() {
        Team team = this.getTeam();
        return team != null && team.getColor().isPresent() ? team.getColor().get().rgb() : 16777215;
    }

    // CraftBukkit start - SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
    public int getDefaultMaxAirSupply() {
        return Entity.TOTAL_AIR_SUPPLY;
    }
    // CraftBukkit end

    public boolean isSpectator() {
        return false;
    }

    public boolean canInteractWithLevel() {
        return this.isAlive() && !this.isRemoved() && !this.isSpectator();
    }

    public final void unRide() {
        if (this.isVehicle()) {
            this.ejectPassengers();
        }

        if (this.isPassenger()) {
            this.stopRiding();
        }
    }

    public void syncPacketPositionCodec(final double x, final double y, final double z) {
        this.packetPositionCodec.setBase(new Vec3(x, y, z));
    }

    public VecDeltaCodec getPositionCodec() {
        return this.packetPositionCodec;
    }

    public EntityType<?> getType() {
        return this.type;
    }

    @Override
    public Holder<EntityType<?>> typeHolder() {
        return this.type.builtInRegistryHolder();
    }

    public boolean getRequiresPrecisePosition() {
        return this.requiresPrecisePosition;
    }

    public void setRequiresPrecisePosition(final boolean requiresPrecisePosition) {
        this.requiresPrecisePosition = requiresPrecisePosition;
    }

    @Override
    public int getId() {
        if (this.id == 0) {
            throw new IllegalStateException("Tried to access entity ID before ID assignment");
        } else {
            return this.id;
        }
    }

    public void setId(final int id) {
        this.id = id;
    }

    public Set<String> entityTags() {
        return this.tags;
    }

    public boolean addTag(final String tag) {
        return this.tags.add(tag); // Paper - fully limit tag size - replace set impl
    }

    public boolean removeTag(final String tag) {
        return this.tags.remove(tag);
    }

    public void kill(final ServerLevel level) {
        this.remove(Entity.RemovalReason.KILLED, org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
        this.gameEvent(GameEvent.ENTITY_DIE);
    }

    public final void discard() {
        // CraftBukkit start - add Bukkit remove cause
        this.discard(null);
    }

    public final void discard(org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) {
        this.remove(Entity.RemovalReason.DISCARDED, cause);
        // CraftBukkit end
    }

    protected abstract void defineSynchedData(SynchedEntityData.Builder entityData);

    public SynchedEntityData getEntityData() {
        return this.entityData;
    }

    // CraftBukkit start
    public void refreshEntityData(ServerPlayer to) {
        List<SynchedEntityData.DataValue<?>> values = this.entityData.packAll(); // Paper - Update EVERYTHING not just not default

        if (to.getBukkitEntity().canSee(this.getBukkitEntity())) { // Paper
            to.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(this.getId(), values));
        }
    }
    // CraftBukkit end
    // Paper start
    // This method should only be used if the data of an entity could have become desynced
    // due to interactions on the client.
    public void resendPossiblyDesyncedEntityData(ServerPlayer player) {
        if (player.getBukkitEntity().canSee(this.getBukkitEntity())) {
            ServerLevel level = (ServerLevel) this.level();
            net.minecraft.server.level.ChunkMap.TrackedEntity tracker = this.moonrise$getTrackedEntity(); // Folia - region threading
            if (tracker == null) {
                return;
            }
            final ServerEntity serverEntity = tracker.serverEntity;
            final List<Packet<? super ClientGamePacketListener>> list = new ArrayList<>();
            serverEntity.sendPairingData(player, list::add);
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundBundlePacket(list));
        }
    }

    // This method allows you to specifically resend certain data accessors to the client
    public void resendPossiblyDesyncedDataValues(List<EntityDataAccessor<?>> accessors, ServerPlayer to) {
        if (!to.getBukkitEntity().canSee(this.getBukkitEntity())) {
            return;
        }

        final List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(accessors.size());
        for (final EntityDataAccessor<?> accessor : accessors) {
            final SynchedEntityData.DataItem<?> item = this.entityData.getItem(accessor);
            values.add(item.value());
        }

        to.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(this.id, values));
    }
    // Paper end

    @Override
    public boolean equals(final Object obj) {
        return obj instanceof Entity entity && entity.getId() == this.getId();
    }

    @Override
    public int hashCode() {
        return this.getId();
    }

    public void remove(final Entity.RemovalReason reason) {
        // CraftBukkit start - add Bukkit remove cause
        this.remove(reason, null);
    }

    public void remove(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause eventCause) {
        this.setRemoved(reason, eventCause);
        // CraftBukkit end
    }

    public void onClientRemoval() {
    }

    public void onRemoval(final Entity.RemovalReason reason) {
    }

    public void setPose(final Pose pose) {
        if (this.fixedPose) return; // Paper - Expand Pose API
        // CraftBukkit start
        if (pose == this.getPose()) {
            return;
        }
        if (!this.generation) {
            this.level.getCraftServer().getPluginManager().callEvent(new org.bukkit.event.entity.EntityPoseChangeEvent(this.getBukkitEntity(), org.bukkit.entity.Pose.values()[pose.ordinal()]));
        }
        // CraftBukkit end
        this.entityData.set(DATA_POSE, pose);
    }

    public Pose getPose() {
        return this.entityData.get(DATA_POSE);
    }

    public boolean hasPose(final Pose pose) {
        return this.getPose() == pose;
    }

    public boolean closerThan(final Entity other, final double distance) {
        return this.position().closerThan(other.position(), distance);
    }

    public boolean closerThan(final Entity other, final double distanceXZ, final double distanceY) {
        double dx = other.getX() - this.getX();
        double dy = other.getY() - this.getY();
        double dz = other.getZ() - this.getZ();
        return Mth.lengthSquared(dx, dz) < Mth.square(distanceXZ) && Mth.square(dy) < Mth.square(distanceY);
    }

    // CraftBukkit start - yaw was sometimes set to NaN, so we need to set it back to 0
    public void setRot(float yRot, float xRot) {
        if (Float.isNaN(yRot)) {
            yRot = 0;
        }

        if (yRot == Float.POSITIVE_INFINITY || yRot == Float.NEGATIVE_INFINITY) {
            if (this instanceof ServerPlayer) {
                this.level.getCraftServer().getLogger().warning(this.getScoreboardName() + " was caught trying to crash the server with an invalid yaw");
                ((org.bukkit.craftbukkit.entity.CraftPlayer) this.getBukkitEntity()).kickPlayer("Infinite yaw (Hacking?)");
            }
            yRot = 0;
        }

        // pitch was sometimes set to NaN, so we need to set it back to 0
        if (Float.isNaN(xRot)) {
            xRot = 0;
        }

        if (xRot == Float.POSITIVE_INFINITY || xRot == Float.NEGATIVE_INFINITY) {
            if (this instanceof ServerPlayer) {
                this.level.getCraftServer().getLogger().warning(this.getScoreboardName() + " was caught trying to crash the server with an invalid pitch");
                ((org.bukkit.craftbukkit.entity.CraftPlayer) this.getBukkitEntity()).kickPlayer("Infinite pitch (Hacking?)");
            }
            xRot = 0;
        }
        // CraftBukkit end
        this.setYRot(yRot % 360.0F);
        this.setXRot(xRot % 360.0F);
    }

    public final void setPos(final Vec3 pos) {
        this.setPos(pos.x(), pos.y(), pos.z());
    }

    public void setPos(final double x, final double y, final double z) {
        this.setPosRaw(x, y, z, true); // Paper - Block invalid positions and bounding box; force update
        // this.setBoundingBox(this.makeBoundingBox()); // Paper - Block invalid positions and bounding box; move into setPosRaw
    }

    protected final AABB makeBoundingBox() {
        return this.makeBoundingBox(this.position);
    }

    protected AABB makeBoundingBox(final Vec3 position) {
        return this.dimensions.makeBoundingBox(position);
    }

    protected void reapplyPosition() {
        this.lastKnownPosition = null;
        this.setPos(this.position.x, this.position.y, this.position.z);
    }

    public void turn(final double xo, final double yo) {
        float xDelta = (float)yo * 0.15F;
        float yDelta = (float)xo * 0.15F;
        this.setXRot(this.getXRot() + xDelta);
        this.setYRot(this.getYRot() + yDelta);
        this.setXRot(Mth.clamp(this.getXRot(), -90.0F, 90.0F));
        this.xRotO += xDelta;
        this.yRotO += yDelta;
        this.xRotO = Mth.clamp(this.xRotO, -90.0F, 90.0F);
        if (this.vehicle != null) {
            this.vehicle.onPassengerTurned(this);
        }
    }

    public void updateDataBeforeSync() {
    }

    public void tick() {
        // Paper start - entity despawn time limit
        if (this.despawnTime >= 0 && this.totalEntityAge >= this.despawnTime) {
            this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN);
            return;
        }
        // Paper end - entity despawn time limit
        this.baseTick();
    }

    // CraftBukkit start
    public void postTick() {
        // No clean way to break out of ticking once the entity has been copied to a new world, so instead we move the portalling later in the tick cycle
        if (!(this instanceof ServerPlayer) && this.isAlive()) { // Paper - don't attempt to teleport dead entities
            //this.handlePortal(); // Folia - region threading
        }
    }
    // CraftBukkit end

    public void baseTick() {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("entityBaseTick");
        if (this.firstTick && this instanceof net.minecraft.world.entity.NeutralMob neutralMob) neutralMob.tickInitialPersistentAnger(this.level); // Paper - Prevent entity loading causing async lookups
        this.computeSpeed();
        this.inBlockState = null;
        if (this.isPassenger() && this.getVehicle().isRemoved()) {
            this.stopRiding();
        }

        if (this.boardingCooldown > 0) {
            this.boardingCooldown--;
        }

        //if (this instanceof ServerPlayer) this.handlePortal(); // CraftBukkit - Moved up to postTick // Folia - region threading - ONLY allow in postTick()
        if (this.canSpawnSprintParticle()) {
            this.spawnSprintParticle();
        }

        this.wasInPowderSnow = this.isInPowderSnow;
        this.isInPowderSnow = false;
        this.wasEyeInWater = this.isEyeInFluid(FluidTags.WATER);
        this.updateFluidInteraction();
        this.updateSwimming();
        if (this.level() instanceof ServerLevel serverLevel) {
            if (this.remainingFireTicks > 0) {
                if (this.fireImmune()) {
                    this.clearFire();
                } else {
                    if (this.remainingFireTicks % 20 == 0 && !this.isInLava()) {
                        this.hurtServer(serverLevel, this.damageSources().onFire(), 1.0F);
                    }

                    this.setRemainingFireTicks(this.remainingFireTicks - 1);
                }
            }
        } else {
            this.clearFire();
        }

        if (this.isInLava()) {
            this.fallDistance *= 0.5;
            // CraftBukkit start
        } else {
            this.lastLavaContact = null;
            // CraftBukkit end
        }

        this.checkBelowWorld();
        if (!this.level().isClientSide()) {
            this.setSharedFlagOnFire(this.remainingFireTicks > 0);
        }

        this.firstTick = false;
        if (this.level() instanceof ServerLevel serverLevel && this instanceof Leashable) {
            Leashable.tickLeash(serverLevel, (Entity & Leashable)this);
        }

        profiler.pop();
    }

    protected void computeSpeed() {
        if (this.lastKnownPosition == null) {
            this.lastKnownPosition = this.position();
        }

        this.lastKnownSpeed = this.position().subtract(this.lastKnownPosition);
        this.lastKnownPosition = this.position();
    }

    public void setSharedFlagOnFire(final boolean value) {
        this.setSharedFlag(FLAG_ONFIRE, this.visualFire.toBooleanOrElse(value)); // Paper - improve visual fire API
    }

    public void checkBelowWorld() {
        if (!this.level.getWorld().isVoidDamageEnabled()) return; // Paper - check if void damage is enabled on the world
        // Paper start - Configurable nether ceiling damage
        if (this.getY() < (this.level.getMinY() + this.level.getWorld().getVoidDamageMinBuildHeightOffset()) || (this.level.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER // Paper - use configured min build height offset
            && this.level.paperConfig().environment.netherCeilingVoidDamageHeight.test(v -> this.getY() >= v)
            && (!(this instanceof Player player) || !player.getAbilities().invulnerable))) {
            // Paper end - Configurable nether ceiling damage
            this.onBelowWorld();
        }
    }

    public void setPortalCooldown() {
        this.portalCooldown = this.getDimensionChangingDelay();
    }

    public void setPortalCooldown(final int portalCooldown) {
        this.portalCooldown = portalCooldown;
    }

    public int getPortalCooldown() {
        return this.portalCooldown;
    }

    public boolean isOnPortalCooldown() {
        return this.portalCooldown > 0;
    }

    protected void processPortalCooldown() {
        if (this.isOnPortalCooldown()) {
            this.portalCooldown--;
        }
    }

    public void lavaIgnite() {
        // Paper start - track lava contact
        this.lavaIgnite(this.lastLavaContact); // fallback for minecarts if defined
    }

    public void lavaIgnite(@Nullable BlockPos pos) {
        // Paper end - track lava contact
        if (!this.fireImmune()) {
            // CraftBukkit start - Fallen in lava TODO: this event spams!
            if (this instanceof net.minecraft.world.entity.LivingEntity && this.remainingFireTicks <= 0) {
                // not on fire yet
                org.bukkit.block.Block damager = pos == null ? null : org.bukkit.craftbukkit.block.CraftBlock.at(this.level, pos);
                org.bukkit.entity.Entity damagee = this.getBukkitEntity();
                org.bukkit.event.entity.EntityCombustEvent combustEvent = new org.bukkit.event.entity.EntityCombustByBlockEvent(damager, damagee, 15.0F);

                if (combustEvent.callEvent()) {
                    this.igniteForSeconds(combustEvent.getDuration(), false);
                }
            } else {
                // This will be called every single tick the entity is in lava, so don't throw an event
                this.igniteForSeconds(15.0F, false);
            }
            // CraftBukkit end
        }
    }

    public void lavaHurt() {
        // Paper start - track lava contact
        this.lavaHurt(this.lastLavaContact); // fallback for minecarts if defined
    }

    public void lavaHurt(@Nullable BlockPos pos) {
        // Paper end - track lava contact
        if (!this.fireImmune()) {
            if (this.level() instanceof ServerLevel serverLevel
                && this.hurtServer(serverLevel, this.damageSources().lava().eventBlockDamager(this.level, pos), 4.0F) // CraftBukkit - we also don't throw an event unless the object in lava is living, to save on some event calls
                && this.shouldPlayLavaHurtSound()
                && !this.isSilent()) {
                serverLevel.playSound(
                    null, this.getX(), this.getY(), this.getZ(), SoundEvents.GENERIC_BURN, this.getSoundSource(), 0.4F, 2.0F + this.random.nextFloat() * 0.4F
                );
            }
        }
    }

    protected boolean shouldPlayLavaHurtSound() {
        return true;
    }

    public final void igniteForSeconds(final float numberOfSeconds) {
        // CraftBukkit start
        this.igniteForSeconds(numberOfSeconds, true);
    }

    public final void igniteForSeconds(float numberOfSeconds, final boolean callEvent) {
        if (callEvent) {
            org.bukkit.event.entity.EntityCombustEvent event = new org.bukkit.event.entity.EntityCombustEvent(this.getBukkitEntity(), numberOfSeconds);
            if (!event.callEvent()) {
                return;
            }

            numberOfSeconds = event.getDuration();
        }
        // CraftBukkit end
        this.igniteForTicks(Mth.floor(numberOfSeconds * 20.0F));
    }

    public void igniteForTicks(final int numberOfTicks) {
        if (this.remainingFireTicks < numberOfTicks) {
            this.setRemainingFireTicks(numberOfTicks);
        }

        this.clearFreeze();
    }

    public void setRemainingFireTicks(final int remainingTicks) {
        this.remainingFireTicks = remainingTicks;
    }

    public int getRemainingFireTicks() {
        return this.remainingFireTicks;
    }

    public void clearFire() {
        this.setRemainingFireTicks(Math.min(0, this.getRemainingFireTicks()));
    }

    protected void onBelowWorld() {
        this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.OUT_OF_WORLD); // CraftBukkit - add Bukkit remove cause
    }

    public boolean isFree(final double xa, final double ya, final double za) {
        return this.isFree(this.getBoundingBox().move(xa, ya, za));
    }

    private boolean isFree(final AABB box) {
        return this.level().noCollision(this, box) && !this.level().containsAnyLiquid(box);
    }

    public void setOnGround(final boolean onGround) {
        this.onGround = onGround;
        this.checkSupportingBlock(onGround, null);
    }

    public void setOnGroundWithMovement(final boolean onGround, final Vec3 movement) {
        this.setOnGroundWithMovement(onGround, this.horizontalCollision, movement);
    }

    public void setOnGroundWithMovement(final boolean onGround, final boolean horizontalCollision, final Vec3 movement) {
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
        this.checkSupportingBlock(onGround, movement);
    }

    public boolean isSupportedBy(final BlockPos pos) {
        return this.mainSupportingBlockPos.isPresent() && this.mainSupportingBlockPos.get().equals(pos);
    }

    protected void checkSupportingBlock(final boolean onGround, final @Nullable Vec3 movement) {
        if (onGround) {
            AABB boundingBox = this.getBoundingBox();
            AABB testArea = new AABB(boundingBox.minX, boundingBox.minY - 1.0E-6, boundingBox.minZ, boundingBox.maxX, boundingBox.minY, boundingBox.maxZ);
            Optional<BlockPos> supportingBlock = this.level.findSupportingBlock(this, testArea);
            if (supportingBlock.isPresent() || this.onGroundNoBlocks) {
                this.mainSupportingBlockPos = supportingBlock;
            } else if (movement != null) {
                AABB onGroundCollisionTestArea = testArea.move(-movement.x, 0.0, -movement.z);
                supportingBlock = this.level.findSupportingBlock(this, onGroundCollisionTestArea);
                this.mainSupportingBlockPos = supportingBlock;
            }

            this.onGroundNoBlocks = supportingBlock.isEmpty();
        } else {
            this.onGroundNoBlocks = false;
            if (this.mainSupportingBlockPos.isPresent()) {
                this.mainSupportingBlockPos = Optional.empty();
            }
        }
    }

    public boolean onGround() {
        return this.onGround;
    }

    // Paper start - detailed watchdog information
    public final Object posLock = new Object(); // Paper - log detailed entity tick information

    @Nullable
    private Vec3 moveVector;
    private double moveStartX;
    private double moveStartY;
    private double moveStartZ;
    // Paper end - detailed watchdog information

    public void move(final MoverType moverType, Vec3 delta) {
        final Vec3 originalMovement = delta; // Paper - Expose pre-collision velocity
        // Paper start - detailed watchdog information
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread("Cannot move an entity off-main");
        synchronized (this.posLock) {
            this.moveStartX = this.getX();
            this.moveStartY = this.getY();
            this.moveStartZ = this.getZ();
            this.moveVector = delta;
        }
        // LightingLuminol start - fix high velocity moving
        if (meow.bacteriawa.lightingluminol.config.FixesConfig.FixHighVelocityIssue.enabled && ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()){
            var finalPosition = delta.add(this.position);
            if (!Double.isNaN(finalPosition.x) && !Double.isNaN(finalPosition.y) && !Double.isNaN(finalPosition.z)) {
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.level,finalPosition)) {
                    throw new meow.bacteriawa.lightingluminol.core.EntityMoveOutOfRegionException(this, delta, moverType);
                }
            }
        }
        // LightingLuminol end
        try {
        // Paper end - detailed watchdog information
        if (this.noPhysics) {
            this.setPos(this.getX() + delta.x, this.getY() + delta.y, this.getZ() + delta.z);
            this.horizontalCollision = false;
            this.verticalCollision = false;
            this.verticalCollisionBelow = false;
            this.minorHorizontalCollision = false;
        } else {
            if (moverType == MoverType.PISTON) {
                delta = this.limitPistonMovement(delta);
                // Paper start - EAR 2
                this.activatedTick = Math.max(this.activatedTick, io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() + 20); // Folia - region threading
                this.activatedImmunityTick = Math.max(this.activatedImmunityTick, io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() + 20); // Folia - region threading
                // Paper end - EAR 2
                if (delta.equals(Vec3.ZERO)) {
                    return;
                }
            }

            ProfilerFiller profiler = Profiler.get();
            profiler.push("move");
            if (this.stuckSpeedMultiplier.lengthSqr() > 1.0E-7) {
                if (moverType != MoverType.PISTON) {
                    delta = delta.multiply(this.stuckSpeedMultiplier);
                }

                this.stuckSpeedMultiplier = Vec3.ZERO;
                this.setDeltaMovement(Vec3.ZERO);
            }
            // Paper start - ignore movement changes while inactive.
            if (isTemporarilyActive && !(this instanceof ItemEntity) && delta == getDeltaMovement() && moverType == MoverType.SELF) {
                setDeltaMovement(Vec3.ZERO);
                profiler.pop();
                return;
            }
            // Paper end

            delta = this.maybeBackOffFromEdge(delta, moverType);
            Vec3 movement = this.collide(delta);
            double movementLength = movement.lengthSqr();
            if (movementLength > 1.0E-7 || delta.lengthSqr() - movementLength < 1.0E-7) {
                if (this.fallDistance != 0.0 && movementLength >= 1.0) {
                    double checkDistance = Math.min(movement.length(), 8.0);
                    Vec3 checkTo = this.position().add(movement.normalize().scale(checkDistance));
                    BlockHitResult hitResult = this.level()
                        .clip(new ClipContext(this.position(), checkTo, ClipContext.Block.FALLDAMAGE_RESETTING, ClipContext.Fluid.WATER, this));
                    if (hitResult.getType() != HitResult.Type.MISS) {
                        this.resetFallDistance();
                    }
                }

                Vec3 pos = this.position();
                Vec3 newPosition = pos.add(movement);
                this.addMovementThisTick(new Entity.Movement(pos, newPosition, delta));
                this.setPos(newPosition);
            }

            profiler.pop();
            profiler.push("rest");
            boolean xCollision = !Mth.equal(delta.x, movement.x);
            boolean zCollision = !Mth.equal(delta.z, movement.z);
            this.horizontalCollision = xCollision || zCollision;
            boolean movedVertically = Math.abs(delta.y) > 0.0;
            if (movedVertically || this.isLocalInstanceAuthoritative()) {
                this.verticalCollision = delta.y != movement.y;
                this.verticalCollisionBelow = this.verticalCollision && delta.y < 0.0;
                this.setOnGroundWithMovement(this.verticalCollisionBelow, this.horizontalCollision, movement);
            }

            if (this.horizontalCollision) {
                this.minorHorizontalCollision = this.isHorizontalCollisionMinor(movement);
            } else {
                this.minorHorizontalCollision = false;
            }

            BlockPos effectPos = this.getOnPosLegacy();
            BlockState effectState = this.level().getBlockState(effectPos);
            if (this.isLocalInstanceAuthoritative()) {
                this.checkFallDamage(movement.y, this.onGround(), effectState, effectPos);
            }

            if (this.isRemoved()) {
                profiler.pop();
            } else {
                if (this.canSimulateMovement() && (movedVertically && this.verticalCollision || this.horizontalCollision)) {
                    this.restituteMovementAfterCollisions(effectState, xCollision, zCollision, movement);
                }
                // CraftBukkit start
                if (this.horizontalCollision && this.getBukkitEntity() instanceof org.bukkit.entity.Vehicle) {
                    org.bukkit.entity.Vehicle vehicle = (org.bukkit.entity.Vehicle) this.getBukkitEntity();
                    org.bukkit.block.Block block = this.level.getWorld().getBlockAt(Mth.floor(this.getX()), Mth.floor(this.getY()), Mth.floor(this.getZ()));

                    if (delta.x > movement.x) {
                        block = block.getRelative(org.bukkit.block.BlockFace.EAST);
                    } else if (delta.x < movement.x) {
                        block = block.getRelative(org.bukkit.block.BlockFace.WEST);
                    } else if (delta.z > movement.z) {
                        block = block.getRelative(org.bukkit.block.BlockFace.SOUTH);
                    } else if (delta.z < movement.z) {
                        block = block.getRelative(org.bukkit.block.BlockFace.NORTH);
                    }

                    if (!block.getType().isAir()) {
                        org.bukkit.event.vehicle.VehicleBlockCollisionEvent event = new org.bukkit.event.vehicle.VehicleBlockCollisionEvent(vehicle, block, org.bukkit.craftbukkit.util.CraftVector.toBukkit(originalMovement)); // Paper - Expose pre-collision velocity
                        event.callEvent();
                    }
                }
                // CraftBukkit end

                if (!this.level().isClientSide() || this.isLocalInstanceAuthoritative()) {
                    Entity.MovementEmission emission = this.getMovementEmission();
                    if (emission.emitsAnything() && !this.isPassenger()) {
                        this.applyMovementEmissionAndPlaySound(emission, movement, effectPos, effectState);
                    }
                }

                float blockSpeedFactor = this.getBlockSpeedFactor();
                this.setDeltaMovement(this.getDeltaMovement().multiply(blockSpeedFactor, 1.0, blockSpeedFactor));
                profiler.pop();
            }
        }
        // Paper start - detailed watchdog information
        } finally {
            synchronized (this.posLock) { // Paper
                this.moveVector = null;
            } // Paper
        }
        // Paper end - detailed watchdog information
    }

    private void restituteMovementAfterCollisions(final BlockState effectState, final boolean xCollision, final boolean zCollision, final Vec3 movement) {
        double restitution = this.isSuppressingBounce() ? 0.0 : this.getEntityBounciness();
        Vec3 currentMovement = this.getDeltaMovement();
        Vec3 movementAfterBounce = currentMovement;
        if (xCollision) {
            movementAfterBounce = movementAfterBounce.with(Direction.Axis.X, -currentMovement.x * restitution);
        }

        if (zCollision) {
            movementAfterBounce = movementAfterBounce.with(Direction.Axis.Z, -currentMovement.z * restitution);
        }

        boolean bounced = restitution > 0.0 && (xCollision || zCollision);
        if (this.verticalCollision) {
            if (this.verticalCollisionBelow) {
                restitution = !(-currentMovement.y < this.getEffectiveGravity()) && !this.isSuppressingBounce() && !effectState.is(BlockTags.SUPPRESSES_BOUNCE)
                    ? Math.max(restitution, this.getBlockBounciness(effectState.getBlock()))
                    : 0.0;
            }

            double gravityCompensation;
            double effectiveDrag;
            if (restitution > 0.0) {
                double portionWithMovement = movement.y / currentMovement.y;
                gravityCompensation = portionWithMovement * this.getEffectiveGravity();
                effectiveDrag = Mth.lerp(portionWithMovement, 1.0, this.getAirDrag());
                bounced = true;
            } else {
                gravityCompensation = 0.0;
                effectiveDrag = 1.0;
            }

            movementAfterBounce = movementAfterBounce.with(Direction.Axis.Y, (gravityCompensation - currentMovement.y) * effectiveDrag * restitution);
        }

        if (bounced) {
            this.gameEvent(GameEvent.BOUNCE);
            this.syncPosition = true;
        }

        this.setDeltaMovement(movementAfterBounce);
    }

    private double getBlockBounciness(final Block onBlock) {
        float blockBounciness = onBlock.getBounceRestitution();
        if (!(this instanceof LivingEntity)) {
            blockBounciness *= 0.8F;
        }

        return blockBounciness;
    }

    protected double getEntityBounciness() {
        return 0.0;
    }

    protected double getEffectiveGravity() {
        return this.getGravity();
    }

    protected boolean omnidirectionalAirMover() {
        return false;
    }

    private void applyMovementEmissionAndPlaySound(
        final Entity.MovementEmission emission, final Vec3 clippedMovement, final BlockPos effectPos, final BlockState effectState
    ) {
        float moveDistScale = 0.6F;
        float movedDistance = (float)(clippedMovement.length() * 0.6F);
        float horizontalMovedDistance = (float)(clippedMovement.horizontalDistance() * 0.6F);
        BlockPos supportingPos = this.getOnPos();
        BlockState supportingState = this.level().getBlockState(supportingPos);
        boolean climbing = this.isStateClimbable(supportingState);
        this.moveDist += climbing ? movedDistance : horizontalMovedDistance;
        this.flyDist += movedDistance;
        if (this.moveDist > this.nextStep && !supportingState.isAir()) {
            boolean onlyEffectStateEmittions = supportingPos.equals(effectPos);
            boolean producedSideEffects = this.vibrationAndSoundEffectsFromBlock(
                effectPos, effectState, emission.emitsSounds(), onlyEffectStateEmittions, clippedMovement
            );
            if (!onlyEffectStateEmittions) {
                producedSideEffects |= this.vibrationAndSoundEffectsFromBlock(supportingPos, supportingState, false, emission.emitsEvents(), clippedMovement);
            }

            if (producedSideEffects) {
                this.nextStep = this.nextStep();
            } else if (this.isInWater()) {
                this.nextStep = this.nextStep();
                if (emission.emitsSounds()) {
                    this.waterSwimSound();
                }

                if (emission.emitsEvents()) {
                    this.gameEvent(GameEvent.SWIM);
                }
            }
        } else if (supportingState.isAir()) {
            this.processFlappingMovement();
        }
    }

    protected void applyEffectsFromBlocks() {
        this.finalMovementsThisTick.clear();
        this.finalMovementsThisTick.addAll(this.movementThisTick);
        this.movementThisTick.clear();
        if (this.finalMovementsThisTick.isEmpty()) {
            this.finalMovementsThisTick.add(new Entity.Movement(this.oldPosition(), this.position()));
        } else if (this.finalMovementsThisTick.getLast().to.distanceToSqr(this.position()) > 9.9999994E-11F) {
            this.finalMovementsThisTick.add(new Entity.Movement(this.finalMovementsThisTick.getLast().to, this.position()));
        }

        this.applyEffectsFromBlocks(this.finalMovementsThisTick);
    }

    protected void applyEffectsFromBlocksForLastMovements() {
        this.applyEffectsFromBlocks(this.finalMovementsThisTick);
    }

    private void addMovementThisTick(final Entity.Movement movement) {
        if (this.movementThisTick.size() >= 100) {
            Entity.Movement first = this.movementThisTick.removeFirst();
            Entity.Movement second = this.movementThisTick.removeFirst();
            Entity.Movement combined = new Entity.Movement(first.from(), second.to());
            this.movementThisTick.addFirst(combined);
        }

        this.movementThisTick.add(movement);
    }

    public void removeLatestMovementRecording() {
        if (!this.movementThisTick.isEmpty()) {
            this.movementThisTick.removeLast();
        }
    }

    protected void clearMovementThisTick() {
        this.movementThisTick.clear();
    }

    public boolean hasMovedHorizontallyRecently() {
        return Math.abs(this.lastKnownSpeed.horizontalDistance()) > 1.0E-5F;
    }

    public void applyEffectsFromBlocks(final Vec3 from, final Vec3 to) {
        this.applyEffectsFromBlocks(List.of(new Entity.Movement(from, to)));
    }

    private void applyEffectsFromBlocks(final List<Entity.Movement> movements) {
        if (this.isAffectedByBlocks()) {
            if (this.onGround()) {
                BlockPos effectPos = this.getOnPosLegacy();
                BlockState effectState = this.level().getBlockState(effectPos);
                effectState.getBlock().stepOn(this.level(), effectPos, effectState, this);
            }

            boolean wasOnFire = this.isOnFire();
            boolean wasFreezing = this.isFreezing();
            int previousRemainingFireTicks = this.getRemainingFireTicks();
            this.checkInsideBlocks(movements, this.insideEffectCollector);
            this.insideEffectCollector.applyAndClear(this);
            if (this.isInRain()) {
                this.clearFire();
            }

            if (wasOnFire && !this.isOnFire() || wasFreezing && !this.isFreezing()) {
                this.playEntityOnFireExtinguishedSound();
            }

            boolean wasIgnitedThisTick = this.getRemainingFireTicks() > previousRemainingFireTicks;
            if (!this.level().isClientSide() && !this.isOnFire() && !wasIgnitedThisTick) {
                this.setRemainingFireTicks(-this.getFireImmuneTicks());
            }
        }
    }

    protected boolean isAffectedByBlocks() {
        return !this.isRemoved() && !this.noPhysics;
    }

    private boolean isStateClimbable(final BlockState state) {
        return state.is(BlockTags.CLIMBABLE) || state.is(Blocks.POWDER_SNOW);
    }

    private boolean vibrationAndSoundEffectsFromBlock(
        final BlockPos pos, final BlockState blockState, final boolean shouldSound, final boolean shouldVibrate, final Vec3 clippedMovement
    ) {
        if (blockState.isAir()) {
            return false;
        }

        boolean isClimbable = this.isStateClimbable(blockState);
        if ((this.onGround() || isClimbable || this.isCrouching() && clippedMovement.y == 0.0 || this.isOnRails()) && !this.isSwimming()) {
            if (shouldSound) {
                this.walkingStepSound(pos, blockState);
            }

            if (shouldVibrate) {
                this.level().gameEvent(GameEvent.STEP, this.position(), GameEvent.Context.of(this, blockState));
            }

            return true;
        } else {
            return false;
        }
    }

    protected boolean isHorizontalCollisionMinor(final Vec3 movement) {
        return false;
    }

    protected void playEntityOnFireExtinguishedSound() {
        if (!this.level.isClientSide()) {
            this.level()
                .playSound(
                    null,
                    this.getX(),
                    this.getY(),
                    this.getZ(),
                    SoundEvents.GENERIC_EXTINGUISH_FIRE,
                    this.getSoundSource(),
                    0.7F,
                    1.6F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F
                );
        }
    }

    public void extinguishFire() {
        if (this.isOnFire()) {
            this.playEntityOnFireExtinguishedSound();
        }

        this.clearFire();
    }

    protected void processFlappingMovement() {
        if (this.isFlapping()) {
            this.onFlap();
            if (this.getMovementEmission().emitsEvents()) {
                this.gameEvent(GameEvent.FLAP);
            }
        }
    }

    @Deprecated
    public BlockPos getOnPosLegacy() {
        return this.getOnPos(0.2F);
    }

    public BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return this.getOnPos(0.500001F);
    }

    public BlockPos getOnPos() {
        return this.getOnPos(1.0E-5F);
    }

    protected BlockPos getOnPos(final float offset) {
        if (this.mainSupportingBlockPos.isPresent() && this.level().getChunkIfLoadedImmediately(this.mainSupportingBlockPos.get()) != null) { // Paper - ensure no loads
            BlockPos getOnPos = this.mainSupportingBlockPos.get();
            if (!(offset > 1.0E-5F)) {
                return getOnPos;
            }

            BlockState belowState = this.level().getBlockState(getOnPos);
            return (!(offset <= 0.5) || !belowState.is(BlockTags.FENCES))
                    && !belowState.is(BlockTags.WALLS)
                    && !(belowState.getBlock() instanceof FenceGateBlock)
                ? getOnPos.atY(Mth.floor(this.position.y - offset))
                : getOnPos;
        } else {
            int xTruncated = Mth.floor(this.position.x);
            int yTruncatedBelow = Mth.floor(this.position.y - offset);
            int zTruncated = Mth.floor(this.position.z);
            return new BlockPos(xTruncated, yTruncatedBelow, zTruncated);
        }
    }

    protected float getBlockJumpFactor() {
        float jumpFactorHere = this.level().getBlockState(this.blockPosition()).getBlock().getJumpFactor();
        float jumpFactorBelow = this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getJumpFactor();
        return jumpFactorHere == 1.0 ? jumpFactorBelow : jumpFactorHere;
    }

    protected float getBlockSpeedFactor() {
        BlockState state = this.level().getBlockState(this.blockPosition());
        float speedFactorHere = state.getBlock().getSpeedFactor();
        if (!state.is(Blocks.WATER) && !state.is(Blocks.BUBBLE_COLUMN)) {
            return speedFactorHere == 1.0
                ? this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getSpeedFactor()
                : speedFactorHere;
        } else {
            return speedFactorHere;
        }
    }

    protected Vec3 maybeBackOffFromEdge(final Vec3 delta, final MoverType moverType) {
        return delta;
    }

    protected Vec3 limitPistonMovement(final Vec3 vec) {
        if (vec.lengthSqr() <= 1.0E-7) {
            return vec;
        }

        long currentGameTime = this.level().getRedstoneGameTime(); // Folia - region threading
        if (currentGameTime != this.pistonDeltasGameTime) {
            Arrays.fill(this.pistonDeltas, 0.0);
            this.pistonDeltasGameTime = currentGameTime;
        }

        if (vec.x != 0.0) {
            double xa = this.applyPistonMovementRestriction(Direction.Axis.X, vec.x);
            return Math.abs(xa) <= 1.0E-5F ? Vec3.ZERO : new Vec3(xa, 0.0, 0.0);
        } else if (vec.y != 0.0) {
            double ya = this.applyPistonMovementRestriction(Direction.Axis.Y, vec.y);
            return Math.abs(ya) <= 1.0E-5F ? Vec3.ZERO : new Vec3(0.0, ya, 0.0);
        } else if (vec.z != 0.0) {
            double za = this.applyPistonMovementRestriction(Direction.Axis.Z, vec.z);
            return Math.abs(za) <= 1.0E-5F ? Vec3.ZERO : new Vec3(0.0, 0.0, za);
        } else {
            return Vec3.ZERO;
        }
    }

    private double applyPistonMovementRestriction(final Direction.Axis axis, double amount) {
        int ordinal = axis.ordinal();
        double min = Mth.clamp(amount + this.pistonDeltas[ordinal], -0.51, 0.51);
        amount = min - this.pistonDeltas[ordinal];
        this.pistonDeltas[ordinal] = min;
        return amount;
    }

    public double getAvailableSpaceBelow(final double maxDistance) {
        AABB aabb = this.getBoundingBox();
        AABB below = aabb.setMinY(aabb.minY - maxDistance).setMaxY(aabb.minY);
        List<VoxelShape> colliders = collectAllColliders(this, this.level, below);
        return colliders.isEmpty() ? maxDistance : -Shapes.collide(Direction.Axis.Y, aabb, colliders, -maxDistance);
    }

    private Vec3 collide(final Vec3 movement) {
        // Paper start - optimise collisions
        final boolean xZero = movement.x == 0.0;
        final boolean yZero = movement.y == 0.0;
        final boolean zZero = movement.z == 0.0;
        if (xZero & yZero & zZero) {
            return movement;
        }

        final AABB currentBox = this.getBoundingBox();

        final List<VoxelShape> potentialCollisionsVoxel = new ArrayList<>();
        final List<AABB> potentialCollisionsBB = new ArrayList<>();

        final AABB initialCollisionBox;
        if (xZero & zZero) {
            // note: xZero & zZero -> collision on x/z == 0 -> no step height calculation
            // this specifically optimises entities standing still
            initialCollisionBox = movement.y < 0.0 ?
                ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.cutDownwards(currentBox, movement.y) : ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.cutUpwards(currentBox, movement.y);
        } else {
            initialCollisionBox = currentBox.expandTowards(movement);
        }

        final List<AABB> entityAABBs = new ArrayList<>();
        ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getEntityHardCollisions(
            this.level, (Entity)(Object)this, initialCollisionBox, entityAABBs, 0, null
        );

        ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getCollisionsForBlocksOrWorldBorder(
            this.level, (Entity)(Object)this, initialCollisionBox, potentialCollisionsVoxel, potentialCollisionsBB,
            ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_BORDER, null
        );
        potentialCollisionsBB.addAll(entityAABBs);
        final Vec3 collided = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.performCollisions(movement, currentBox, potentialCollisionsVoxel, potentialCollisionsBB);

        final boolean collidedX = collided.x != movement.x;
        final boolean collidedY = collided.y != movement.y;
        final boolean collidedZ = collided.z != movement.z;

        final boolean collidedDownwards = collidedY && movement.y < 0.0;

        final double stepHeight;

        if ((!collidedDownwards && !this.onGround) || (!collidedX && !collidedZ) || (stepHeight = (double)this.maxUpStep()) <= 0.0) {
            return collided;
        }

        final AABB collidedYBox = collidedDownwards ? currentBox.move(0.0, collided.y, 0.0) : currentBox;
        AABB stepRetrievalBox = collidedYBox.expandTowards(movement.x, stepHeight, movement.z);
        if (!collidedDownwards) {
            stepRetrievalBox = stepRetrievalBox.expandTowards(0.0, (double)-1.0E-5F, 0.0);
        }

        final List<VoxelShape> stepVoxels = new ArrayList<>();
        final List<AABB> stepAABBs = entityAABBs;

        ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.getCollisionsForBlocksOrWorldBorder(
            this.level, (Entity)(Object)this, stepRetrievalBox, stepVoxels, stepAABBs,
            ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_FLAG_CHECK_BORDER, null
        );

        for (final float step : calculateStepHeights(collidedYBox, stepVoxels, stepAABBs, (float)stepHeight, (float)collided.y)) {
            final Vec3 stepResult = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.performCollisions(new Vec3(movement.x, (double)step, movement.z), collidedYBox, stepVoxels, stepAABBs);
            if (stepResult.horizontalDistanceSqr() > collided.horizontalDistanceSqr()) {
                return stepResult.add(0.0, collidedYBox.minY - currentBox.minY, 0.0);
            }
        }

        return collided;
        // Paper end - optimise collisions
    }

    private static float[] collectCandidateStepUpHeights(
        final AABB boundingBox, final List<VoxelShape> colliders, final float maxStepHeight, final float stepHeightToSkip
    ) {
        FloatSet candidates = new FloatArraySet(4);

        for (VoxelShape collider : colliders) {
            for (double coord : collider.getCoords(Direction.Axis.Y)) {
                float relativeCoord = (float)(coord - boundingBox.minY);
                if (!(relativeCoord < 0.0F) && relativeCoord != stepHeightToSkip) {
                    if (relativeCoord > maxStepHeight) {
                        break;
                    }

                    candidates.add(relativeCoord);
                }
            }
        }

        float[] sortedCandidates = candidates.toFloatArray();
        FloatArrays.unstableSort(sortedCandidates);
        return sortedCandidates;
    }

    public static Vec3 collideBoundingBox(
        final Entity source, final Vec3 movement, final AABB boundingBox, final Level level, final List<VoxelShape> entityColliders
    ) {
        List<VoxelShape> colliders = collectCollidersIgnoringWorldBorder(source, level, entityColliders, boundingBox.expandTowards(movement));
        return collideWithShapes(movement, boundingBox, colliders);
    }

    public static Vec3 collideBoundingBox(
        final CollisionContext source, final Vec3 movement, final AABB boundingBox, final Level level, final List<VoxelShape> entityColliders
    ) {
        List<VoxelShape> colliders = collectCollidersIgnoringWorldBorder(source, level, entityColliders, boundingBox.expandTowards(movement));
        return collideWithShapes(movement, boundingBox, colliders);
    }

    public static List<VoxelShape> collectAllColliders(final @Nullable Entity source, final Level level, final AABB boundingBox) {
        List<VoxelShape> entityColliders = level.getEntityCollisions(source, boundingBox);
        return collectCollidersIgnoringWorldBorder(source, level, entityColliders, boundingBox);
    }

    private static List<VoxelShape> collectCollidersIgnoringWorldBorder(
        final @Nullable Entity source, final Level level, final List<VoxelShape> entityColliders, final AABB boundingBox
    ) {
        Builder<VoxelShape> colliders = ImmutableList.builderWithExpectedSize(entityColliders.size() + 1);
        if (!entityColliders.isEmpty()) {
            colliders.addAll(entityColliders);
        }

        WorldBorder worldBorder = level.getWorldBorder();
        boolean isEntityInsideCloseToBorder = source != null && worldBorder.isInsideCloseToBorder(source, boundingBox);
        if (isEntityInsideCloseToBorder) {
            colliders.add(worldBorder.getCollisionShape());
        }

        colliders.addAll(level.getBlockCollisions(source, boundingBox));
        return colliders.build();
    }

    private static List<VoxelShape> collectCollidersIgnoringWorldBorder(
        final CollisionContext source, final Level level, final List<VoxelShape> entityColliders, final AABB boundingBox
    ) {
        Builder<VoxelShape> colliders = ImmutableList.builderWithExpectedSize(entityColliders.size() + 1);
        if (!entityColliders.isEmpty()) {
            colliders.addAll(entityColliders);
        }

        colliders.addAll(level.getBlockCollisionsFromContext(source, boundingBox));
        return colliders.build();
    }

    private static Vec3 collideWithShapes(final Vec3 movement, final AABB boundingBox, final List<VoxelShape> shapes) {
        if (shapes.isEmpty()) {
            return movement;
        }

        Vec3 resolvedMovement = Vec3.ZERO;

        for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
            double axisMovement = movement.get(axis);
            if (axisMovement != 0.0) {
                double collision = Shapes.collide(axis, boundingBox.move(resolvedMovement), shapes, axisMovement);
                resolvedMovement = resolvedMovement.with(axis, collision);
            }
        }

        return resolvedMovement;
    }

    protected float nextStep() {
        return (int)this.moveDist + 1;
    }

    public SoundEvent getSwimSound() {
        return SoundEvents.GENERIC_SWIM;
    }

    public SoundEvent getSwimSplashSound() {
        return SoundEvents.GENERIC_SPLASH;
    }

    public SoundEvent getSwimHighSpeedSplashSound() {
        return SoundEvents.GENERIC_SPLASH;
    }

    private void checkInsideBlocks(final List<Entity.Movement> movements, final InsideBlockEffectApplier.StepBasedCollector effectCollector) {
        if (this.isAffectedByBlocks()) {
            LongSet visitedBlocks = this.visitedBlocks;

            for (Entity.Movement movement : movements) {
                Vec3 pos = movement.from;
                Vec3 delta = movement.to().subtract(movement.from());
                int maxMovementIterations = 16;
                if (movement.axisDependentOriginalMovement().isPresent() && delta.lengthSqr() > 0.0) {
                    for (Direction.Axis axis : Direction.axisStepOrder(movement.axisDependentOriginalMovement().get())) {
                        double axisMove = delta.get(axis);
                        if (axisMove != 0.0) {
                            Vec3 to = pos.relative(axis.getPositive(), axisMove);
                            maxMovementIterations -= this.checkInsideBlocks(pos, to, effectCollector, visitedBlocks, maxMovementIterations);
                            pos = to;
                        }
                    }
                } else {
                    maxMovementIterations -= this.checkInsideBlocks(movement.from(), movement.to(), effectCollector, visitedBlocks, 16);
                }

                if (maxMovementIterations <= 0) {
                    this.checkInsideBlocks(movement.to(), movement.to(), effectCollector, visitedBlocks, 1);
                }
            }

            visitedBlocks.clear();
        }
    }

    private int checkInsideBlocks(
        final Vec3 from,
        final Vec3 to,
        final InsideBlockEffectApplier.StepBasedCollector effectCollector,
        final LongSet visitedBlocks,
        final int maxMovementIterations
    ) {
        AABB deflatedBoundingBoxAtTarget = this.makeBoundingBox(to).deflate(1.0E-5F);
        boolean movedFar = from.distanceToSqr(to) > Mth.square(0.9999900000002526);
        boolean debugEntityBlockIntersections = this.level instanceof ServerLevel serverLevel
            && serverLevel.getServer().debugSubscribers().hasAnySubscriberFor(DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS);
        AtomicInteger iterations = new AtomicInteger();
        BlockGetter.forEachBlockIntersectedBetween(
            from,
            to,
            deflatedBoundingBoxAtTarget,
            (blockIntersection, iteration) -> {
                if (!this.isAlive()) {
                    return false;
                }

                if (iteration >= maxMovementIterations) {
                    return false;
                }

                // Folia start - region threading
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(Entity.this.level, blockIntersection, 16)) {
                    return false;
                }
                // Folia end - region threading
                iterations.set(iteration);
                BlockState state = this.level().getBlockState(blockIntersection);
                if (state.isAir()) {
                    if (debugEntityBlockIntersections) {
                        this.debugBlockIntersection((ServerLevel)this.level(), blockIntersection.immutable(), false, false);
                    }

                    return true;
                } else {
                    VoxelShape intersectShape = state.getEntityInsideCollisionShape(this.level(), blockIntersection, this);
                    boolean insideBlock = intersectShape == Shapes.block()
                        || this.collidedWithShapeMovingFrom(from, to, intersectShape.move(new Vec3(blockIntersection)).toAabbs());
                    boolean insideFluid = this.collidedWithFluid(state.getFluidState(), blockIntersection, from, to);
                    if ((insideBlock || insideFluid) && visitedBlocks.add(blockIntersection.asLong())) {
                        if (insideBlock) {
                            try {
                                boolean isPrecise = movedFar || deflatedBoundingBoxAtTarget.intersects(blockIntersection);
                                effectCollector.advanceStep(iteration, blockIntersection); // Paper - track position inside effect was triggered on
                                state.entityInside(this.level(), blockIntersection, this, effectCollector, isPrecise);
                                this.onInsideBlock(state);
                            } catch (Throwable t) {
                                CrashReport report = CrashReport.forThrowable(t, "Colliding entity with block");
                                CrashReportCategory category = report.addCategory("Block being collided with");
                                CrashReportCategory.populateBlockDetails(category, this.level(), blockIntersection, state);
                                CrashReportCategory entityCategory = report.addCategory("Entity being checked for collision");
                                this.fillCrashReportCategory(entityCategory);
                                throw new ReportedException(report);
                            }
                        }

                        if (insideFluid) {
                            effectCollector.advanceStep(iteration, blockIntersection); // Paper - track position inside effect was triggered on
                            state.getFluidState().entityInside(this.level(), blockIntersection, this, effectCollector);
                        }

                        if (debugEntityBlockIntersections) {
                            this.debugBlockIntersection((ServerLevel)this.level(), blockIntersection.immutable(), insideBlock, insideFluid);
                        }

                        return true;
                    } else {
                        return true;
                    }
                }
            }
        );
        return iterations.get() + 1;
    }

    private void debugBlockIntersection(final ServerLevel level, final BlockPos pos, final boolean insideBlock, final boolean insideFluid) {
        DebugEntityBlockIntersection type;
        if (insideFluid) {
            type = DebugEntityBlockIntersection.IN_FLUID;
        } else if (insideBlock) {
            type = DebugEntityBlockIntersection.IN_BLOCK;
        } else {
            type = DebugEntityBlockIntersection.IN_AIR;
        }

        level.debugSynchronizers().sendBlockValue(pos, DebugSubscriptions.ENTITY_BLOCK_INTERSECTIONS, type);
    }

    public boolean collidedWithFluid(final FluidState fluidState, final BlockPos blockPos, final Vec3 from, final Vec3 to) {
        AABB fluidAABB = fluidState.getAABB(this.level(), blockPos);
        return fluidAABB != null && this.collidedWithShapeMovingFrom(from, to, List.of(fluidAABB));
    }

    public boolean collidedWithShapeMovingFrom(final Vec3 from, final Vec3 to, final List<AABB> aabbs) {
        AABB boundingBoxAtFrom = this.makeBoundingBox(from);
        Vec3 travelVector = to.subtract(from);
        return boundingBoxAtFrom.collidedAlongVector(travelVector, aabbs);
    }

    protected void onInsideBlock(final BlockState state) {
    }

    public BlockPos adjustSpawnLocation(final ServerLevel level, final BlockPos spawnSuggestion) {
        BlockPos spawnBlockPos = level.getRespawnData().pos();
        Vec3 spawnPos = Vec3.atCenterOf(spawnBlockPos);
        int spawnHeight = level.getChunkAt(spawnBlockPos).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnBlockPos.getX(), spawnBlockPos.getZ()) + 1;
        return BlockPos.containing(spawnPos.x, spawnHeight, spawnPos.z);
    }

    public void gameEvent(final Holder<GameEvent> event, final @Nullable Entity sourceEntity) {
        this.level().gameEvent(sourceEntity, event, this.position);
    }

    public void gameEvent(final Holder<GameEvent> event) {
        this.gameEvent(event, this);
    }

    private void walkingStepSound(final BlockPos onPos, final BlockState onState) {
        this.playStepSound(onPos, onState);
        if (this.shouldPlayAmethystStepSound(onState)) {
            this.playAmethystStepSound();
        }
    }

    protected void waterSwimSound() {
        Entity entity = Objects.requireNonNullElse(this.getControllingPassenger(), this);
        float volumeModifier = entity == this ? 0.35F : 0.4F;
        Vec3 deltaMovement = entity.getDeltaMovement();
        float speed = Math.min(
            1.0F,
            (float)Math.sqrt(deltaMovement.x * deltaMovement.x * 0.2F + deltaMovement.y * deltaMovement.y + deltaMovement.z * deltaMovement.z * 0.2F)
                * volumeModifier
        );
        this.playSwimSound(speed);
    }

    protected BlockPos getPrimaryStepSoundBlockPos(final BlockPos affectingPos) {
        BlockPos abovePos = affectingPos.above();
        BlockState aboveState = this.level().getBlockState(abovePos);
        return !aboveState.is(BlockTags.INSIDE_STEP_SOUND_BLOCKS) && !aboveState.is(BlockTags.COMBINATION_STEP_SOUND_BLOCKS) ? affectingPos : abovePos;
    }

    protected void playCombinationStepSounds(final BlockState primaryStepSound, final BlockState secondaryStepSound) {
        SoundType primaryStepSoundType = primaryStepSound.getSoundType();
        this.playSound(primaryStepSoundType.getStepSound(), primaryStepSoundType.getVolume() * 0.15F, primaryStepSoundType.getPitch());
        this.playMuffledStepSound(secondaryStepSound);
    }

    protected void playMuffledStepSound(final BlockState blockState) {
        SoundType secondaryStepSoundType = blockState.getSoundType();
        this.playSound(secondaryStepSoundType.getStepSound(), secondaryStepSoundType.getVolume() * 0.05F, secondaryStepSoundType.getPitch() * 0.8F);
    }

    protected void playStepSound(final BlockPos pos, final BlockState blockState) {
        SoundType soundType = blockState.getSoundType();
        this.playSound(soundType.getStepSound(), soundType.getVolume() * 0.15F, soundType.getPitch());
    }

    private boolean shouldPlayAmethystStepSound(final BlockState affectingState) {
        return affectingState.is(BlockTags.CRYSTAL_SOUND_BLOCKS) && this.tickCount >= this.lastCrystalSoundPlayTick + 20;
    }

    private void playAmethystStepSound() {
        this.crystalSoundIntensity = this.crystalSoundIntensity * (float)Math.pow(0.997, this.tickCount - this.lastCrystalSoundPlayTick);
        this.crystalSoundIntensity = Math.min(1.0F, this.crystalSoundIntensity + 0.07F);
        float pitch = 0.5F + this.crystalSoundIntensity * this.random.nextFloat() * 1.2F;
        float volume = 0.1F + this.crystalSoundIntensity * 1.2F;
        this.playSound(SoundEvents.AMETHYST_BLOCK_CHIME, volume, pitch);
        this.lastCrystalSoundPlayTick = this.tickCount;
    }

    protected void playSwimSound(final float volume) {
        this.playSound(this.getSwimSound(), volume, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
    }

    protected void onFlap() {
    }

    protected boolean isFlapping() {
        return false;
    }

    public void playSound(final SoundEvent sound, final float volume, final float pitch) {
        if (!this.isSilent()) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(), sound, this.getSoundSource(), volume, pitch);
        }
    }

    public void playSound(final SoundEvent sound) {
        if (!this.isSilent()) {
            this.playSound(sound, 1.0F, 1.0F);
        }
    }

    public boolean isSilent() {
        return this.entityData.get(DATA_SILENT);
    }

    public void setSilent(final boolean silent) {
        this.entityData.set(DATA_SILENT, silent);
    }

    public boolean isNoGravity() {
        return this.entityData.get(DATA_NO_GRAVITY);
    }

    public void setNoGravity(final boolean noGravity) {
        this.entityData.set(DATA_NO_GRAVITY, noGravity);
    }

    protected double getDefaultGravity() {
        return 0.0;
    }

    public final double getGravity() {
        return this.isNoGravity() ? 0.0 : this.getDefaultGravity();
    }

    protected void applyGravity() {
        double gravity = this.getGravity();
        if (gravity != 0.0) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0, -gravity, 0.0));
        }
    }

    protected float getAirDrag() {
        return 0.98F;
    }

    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.ALL;
    }

    public boolean dampensVibrations() {
        return false;
    }

    public final void doCheckFallDamage(final double xa, final double ya, final double za, final boolean onGround) {
        if (!this.touchingUnloadedChunk()) {
            this.checkSupportingBlock(onGround, new Vec3(xa, ya, za));
            BlockPos pos = this.getOnPosLegacy();
            BlockState state = this.level().getBlockState(pos);
            this.checkFallDamage(ya, onGround, state, pos);
        }
    }

    protected void checkFallDamage(final double ya, final boolean onGround, final BlockState onState, final BlockPos pos) {
        if (!this.isInWater() && ya < 0.0) {
            this.fallDistance -= (float)ya;
        }

        if (onGround) {
            if (this.fallDistance > 0.0) {
                onState.getBlock().fallOn(this.level(), onState, pos, this, this.fallDistance);
                this.level()
                    .gameEvent(
                        GameEvent.HIT_GROUND,
                        this.position,
                        GameEvent.Context.of(
                            this, this.mainSupportingBlockPos.<BlockState>map(blockPos -> this.level().getBlockState(blockPos)).orElse(onState)
                        )
                    );
            }

            this.resetFallDistance();
        }
    }

    public boolean fireImmune() {
        return this.getType().fireImmune();
    }

    public boolean causeFallDamage(final double fallDistance, final float damageModifier, final DamageSource damageSource) {
        if (this.is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
            return false;
        }

        this.propagateFallToPassengers(fallDistance, damageModifier, damageSource);
        return false;
    }

    protected void propagateFallToPassengers(final double fallDistance, final float damageModifier, final DamageSource damageSource) {
        if (this.isVehicle()) {
            for (Entity passenger : this.getPassengers()) {
                passenger.causeFallDamage(fallDistance, damageModifier, damageSource);
            }
        }
    }

    public boolean isInWater() {
        return this.wasTouchingWater;
    }

    public boolean isInRain() {
        BlockPos pos = this.blockPosition();
        return this.level().isRainingAt(pos) || this.level().isRainingAt(BlockPos.containing(pos.getX(), this.getBoundingBox().maxY, pos.getZ()));
    }

    public boolean isInWaterOrRain() {
        return this.isInWater() || this.isInRain();
    }

    public boolean isInLiquid() {
        return this.isInWater() || this.isInLava();
    }

    public boolean isUnderWater() {
        return this.wasEyeInWater && this.isInWater();
    }

    public boolean isInShallowWater() {
        return this.isInWater() && !this.isUnderWater();
    }

    public boolean isInClouds() {
        if (ARGB.alpha(this.level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_COLOR, this.position())) == 0) {
            return false;
        }

        float cloudBottom = this.level.environmentAttributes().getValue(EnvironmentAttributes.CLOUD_HEIGHT, this.position());
        if (this.getY() + this.getBbHeight() < cloudBottom) {
            return false;
        }

        float cloudTop = cloudBottom + 4.0F;
        return this.getY() <= cloudTop;
    }

    public void updateSwimming() {
        if (this.isSwimming()) {
            this.setSwimming(this.isSprinting() && this.isInWater() && !this.isPassenger());
        } else {
            this.setSwimming(
                this.isSprinting() && this.isUnderWater() && !this.isPassenger() && this.level().getFluidState(this.blockPosition).is(FluidTags.WATER)
            );
        }
    }

    protected boolean updateFluidInteraction() {
        this.fluidInteraction.update(this, !this.isPushedByFluid());
        boolean inWater = this.fluidInteraction.isInFluid(FluidTags.WATER);
        boolean inLava = this.fluidInteraction.isInFluid(FluidTags.LAVA);
        if (inWater) {
            this.resetFallDistance();
            if (!this.wasTouchingWater && !this.firstTick) {
                this.doWaterSplashEffect();
            }
        }

        this.wasTouchingWater = inWater;
        if (this.isPushedByFluid()) {
            if (inWater) {
                this.fluidInteraction.applyCurrentTo(FluidTags.WATER, this, 0.014);
            }

            if (inLava) {
                double lavaFlowScale = this.level.environmentAttributes().getDimensionValue(EnvironmentAttributes.FAST_LAVA) ? 0.007 : 0.0023333333333333335;
                this.fluidInteraction.applyCurrentTo(FluidTags.LAVA, this, lavaFlowScale);
            }
        }

        return inWater || inLava;
    }

    protected void doWaterSplashEffect() {
        Entity entity = Objects.requireNonNullElse(this.getControllingPassenger(), this);
        float volumeModifier = entity == this ? 0.2F : 0.9F;
        Vec3 movement = entity.getDeltaMovement();
        float speed = Math.min(
            1.0F, (float)Math.sqrt(movement.x * movement.x * 0.2F + movement.y * movement.y + movement.z * movement.z * 0.2F) * volumeModifier
        );
        if (speed < 0.25F) {
            this.playSound(this.getSwimSplashSound(), speed, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
        } else {
            this.playSound(this.getSwimHighSpeedSplashSound(), speed, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
        }

        float yt = Mth.floor(this.getY());

        for (int i = 0; i < 1.0F + this.dimensions.width() * 20.0F; i++) {
            double xo = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            double zo = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            this.level()
                .addParticle(
                    ParticleTypes.BUBBLE, this.getX() + xo, yt + 1.0F, this.getZ() + zo, movement.x, movement.y - this.random.nextDouble() * 0.2F, movement.z
                );
        }

        for (int i = 0; i < 1.0F + this.dimensions.width() * 20.0F; i++) {
            double xo = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            double zo = (this.random.nextDouble() * 2.0 - 1.0) * this.dimensions.width();
            this.level().addParticle(ParticleTypes.SPLASH, this.getX() + xo, yt + 1.0F, this.getZ() + zo, movement.x, movement.y, movement.z);
        }

        this.gameEvent(GameEvent.SPLASH);
    }

    @Deprecated
    protected BlockState getBlockStateOnLegacy() {
        return this.level().getBlockState(this.getOnPosLegacy());
    }

    public BlockState getBlockStateOn() {
        return this.level().getBlockState(this.getOnPos());
    }

    public boolean canSpawnSprintParticle() {
        return this.isSprinting() && !this.isInWater() && !this.isSpectator() && !this.isCrouching() && !this.isInLava() && this.isAlive();
    }

    protected void spawnSprintParticle() {
        BlockPos pos = this.getOnPosLegacy();
        BlockState blockState = this.level().getBlockState(pos);
        if (blockState.getRenderShape() != RenderShape.INVISIBLE) {
            Vec3 movement = this.getDeltaMovement();
            BlockPos entityPosition = this.blockPosition();
            double x = this.getX() + (this.random.nextDouble() - 0.5) * this.dimensions.width();
            double z = this.getZ() + (this.random.nextDouble() - 0.5) * this.dimensions.width();
            if (entityPosition.getX() != pos.getX()) {
                x = Mth.clamp(x, pos.getX(), pos.getX() + 1.0);
            }

            if (entityPosition.getZ() != pos.getZ()) {
                z = Mth.clamp(z, pos.getZ(), pos.getZ() + 1.0);
            }

            this.level()
                .addParticle(new BlockParticleOption(ParticleTypes.BLOCK, blockState), x, this.getY() + 0.1, z, movement.x * -4.0, 1.5, movement.z * -4.0);
        }
    }

    public boolean isEyeInFluid(final TagKey<Fluid> type) {
        return this.fluidInteraction.isEyeInFluid(type);
    }

    public boolean isInLava() {
        return !this.firstTick && this.fluidInteraction.isInFluid(FluidTags.LAVA);
    }

    public void moveRelative(final float speed, final Vec3 input) {
        Vec3 delta = getInputVector(input, speed, this.getYRot());
        this.setDeltaMovement(this.getDeltaMovement().add(delta));
    }

    protected static Vec3 getInputVector(final Vec3 input, final float speed, final float yRot) {
        double length = input.lengthSqr();
        if (length < 1.0E-7) {
            return Vec3.ZERO;
        }

        Vec3 movement = (length > 1.0 ? input.normalize() : input).scale(speed);
        float sin = Mth.sin(yRot * Mth.DEG_TO_RAD);
        float cos = Mth.cos(yRot * Mth.DEG_TO_RAD);
        return new Vec3(movement.x * cos - movement.z * sin, movement.y, movement.z * cos + movement.x * sin);
    }

    @Deprecated
    public float getLightLevelDependentMagicValue() {
        return this.level().hasChunkAt(this.getBlockX(), this.getBlockZ())
            ? this.level().getLightLevelDependentMagicValue(BlockPos.containing(this.getX(), this.getEyeY(), this.getZ()))
            : 0.0F;
    }

    public void absSnapTo(final double x, final double y, final double z, final float yRot, final float xRot) {
        this.absSnapTo(x, y, z);
        this.absSnapRotationTo(yRot, xRot);
    }

    public void absSnapRotationTo(final float yRot, final float xRot) {
        this.setYRot(yRot % 360.0F);
        this.setXRot(Mth.clamp(xRot, -90.0F, 90.0F) % 360.0F);
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        this.setYHeadRot(yRot); // Paper - Update head rotation
    }

    public void absSnapTo(final double x, final double y, final double z) {
        double cx = Mth.clamp(x, -3.0E7, 3.0E7);
        double cz = Mth.clamp(z, -3.0E7, 3.0E7);
        this.xo = cx;
        this.yo = y;
        this.zo = cz;
        this.setPos(cx, y, cz);
        if (this.valid) this.level.getChunk((int) Math.floor(this.getX()) >> 4, (int) Math.floor(this.getZ()) >> 4); // CraftBukkit
    }

    public void snapTo(final Vec3 pos) {
        this.snapTo(pos.x, pos.y, pos.z);
    }

    public void snapTo(final double x, final double y, final double z) {
        this.snapTo(x, y, z, this.getYRot(), this.getXRot());
    }

    public void snapTo(final BlockPos spawnPos, final float yRot, final float xRot) {
        this.snapTo(Vec3.atBottomCenterOf(spawnPos), yRot, xRot);
    }

    public void snapTo(final Vec3 spawnPos, final float yRot, final float xRot) {
        this.snapTo(spawnPos.x, spawnPos.y, spawnPos.z, yRot, xRot);
    }

    public void snapTo(final double x, final double y, final double z, final float yRot, final float xRot) {
        this.setPosRaw(x, y, z);
        this.setYRot(yRot);
        this.setXRot(xRot);
        this.setOldPosAndRot();
        this.reapplyPosition();
        this.setYHeadRot(yRot); // Paper - Update head rotation
    }

    public final void setOldPosAndRot() {
        this.setOldPos();
        this.setOldRot();
    }

    public final void setOldPosAndRot(final Vec3 position, final float yRot, final float xRot) {
        this.setOldPos(position);
        this.setOldRot(yRot, xRot);
    }

    protected void setOldPos() {
        this.setOldPos(this.position);
    }

    public void setOldRot() {
        this.setOldRot(this.getYRot(), this.getXRot());
    }

    private void setOldPos(final Vec3 position) {
        this.xo = this.xOld = position.x;
        this.yo = this.yOld = position.y;
        this.zo = this.zOld = position.z;
    }

    private void setOldRot(final float yRot, final float xRot) {
        this.yRotO = yRot;
        this.xRotO = xRot;
    }

    public final Vec3 oldPosition() {
        return new Vec3(this.xOld, this.yOld, this.zOld);
    }

    public float distanceTo(final Entity entity) {
        float xd = (float)(this.getX() - entity.getX());
        float yd = (float)(this.getY() - entity.getY());
        float zd = (float)(this.getZ() - entity.getZ());
        return Mth.sqrt(xd * xd + yd * yd + zd * zd);
    }

    public double distanceToSqr(final double x2, final double y2, final double z2) {
        double xd = this.getX() - x2;
        double yd = this.getY() - y2;
        double zd = this.getZ() - z2;
        return xd * xd + yd * yd + zd * zd;
    }

    public double distanceToSqr(final Entity entity) {
        return this.distanceToSqr(entity.position());
    }

    public double distanceToSqr(final Vec3 pos) {
        double xd = this.getX() - pos.x;
        double yd = this.getY() - pos.y;
        double zd = this.getZ() - pos.z;
        return xd * xd + yd * yd + zd * zd;
    }

    public void playerTouch(final Player player) {
    }

    public void push(final Entity entity) {
        if (!this.isPassengerOfSameVehicle(entity)) {
            if (!entity.noPhysics && !this.noPhysics) {
                if (this.level.paperConfig().collisions.onlyPlayersCollide && !(entity instanceof ServerPlayer || this instanceof ServerPlayer)) return; // Paper - Collision option for requiring a player participant
                double xa = entity.getX() - this.getX();
                double za = entity.getZ() - this.getZ();
                double dd = Mth.absMax(xa, za);
                if (dd >= 0.01F) {
                    dd = Math.sqrt(dd);
                    xa /= dd;
                    za /= dd;
                    double pow = 1.0 / dd;
                    if (pow > 1.0) {
                        pow = 1.0;
                    }

                    xa *= pow;
                    za *= pow;
                    xa *= 0.05F;
                    za *= 0.05F;
                    if (!this.isVehicle() && this.isPushable()) {
                        this.push(-xa, 0.0, -za);
                    }

                    if (!entity.isVehicle() && entity.isPushable()) {
                        entity.push(xa, 0.0, za);
                    }
                }
            }
        }
    }

    public void push(final Vec3 impulse) {
        if (impulse.isFinite()) {
            this.push(impulse.x, impulse.y, impulse.z);
        }
    }

    public void push(final double xa, final double ya, final double za) {
        // Paper start - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
        this.push(xa, ya, za, null);
    }

    public void push(final double xa, final double ya, final double za, @Nullable Entity pushingEntity) {
        // Paper end - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
        if (Double.isFinite(xa) && Double.isFinite(ya) && Double.isFinite(za)) {
            // Paper start - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
            org.bukkit.util.Vector delta = new org.bukkit.util.Vector(xa, ya, za);
            if (pushingEntity != null) {
                io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent event = new io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent(this.getBukkitEntity(), io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.PUSH, pushingEntity.getBukkitEntity(), delta);
                if (!event.callEvent()) {
                    return;
                }
                delta = event.getKnockback();
            }
            this.setDeltaMovement(this.getDeltaMovement().add(delta.getX(), delta.getY(), delta.getZ()));
            // Paper end - Add EntityKnockbackByEntityEvent and EntityPushedByEntityAttackEvent
            this.needsSync = true;
        }
    }

    protected void markHurt() {
        this.hurtMarked = true;
    }

    @Deprecated
    public final void hurt(final DamageSource source, final float damage) {
        if (this.level instanceof ServerLevel serverLevel) {
            this.hurtServer(serverLevel, source, damage);
        }
    }

    @Deprecated
    public final boolean hurtOrSimulate(final DamageSource source, final float damage) {
        return this.level instanceof ServerLevel serverLevel ? this.hurtServer(serverLevel, source, damage) : this.hurtClient(source);
    }

    public abstract boolean hurtServer(ServerLevel level, DamageSource source, float damage);

    public boolean hurtClient(final DamageSource source) {
        return false;
    }

    public final Vec3 getViewVector(final float a) {
        return this.calculateViewVector(this.getViewXRot(a), this.getViewYRot(a));
    }

    public Direction getNearestViewDirection() {
        return Direction.getApproximateNearest(this.getViewVector(1.0F));
    }

    public float getViewXRot(final float a) {
        return this.getXRot(a);
    }

    public float getViewYRot(final float a) {
        return this.getYRot(a);
    }

    public float getXRot(final float partialTicks) {
        return partialTicks == 1.0F ? this.getXRot() : Mth.lerp(partialTicks, this.xRotO, this.getXRot());
    }

    public float getYRot(final float partialTicks) {
        return partialTicks == 1.0F ? this.getYRot() : Mth.rotLerp(partialTicks, this.yRotO, this.getYRot());
    }

    public final Vec3 calculateViewVector(final float xRot, final float yRot) {
        float realXRot = xRot * Mth.DEG_TO_RAD;
        float realYRot = -yRot * Mth.DEG_TO_RAD;
        float yCos = Mth.cos(realYRot);
        float ySin = Mth.sin(realYRot);
        float xCos = Mth.cos(realXRot);
        float xSin = Mth.sin(realXRot);
        return new Vec3(ySin * xCos, -xSin, yCos * xCos);
    }

    public final Vec3 getUpVector(final float a) {
        return this.calculateUpVector(this.getViewXRot(a), this.getViewYRot(a));
    }

    protected final Vec3 calculateUpVector(final float xRot, final float yRot) {
        return this.calculateViewVector(xRot - 90.0F, yRot);
    }

    public final Vec3 getEyePosition() {
        return new Vec3(this.getX(), this.getEyeY(), this.getZ());
    }

    public final Vec3 getEyePosition(final float partialTickTime) {
        double x = Mth.lerp(partialTickTime, this.xo, this.getX());
        double y = Mth.lerp(partialTickTime, this.yo, this.getY()) + this.getEyeHeight();
        double z = Mth.lerp(partialTickTime, this.zo, this.getZ());
        return new Vec3(x, y, z);
    }

    public Vec3 getLightProbePosition(final float partialTickTime) {
        return this.getEyePosition(partialTickTime);
    }

    public final Vec3 getPosition(final float partialTickTime) {
        double endX = Mth.lerp(partialTickTime, this.xo, this.getX());
        double endY = Mth.lerp(partialTickTime, this.yo, this.getY());
        double endZ = Mth.lerp(partialTickTime, this.zo, this.getZ());
        return new Vec3(endX, endY, endZ);
    }

    public HitResult pick(final double range, final float a, final boolean withLiquids) {
        Vec3 from = this.getEyePosition(a);
        Vec3 viewVector = this.getViewVector(a);
        Vec3 to = from.add(viewVector.x * range, viewVector.y * range, viewVector.z * range);
        return this.level().clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, withLiquids ? ClipContext.Fluid.ANY : ClipContext.Fluid.NONE, this));
    }

    public boolean canBeHitByProjectile() {
        return this.isAlive() && this.isPickable();
    }

    public boolean isPickable() {
        return false;
    }

    public boolean canBePickedFromInside() {
        return true;
    }

    public boolean isPushable() {
        // Paper start - Climbing should not bypass cramming gamerule
        return isCollidable(false);
    }

    public boolean isCollidable(boolean ignoreClimbing) {
        // Paper end - Climbing should not bypass cramming gamerule
        return false;
    }

    // CraftBukkit start - collidable API
    public boolean canCollideWithBukkit(Entity entity) {
        return this.isPushable();
    }
    // CraftBukkit end

    public void awardKillScore(final Entity victim, final DamageSource killingBlow) {
        if (victim instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.ENTITY_KILLED_PLAYER.trigger(serverPlayer, this, killingBlow);
        }
    }

    public boolean shouldRender(final double camX, final double camY, final double camZ) {
        double xd = this.getX() - camX;
        double yd = this.getY() - camY;
        double zd = this.getZ() - camZ;
        double distance = xd * xd + yd * yd + zd * zd;
        return this.shouldRenderAtSqrDistance(distance);
    }

    public boolean shouldRenderAtSqrDistance(final double distance) {
        double size = this.getBoundingBox().getSize();
        if (Double.isNaN(size)) {
            size = 1.0;
        }

        size *= 64.0 * viewScale;
        return distance < size * size;
    }

    public boolean saveAsPassenger(final ValueOutput output) {
        // CraftBukkit start - allow excluding certain data when saving
        // Paper start - Raw entity serialization API
        return this.saveAsPassenger(output, true, false, false);
    }

    public boolean saveAsPassenger(final ValueOutput output, final boolean includeAll, final boolean includeNonSaveable, final boolean forceSerialization) {
        // Paper end - Raw entity serialization API
        // CraftBukkit end
        if (this.removalReason != null && !this.removalReason.shouldSave() && !forceSerialization) { // Paper - Raw entity serialization API
            return false;
        }

        String id = this.getEncodeId(includeNonSaveable); // Paper - Raw entity serialization API
        if ((!this.persist && !forceSerialization) || id == null) { // CraftBukkit - persist flag // Paper - Raw entity serialization API
            return false;
        }

        output.putString("id", id);
        this.saveWithoutId(output, includeAll, includeNonSaveable, forceSerialization); // CraftBukkit - pass on includeAll // Paper - Raw entity serialization API
        return true;
    }

    public boolean save(final ValueOutput output) {
        return !this.isPassenger() && this.saveAsPassenger(output);
    }

    public void saveWithoutId(final ValueOutput output) {
        // CraftBukkit start - allow excluding certain data when saving
        // Paper start - Raw entity serialization API
        this.saveWithoutId(output, true, false, false);
    }

    public void saveWithoutId(final ValueOutput output, final boolean includeAll, final boolean includeNonSaveable, final boolean forceSerialization) {
        // Paper end - Raw entity serialization API
        // CraftBukkit end
        try {
            if (includeAll) { // CraftBukkit - selectively save position
            if (this.vehicle != null) {
                output.store("Pos", Vec3.CODEC, new Vec3(this.vehicle.getX(), this.getY(), this.vehicle.getZ()));
            } else {
                output.store("Pos", Vec3.CODEC, this.position());
            }
            } // CraftBukkit

            output.store("Motion", Vec3.CODEC, this.getDeltaMovement());
            // CraftBukkit start - Checking for NaN pitch/yaw and resetting to zero
            // TODO: make sure this is the best way to address this.
            if (Float.isNaN(this.yRot)) {
                this.yRot = 0;
            }

            if (Float.isNaN(this.xRot)) {
                this.xRot = 0;
            }
            // CraftBukkit end
            output.store("Rotation", Vec2.CODEC, new Vec2(this.getYRot(), this.getXRot()));
            output.putDouble("fall_distance", this.fallDistance);
            output.putShort("Fire", (short)this.remainingFireTicks);
            output.putShort("Air", (short)this.getAirSupply());
            output.putBoolean("OnGround", this.onGround());
            output.putBoolean("Invulnerable", this.invulnerable);
            output.putInt("PortalCooldown", this.portalCooldown);
            // CraftBukkit start - selectively save uuid and world
            if (includeAll) {
            output.store("UUID", UUIDUtil.CODEC, this.getUUID());
            // PAIL: Check above UUID reads 1.8 properly, ie: UUIDMost / UUIDLeast
            output.putLong("WorldUUIDLeast", this.level.getWorld().getUID().getLeastSignificantBits());
            output.putLong("WorldUUIDMost", this.level.getWorld().getUID().getMostSignificantBits());
            }
            output.putInt("Bukkit.updateLevel", Entity.CURRENT_LEVEL);
            if (!this.persist) {
                output.putBoolean("Bukkit.persist", this.persist);
            }
            if (!this.visibleByDefault) {
                output.putBoolean("Bukkit.visibleByDefault", this.visibleByDefault);
            }
            if (this.persistentInvisibility) {
                output.putBoolean("Bukkit.invisible", this.persistentInvisibility);
            }
            // SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
            if (this.maxAirTicks != this.getDefaultMaxAirSupply()) {
                output.putInt("Bukkit.MaxAirSupply", this.getMaxAirSupply());
            }
            output.putInt("Spigot.ticksLived", this.totalEntityAge); // Paper
            // CraftBukkit end
            output.storeNullable("CustomName", ComponentSerialization.CODEC, this.getCustomName());
            if (this.isCustomNameVisible()) {
                output.putBoolean("CustomNameVisible", this.isCustomNameVisible());
            }

            if (this.isSilent()) {
                output.putBoolean("Silent", this.isSilent());
            }

            if (this.isNoGravity()) {
                output.putBoolean("NoGravity", this.isNoGravity());
            }

            if (this.hasGlowingTag) {
                output.putBoolean("Glowing", true);
            }

            int ticksFrozen = this.getTicksFrozen();
            if (ticksFrozen > 0) {
                output.putInt("TicksFrozen", this.getTicksFrozen());
            }

            // Paper start - improve visual fire API
            if (this.visualFire.equals(net.kyori.adventure.util.TriState.TRUE)) {
                output.putBoolean("HasVisualFire", true);
            }
            if (this.visualFire != net.kyori.adventure.util.TriState.NOT_SET) {
                output.putString("Paper.FireOverride", visualFire.name());
            }
            // Paper end

            if (!this.tags.isEmpty()) {
                output.store("Tags", TAG_LIST_CODEC, List.copyOf(this.tags));
            }

            if (!this.customData.isEmpty()) {
                output.store("data", CustomData.CODEC, this.customData);
            }

            this.addAdditionalSaveData(output, includeAll); // CraftBukkit - pass on includeAll
            if (this.isVehicle()) {
                ValueOutput.ValueOutputList passengersList = output.childrenList("Passengers");

                for (Entity passenger : this.getPassengers()) {
                    ValueOutput passengerOutput = passengersList.addChild();
                    if (!passenger.saveAsPassenger(passengerOutput, includeAll, includeNonSaveable, forceSerialization)) { // CraftBukkit - pass on includeAll // Paper - Raw entity serialization API
                        passengersList.discardLast();
                    }
                }

                if (passengersList.isEmpty()) {
                    output.discard("Passengers");
                }
            }
            // CraftBukkit start - stores eventually existing bukkit values
            if (this.bukkitEntity != null) {
                this.bukkitEntity.storeBukkitValues(output);
            }
            // CraftBukkit end
            // Paper start
            if (this.origin != null) {
                UUID originWorld = this.originWorld != null ? this.originWorld : (this.level != null ? this.level.getWorld().getUID() : null);
                if (originWorld != null) {
                    output.store("Paper.OriginWorld", UUIDUtil.CODEC, originWorld);
                }
                output.store("Paper.Origin", Vec3.CODEC, this.origin);
            }
            if (this.spawnReason != null) {
                output.putString("Paper.SpawnReason", this.spawnReason.name());
            }
            // Save entity's from mob spawner status
            if (this.spawnedViaMobSpawner) {
                output.putBoolean("Paper.FromMobSpawner", true);
            }
            if (this.fromNetherPortal) {
                output.putBoolean("Paper.FromNetherPortal", true);
            }
            if (this.freezeLocked) {
                output.putBoolean("Paper.FreezeLock", true);
            }
            // Paper end
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Saving entity NBT");
            CrashReportCategory category = report.addCategory("Entity being saved");
            this.fillCrashReportCategory(category);
            throw new ReportedException(report);
        }
    }

    public void load(final ValueInput input) {
        try {
            Vec3 pos = input.read("Pos", Vec3.CODEC).orElse(Vec3.ZERO);
            Vec3 motion = input.read("Motion", Vec3.CODEC).orElse(Vec3.ZERO);
            Vec2 rotation = input.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
            this.setDeltaMovement(
                Math.abs(motion.x) > 10.0 ? 0.0 : motion.x, Math.abs(motion.y) > 10.0 ? 0.0 : motion.y, Math.abs(motion.z) > 10.0 ? 0.0 : motion.z
            );
            this.needsSync = true;
            double maxHorizontalPosition = 3.0000512E7;
            this.setPosRaw(Mth.clamp(pos.x, -3.0000512E7, 3.0000512E7), Mth.clamp(pos.y, -2.0E7, 2.0E7), Mth.clamp(pos.z, -3.0000512E7, 3.0000512E7));
            this.setYRot(rotation.x);
            this.setXRot(rotation.y);
            this.setOldPosAndRot();
            this.setYHeadRot(this.getYRot());
            this.setYBodyRot(this.getYRot());
            this.fallDistance = input.getDoubleOr("fall_distance", 0.0);
            this.remainingFireTicks = input.getShortOr("Fire", (short)0);
            this.setAirSupply(input.getIntOr("Air", this.getMaxAirSupply()));
            this.onGround = input.getBooleanOr("OnGround", false);
            this.invulnerable = input.getBooleanOr("Invulnerable", false);
            this.portalCooldown = input.getIntOr("PortalCooldown", 0);
            input.read("UUID", UUIDUtil.CODEC).ifPresent(id -> {
                this.uuid = id;
                this.stringUUID = this.uuid.toString();
            });
            if (!Double.isFinite(this.getX()) || !Double.isFinite(this.getY()) || !Double.isFinite(this.getZ())) {
                throw new IllegalStateException("Entity has invalid position");
            }

            if (Double.isFinite(this.getYRot()) && Double.isFinite(this.getXRot())) {
                this.reapplyPosition();
                this.setRot(this.getYRot(), this.getXRot());
                this.setCustomName(input.read("CustomName", ComponentSerialization.CODEC).orElse(null));
                this.setCustomNameVisible(input.getBooleanOr("CustomNameVisible", false));
                this.setSilent(input.getBooleanOr("Silent", false));
                this.setNoGravity(input.getBooleanOr("NoGravity", false));
                this.setGlowingTag(input.getBooleanOr("Glowing", false));
                this.setTicksFrozen(input.getIntOr("TicksFrozen", 0));
                // Paper start - improve visual fire API
                input.getString("Paper.FireOverride").ifPresentOrElse(
                    override -> {
                        try {
                            this.visualFire = net.kyori.adventure.util.TriState.valueOf(override);
                        } catch (final Exception ignored) {
                            LOGGER.error("Unknown fire override {} for {}", override, this);
                        }
                    },
                    () -> this.visualFire = input.read("HasVisualFire", Codec.BOOL)
                        .map(net.kyori.adventure.util.TriState::byBoolean)
                        .orElse(net.kyori.adventure.util.TriState.NOT_SET)
                );
                // Paper end
                this.customData = input.read("data", CustomData.CODEC).orElse(CustomData.EMPTY);
                this.tags.clear();
                input.read("Tags", TAG_LIST_CODEC).ifPresent(this.tags::addAll);
                this.readAdditionalSaveData(input);
                if (this.repositionEntityAfterLoad()) {
                    this.reapplyPosition();
                }
            } else {
                throw new IllegalStateException("Entity has invalid rotation");
            }
            // CraftBukkit start
            // Spigot start
            if (this instanceof net.minecraft.world.entity.LivingEntity) {
                this.totalEntityAge = input.getIntOr("Spigot.ticksLived", 0); // Paper
            }
            // Spigot end
            this.persist = input.getBooleanOr("Bukkit.persist", true);
            this.visibleByDefault = input.getBooleanOr("Bukkit.visibleByDefault", true);
            // SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
            this.maxAirTicks = input.getIntOr("Bukkit.MaxAirSupply",this.maxAirTicks);
            // CraftBukkit end

            // CraftBukkit start
            // Paper - move world parsing/loading to PlayerList#placeNewPlayer
            this.getBukkitEntity().readBukkitValues(input);
            input.read("Bukkit.invisible", Codec.BOOL).ifPresent(bukkitInvisible -> {
                this.setInvisible(bukkitInvisible);
                this.persistentInvisibility = bukkitInvisible;
            });
            // CraftBukkit end

            // Paper start
            Optional<Vec3> originVec = input.read("Paper.Origin", Vec3.CODEC);
            if (originVec.isPresent()) {
                this.originWorld = input.read("Paper.OriginWorld", UUIDUtil.CODEC)
                    .orElse(this.level != null ? this.level.getWorld().getUID() : null);
                this.origin = originVec.get();
            }

            spawnedViaMobSpawner = input.getBooleanOr("Paper.FromMobSpawner", false); // Restore entity's from mob spawner status
            fromNetherPortal = input.getBooleanOr("Paper.FromNetherPortal", false);
            input.getString("Paper.SpawnReason").ifPresent(spawnReasonName -> {
                try {
                    spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.valueOf(spawnReasonName);
                } catch (Exception ignored) {
                    LOGGER.error("Unknown SpawnReason " + spawnReasonName + " for " + this);
                }
            });
            if (spawnReason == null) {
                if (spawnedViaMobSpawner) {
                    spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER;
                } else if (this instanceof Mob && (this instanceof net.minecraft.world.entity.animal.Animal || this instanceof net.minecraft.world.entity.animal.fish.AbstractFish) && !((Mob) this).removeWhenFarAway(0.0)) {
                    if (!input.getBooleanOr("PersistenceRequired", false)) {
                        spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL;
                    }
                }
            }
            if (spawnReason == null) {
                spawnReason = org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT;
            }
            freezeLocked = input.getBooleanOr("Paper.FreezeLock", false);
            // Paper end
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Loading entity NBT");
            CrashReportCategory category = report.addCategory("Entity being loaded");
            this.fillCrashReportCategory(category);
            throw new ReportedException(report);
        }
    }

    protected boolean repositionEntityAfterLoad() {
        return true;
    }

    public final @Nullable String getEncodeId() {
        // Paper start - Raw entity serialization API
        return this.getEncodeId(false);
    }

    public final @Nullable String getEncodeId(final boolean includeNonSaveable) {
        if (!includeNonSaveable && !this.getType().canSerialize()) {
            // Paper end - Raw entity serialization API
            return null;
        }

        ResourceKey<EntityType<?>> typeId = this.typeHolder().unwrapKey().orElseThrow(() -> new IllegalStateException("Unregistered entity"));
        return typeId.identifier().toString();
    }

    protected abstract void readAdditionalSaveData(ValueInput input);

    // CraftBukkit start - allow excluding certain data when saving
    protected void addAdditionalSaveData(ValueOutput output, boolean includeAll) {
        this.addAdditionalSaveData(output);
    }
    // CraftBukkit end

    protected abstract void addAdditionalSaveData(ValueOutput output);

    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemLike resource) {
        return this.spawnAtLocation(level, new ItemStack(resource), 0.0F);
    }

    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemStack itemStack) {
        return this.spawnAtLocation(level, itemStack, 0.0F);
    }

    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemStack itemStack, final Vec3 offset) {
        // Paper start - Restore vanilla drops behavior
        return this.spawnAtLocation(level, itemStack, offset, null);
    }

    public record DefaultDrop(Item item, org.bukkit.inventory.ItemStack stack, java.util.function.@Nullable Consumer<ItemStack> dropConsumer) {
        public DefaultDrop(final ItemStack stack, final java.util.function.Consumer<ItemStack> dropConsumer) {
            this(stack.getItem(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(stack), dropConsumer);
        }

        public void runConsumer(final java.util.function.Consumer<org.bukkit.inventory.ItemStack> fallback) {
            if (this.dropConsumer == null || org.bukkit.craftbukkit.inventory.CraftItemType.bukkitToMinecraft(this.stack.getType()) != this.item) {
                fallback.accept(this.stack);
            } else {
                this.dropConsumer.accept(org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(this.stack));
            }
        }
    }

    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemStack itemStack, final Vec3 offset, java.util.function.@Nullable Consumer<? super ItemEntity> delayedAddConsumer) {
        // Paper end - Restore vanilla drops behavior
        if (itemStack.isEmpty()) {
            return null;
        }
        // CraftBukkit start - Capture drops for death event
        if (this instanceof LivingEntity livingEntity && livingEntity.deathDropItems != null) {
            // Paper start - Restore vanilla drops behavior
            livingEntity.deathDropItems.add(new DefaultDrop(itemStack, dropStack -> {
                ItemEntity dropEntity = new ItemEntity(this.level, this.getX() + offset.x, this.getY() + offset.y, this.getZ() + offset.z, dropStack); // stack is copied before consumer
                dropEntity.setDefaultPickUpDelay();
                this.level.addFreshEntity(dropEntity);
                if (delayedAddConsumer != null) delayedAddConsumer.accept(dropEntity);
            }));
            // Paper end - Restore vanilla drops behavior
            return null;
        }
        // CraftBukkit end
        ItemEntity entity = new ItemEntity(level, this.getX() + offset.x, this.getY() + offset.y, this.getZ() + offset.z, itemStack);
        entity.setDefaultPickUpDelay(); // Paper - diff on change (in dropConsumer)
        // Paper start - Call EntityDropItemEvent
        return this.spawnAtLocation(level, entity);
    }

    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemEntity entity) {
        // Paper end - Call EntityDropItemEvent
        // CraftBukkit start
        org.bukkit.event.entity.EntityDropItemEvent event = new org.bukkit.event.entity.EntityDropItemEvent(this.getBukkitEntity(), (org.bukkit.entity.Item) entity.getBukkitEntity());
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return null;
        }
        // CraftBukkit end
        level.addFreshEntity(entity);
        return entity;
    }

    public @Nullable ItemEntity spawnAtLocation(final ServerLevel level, final ItemStack itemStack, final float offset) {
        return this.spawnAtLocation(level, itemStack, new Vec3(0.0, offset, 0.0));
    }

    public boolean isAlive() {
        return !this.isRemoved();
    }

    public boolean isInWall() {
        if (this.noPhysics) {
            return false;
        }

        // Paper start - optimise collisions
        final double reducedWith = (double)(this.dimensions.width() * 0.8F);
        final AABB boundingBox = AABB.ofSize(this.getEyePosition(), reducedWith, 1.0E-6D, reducedWith);
        final Level world = this.level;

        if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.isEmpty(boundingBox)) {
            return false;
        }

        final int minBlockX = Mth.floor(boundingBox.minX);
        final int minBlockY = Mth.floor(boundingBox.minY);
        final int minBlockZ = Mth.floor(boundingBox.minZ);

        final int maxBlockX = Mth.floor(boundingBox.maxX);
        final int maxBlockY = Mth.floor(boundingBox.maxY);
        final int maxBlockZ = Mth.floor(boundingBox.maxZ);

        final int minChunkX = minBlockX >> 4;
        final int minChunkY = minBlockY >> 4;
        final int minChunkZ = minBlockZ >> 4;

        final int maxChunkX = maxBlockX >> 4;
        final int maxChunkY = maxBlockY >> 4;
        final int maxChunkZ = maxBlockZ >> 4;

        final int minSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(world);
        final net.minecraft.world.level.chunk.ChunkSource chunkSource = world.getChunkSource();
        final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int currChunkZ = minChunkZ; currChunkZ <= maxChunkZ; ++currChunkZ) {
            for (int currChunkX = minChunkX; currChunkX <= maxChunkX; ++currChunkX) {
                final net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunkSource.getChunk(currChunkX, currChunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true).getSections();

                for (int currChunkY = minChunkY; currChunkY <= maxChunkY; ++currChunkY) {
                    final int sectionIdx = currChunkY - minSection;
                    if (sectionIdx < 0 || sectionIdx >= sections.length) {
                        continue;
                    }
                    final net.minecraft.world.level.chunk.LevelChunkSection section = sections[sectionIdx];
                    if (section.hasOnlyAir()) {
                        // empty
                        continue;
                    }

                    final net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> blocks = section.getStates();

                    final int minXIterate = currChunkX == minChunkX ? (minBlockX & 15) : 0;
                    final int maxXIterate = currChunkX == maxChunkX ? (maxBlockX & 15) : 15;
                    final int minZIterate = currChunkZ == minChunkZ ? (minBlockZ & 15) : 0;
                    final int maxZIterate = currChunkZ == maxChunkZ ? (maxBlockZ & 15) : 15;
                    final int minYIterate = currChunkY == minChunkY ? (minBlockY & 15) : 0;
                    final int maxYIterate = currChunkY == maxChunkY ? (maxBlockY & 15) : 15;

                    for (int currY = minYIterate; currY <= maxYIterate; ++currY) {
                        final int blockY = currY | (currChunkY << 4);
                        mutablePos.setY(blockY);
                        for (int currZ = minZIterate; currZ <= maxZIterate; ++currZ) {
                            final int blockZ = currZ | (currChunkZ << 4);
                            mutablePos.setZ(blockZ);
                            for (int currX = minXIterate; currX <= maxXIterate; ++currX) {
                                final int blockX = currX | (currChunkX << 4);
                                mutablePos.setX(blockX);

                                final BlockState blockState = blocks.get((currX) | (currZ << 4) | ((currY) << 8));

                                if (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)blockState).moonrise$emptyCollisionShape()
                                    || !blockState.isSuffocating(world, mutablePos)) {
                                    continue;
                                }

                                // Yes, it does not use the Entity context stuff.
                                final VoxelShape collisionShape = blockState.getCollisionShape(world, mutablePos);

                                if (collisionShape.isEmpty()) {
                                    continue;
                                }

                                final AABB toCollide = boundingBox.move(-(double)blockX, -(double)blockY, -(double)blockZ);

                                final AABB singleAABB = ((ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape)collisionShape).moonrise$getSingleAABBRepresentation();
                                if (singleAABB != null) {
                                    if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.voxelShapeIntersect(singleAABB, toCollide)) {
                                        return true;
                                    }
                                    continue;
                                }

                                if (ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.voxelShapeIntersectNoEmpty(collisionShape, toCollide)) {
                                    return true;
                                }
                                continue;
                            }
                        }
                    }
                }
            }
        }

        return false;
        // Paper end - optimise collisions
    }

    public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
        if (!this.level().isClientSide()
            && player.isSecondaryUseActive()
            && this instanceof Leashable leashable
            && leashable.canBeLeashed()
            && this.isAlive()
            && !(this instanceof LivingEntity le && le.isBaby())) {
            List<Leashable> mobsToLeash = Leashable.leashableInArea(this, l -> l.getLeashHolder() == player);
            if (!mobsToLeash.isEmpty()) {
                boolean anyLeashed = false;

                for (Leashable mob : mobsToLeash) {
                    if (mob.canHaveALeashAttachedTo(this)) {
                        // Paper start - PlayerLeashEvent
                        final org.bukkit.event.entity.PlayerLeashEntityEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerLeashEntityEvent(
                            mob,
                            this,
                            player,
                            hand
                        );
                        if (event != null && event.isCancelled()) continue; // If the event was called and cancelled, skip this.
                        // Paper end - PlayerLeashEvent
                        mob.setLeashedTo(this, true);
                        anyLeashed = true;
                    }
                }

                if (anyLeashed) {
                    this.level().gameEvent(GameEvent.ENTITY_ACTION, this.blockPosition(), GameEvent.Context.of(player));
                    this.playSound(SoundEvents.LEAD_TIED);
                    return InteractionResult.SUCCESS_SERVER.withoutItem();
                }
            }
        }

        ItemStack heldItem = player.getItemInHand(hand);
        if (heldItem.is(Items.SHEARS) && this.shearOffAllLeashConnections(player, hand)) { // Paper - PlayerUnleashEntityEvent - pass used hand
            heldItem.hurtAndBreak(1, player, hand);
            return InteractionResult.SUCCESS;
        } else if (this instanceof Mob target
            && heldItem.is(Items.SHEARS)
            && target.canShearEquipment(player)
            && !player.isSecondaryUseActive()
            && target.attemptToShearEquipment(player, hand, heldItem)) {
            return InteractionResult.SUCCESS;
        } else {
            if (this.isAlive() && this instanceof Leashable leashable) {
                if (leashable.getLeashHolder() == player) {
                    if (!this.level().isClientSide()) {
                        // Paper start - EntityUnleashEvent
                        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent(
                            leashable, player, hand, !player.hasInfiniteMaterials(), true
                        )) {
                            return InteractionResult.PASS;
                        }
                        // Paper end - EntityUnleashEvent

                        this.gameEvent(GameEvent.ENTITY_INTERACT, player);
                        this.playSound(SoundEvents.LEAD_UNTIED);
                    }

                    return InteractionResult.SUCCESS.withoutItem();
                }

                ItemStack itemStack = player.getItemInHand(hand);
                if (itemStack.is(Items.LEAD) && !(leashable.getLeashHolder() instanceof Player)) {
                    if (this.level().isClientSide()) {
                        return InteractionResult.CONSUME;
                    }

                    if (leashable.canHaveALeashAttachedTo(player)) {
                        if (leashable.isLeashed()) {
                            // Paper start - EntityUnleashEvent
                            if (!org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent(
                                leashable, player, hand, true, true
                            )) {
                                return InteractionResult.PASS;
                            }
                            // Paper end - EntityUnleashEvent
                            // leashable.dropLeash(); // Paper - EntityUnleashEvent - moved into handlePlayerUnleashEntityEvent
                        }

                        // Paper start - EntityLeashEvent
                        if (org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerLeashEntityEvent(this, player, player, hand).isCancelled()) {
                            ((ServerPlayer) player).connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket(this, leashable.getLeashHolder()));
                            return InteractionResult.PASS;
                        }
                        // Paper end - EntityLeashEvent
                        leashable.setLeashedTo(player, true);
                        this.playSound(SoundEvents.LEAD_TIED);
                        itemStack.shrink(1);
                        return InteractionResult.SUCCESS_SERVER;
                    }
                }
            }

            return InteractionResult.PASS;
        }
    }

    public boolean shearOffAllLeashConnections(final @Nullable Player player) {
        // Paper start - EntityUnleashEvent - overload
        return this.shearOffAllLeashConnections(player, null);
    }

    public boolean shearOffAllLeashConnections(final @Nullable Player player, final @Nullable InteractionHand hand) {
        // Paper end - EntityUnleashEvent - overload
        boolean dropped = this.dropAllLeashConnections(player, hand); // Paper - EntityUnleashEvent - overload
        if (dropped && this.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, this.blockPosition(), SoundEvents.SHEARS_SNIP, player != null ? player.getSoundSource() : this.getSoundSource());
        }

        return dropped;
    }

    public boolean dropAllLeashConnections(final @Nullable Player player) {
        // Paper start - EntityUnleashEvent - overload
        return this.dropAllLeashConnections(player, null);
    }

    public boolean dropAllLeashConnections(final @Nullable Player player, final @Nullable InteractionHand hand) {
        // Paper end - EntityUnleashEvent - overload
        List<Leashable> leashables = Leashable.leashableLeashedTo(this);
        boolean dropped = false; // Paper - EntityUnleashEvent - compute flag later, events might prevent unleashing all connected leashables.
        if (this instanceof Leashable leashableThis && leashableThis.isLeashed()) {
            // Paper start - EntityUnleashEvent
            dropped |= org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent(
                this,
                player,
                hand,
                true,
                true
            );
            // Paper end - EntityUnleashEvent
            // leashableThis.dropLeash(); // Paper - EntityUnleashEvent - moved into handlePlayerUnleashEntityEvent
            // dropped = true; // Paper - EntityUnleashEvent - moved above
        }

        for (Leashable leashable : leashables) {
            // Paper start - EntityUnleashEvent
            dropped |= org.bukkit.craftbukkit.event.CraftEventFactory.handlePlayerUnleashEntityEvent( // Update flag here, if any entity was unleashed, set to true.
                leashable,
                player,
                hand,
                true,
                true
            );
            // leashable.dropLeash(); // Paper - EntityUnleashEvent - moved into handlePlayerUnleashEntityEvent
            // Paper end - EntityUnleashEvent
        }

        if (dropped) {
            this.gameEvent(GameEvent.SHEAR, player);
            return true;
        } else {
            return false;
        }
    }

    public boolean canCollideWith(final Entity entity) {
        return entity.canBeCollidedWith(this) && !this.isPassengerOfSameVehicle(entity);
    }

    public boolean canBeCollidedWith(final @Nullable Entity other) {
        return false;
    }

    public void rideTick() {
        this.setDeltaMovement(Vec3.ZERO);
        this.tick();
        if (this.isPassenger()) {
            this.getVehicle().positionRider(this);
        }
    }

    public final void positionRider(final Entity passenger) {
        if (this.hasPassenger(passenger)) {
            this.positionRider(passenger, Entity::setPos);
        }
    }

    protected void positionRider(final Entity passenger, final Entity.MoveFunction moveFunction) {
        Vec3 position = this.getPassengerRidingPosition(passenger);
        Vec3 offset = passenger.getVehicleAttachmentPoint(this);
        moveFunction.accept(passenger, position.x - offset.x, position.y - offset.y, position.z - offset.z);
    }

    public void onPassengerTurned(final Entity passenger) {
    }

    public Vec3 getVehicleAttachmentPoint(final Entity vehicle) {
        return this.getAttachments().get(EntityAttachment.VEHICLE, 0, this.yRot);
    }

    public Vec3 getPassengerRidingPosition(final Entity passenger) {
        return this.position().add(this.getPassengerAttachmentPoint(passenger, this.dimensions, 1.0F));
    }

    protected Vec3 getPassengerAttachmentPoint(final Entity passenger, final EntityDimensions dimensions, final float scale) {
        return getDefaultPassengerAttachmentPoint(this, passenger, dimensions.attachments());
    }

    protected static Vec3 getDefaultPassengerAttachmentPoint(final Entity vehicle, final Entity passenger, final EntityAttachments attachments) {
        int passengerIndex = vehicle.getPassengers().indexOf(passenger);
        return attachments.getClamped(EntityAttachment.PASSENGER, passengerIndex, vehicle.yRot);
    }

    public final boolean startRiding(final Entity entity) {
        return this.startRiding(entity, false, true);
    }

    public boolean showVehicleHealth() {
        return this instanceof LivingEntity;
    }

    public boolean startRiding(final Entity entityToRide, final boolean force, final boolean sendEventAndTriggers) {
        if (entityToRide == this.vehicle || entityToRide.level != this.level) { // Paper - Ensure entity passenger world matches ridden entity (bad plugins)
            return false;
        }

        if (!entityToRide.couldAcceptPassenger()) {
            return false;
        }

        if (!force && !this.level().isClientSide() && !entityToRide.type.canSerialize()) { // SPIGOT-7947: Allow force riding all entities
            return false;
        }

        for (Entity vehicleEntity = entityToRide; vehicleEntity.vehicle != null; vehicleEntity = vehicleEntity.vehicle) {
            if (vehicleEntity.vehicle == this) {
                return false;
            }
        }

        if (force || this.canRide(entityToRide) && entityToRide.canAddPassenger(this)) {
            if (this.valid) { // Folia - region threading - suppress entire event logic during worldgen
            // CraftBukkit start
            if (entityToRide.getBukkitEntity() instanceof org.bukkit.entity.Vehicle && this.getBukkitEntity() instanceof org.bukkit.entity.LivingEntity) {
                org.bukkit.event.vehicle.VehicleEnterEvent event = new org.bukkit.event.vehicle.VehicleEnterEvent((org.bukkit.entity.Vehicle) entityToRide.getBukkitEntity(), this.getBukkitEntity());
                // Suppress during worldgen
                if (this.valid) {
                    org.bukkit.Bukkit.getPluginManager().callEvent(event);
                }
                if (event.isCancelled()) {
                    return false;
                }
            }

            org.bukkit.event.entity.EntityMountEvent event = new org.bukkit.event.entity.EntityMountEvent(this.getBukkitEntity(), entityToRide.getBukkitEntity());
            // Suppress during worldgen
            if (this.valid) {
                org.bukkit.Bukkit.getPluginManager().callEvent(event);
            }
            if (event.isCancelled()) {
                return false;
            }
            // CraftBukkit end
            } // Folia - region threading - suppress entire event logic during worldgen
            if (this.isPassenger()) {
                this.stopRiding();
            }

            this.setPose(Pose.STANDING);
            this.vehicle = entityToRide;
            this.vehicle.addPassenger(this);
            if (sendEventAndTriggers) {
                this.level().gameEvent(this, GameEvent.ENTITY_MOUNT, this.vehicle.position);
                entityToRide.getIndirectPassengersStream()
                    .filter(e -> e instanceof ServerPlayer)
                    .forEach(player -> CriteriaTriggers.START_RIDING_TRIGGER.trigger((ServerPlayer)player));
            }

            return true;
        } else {
            return false;
        }
    }

    protected boolean canRide(final Entity vehicle) {
        return !this.isShiftKeyDown() && this.boardingCooldown <= 0;
    }

    public void ejectPassengers() {
        for (int i = this.passengers.size() - 1; i >= 0; i--) {
            this.passengers.get(i).stopRiding();
        }
    }

    public void removeVehicle() {
        // Paper start - Force entity dismount during teleportation
        this.removeVehicle(false);
    }

    public void removeVehicle(boolean suppressCancellation) {
        // Paper end - Force entity dismount during teleportation
        if (this.vehicle != null) {
            Entity oldVehicle = this.vehicle;
            this.vehicle = null;
            if (!oldVehicle.removePassenger(this, suppressCancellation)) this.vehicle = oldVehicle; // CraftBukkit // Paper - Force entity dismount during teleportation
            Entity.RemovalReason removalReason = this.getRemovalReason();
            if (removalReason == null || removalReason.shouldDestroy()) {
                if (!this.hasNullCallback()) this.level().gameEvent(this, GameEvent.ENTITY_DISMOUNT, oldVehicle.position); // Folia - region threading - do not fire game events for entities not added
            }
        }
    }

    public void stopRiding() {
        // Paper start - Force entity dismount during teleportation
        this.stopRiding(false);
    }

    public void stopRiding(boolean suppressCancellation) {
        this.removeVehicle(suppressCancellation);
        // Paper end - Force entity dismount during teleportation
    }

    protected void addPassenger(final Entity passenger) {
        if (passenger.getVehicle() != this) {
            throw new IllegalStateException("Use x.startRiding(y), not y.addPassenger(x)");
        }

        if (this.passengers.isEmpty()) {
            this.passengers = ImmutableList.of(passenger);
        } else {
            List<Entity> newPassengers = Lists.newArrayList(this.passengers);
            if (!this.level().isClientSide() && passenger instanceof Player && !(this.getFirstPassenger() instanceof Player)) {
                newPassengers.add(0, passenger);
            } else {
                newPassengers.add(passenger);
            }

            this.passengers = ImmutableList.copyOf(newPassengers);
        }
    }

    // Paper start - Force entity dismount during teleportation
    protected boolean removePassenger(final Entity passenger) {
        return this.removePassenger(passenger, false);
    }

    protected boolean removePassenger(final Entity passenger, final boolean suppressCancellation) { // CraftBukkit
        // Paper end - Force entity dismount during teleportation
        if (passenger.getVehicle() == this) {
            throw new IllegalStateException("Use x.stopRiding(y), not y.removePassenger(x)");
        }
        // CraftBukkit start
        if (this.valid) { // Folia - region threading - suppress entire event logic during worldgen
        org.bukkit.craftbukkit.entity.CraftEntity craft = (org.bukkit.craftbukkit.entity.CraftEntity) passenger.getBukkitEntity().getVehicle();
        Entity orig = craft == null ? null : craft.getHandle();
        if (this.getBukkitEntity() instanceof org.bukkit.entity.Vehicle && passenger.getBukkitEntity() instanceof org.bukkit.entity.LivingEntity) {
            org.bukkit.event.vehicle.VehicleExitEvent event = new org.bukkit.event.vehicle.VehicleExitEvent(
                    (org.bukkit.entity.Vehicle) this.getBukkitEntity(),
                    (org.bukkit.entity.LivingEntity) passenger.getBukkitEntity(), !suppressCancellation // Paper - Force entity dismount during teleportation
            );
            // Suppress during worldgen
            if (this.valid) {
                org.bukkit.Bukkit.getPluginManager().callEvent(event);
            }
            org.bukkit.craftbukkit.entity.CraftEntity craftn = (org.bukkit.craftbukkit.entity.CraftEntity) passenger.getBukkitEntity().getVehicle();
            Entity n = craftn == null ? null : craftn.getHandle();
            if (event.isCancelled() || n != orig) {
                return false;
            }
        }

        org.bukkit.event.entity.EntityDismountEvent event = new org.bukkit.event.entity.EntityDismountEvent(passenger.getBukkitEntity(), this.getBukkitEntity(), !suppressCancellation); // Paper - Force entity dismount during teleportation
        // Suppress during worldgen
        if (this.valid) {
            org.bukkit.Bukkit.getPluginManager().callEvent(event);
        }
        if (event.isCancelled()) {
            return false;
        }
        // CraftBukkit end
        } // Folia - region threading - suppress entire event logic during worldgen
        if (this.passengers.size() == 1 && this.passengers.get(0) == passenger) {
            this.passengers = ImmutableList.of();
        } else {
            this.passengers = this.passengers.stream().filter(p -> p != passenger).collect(ImmutableList.toImmutableList());
        }

        passenger.boardingCooldown = 60;
        return true; // CraftBukkit
    }

    protected boolean canAddPassenger(final Entity passenger) {
        return this.passengers.isEmpty();
    }

    protected boolean couldAcceptPassenger() {
        return true;
    }

    public final boolean isInterpolating() {
        return this.getInterpolation() != null && this.getInterpolation().hasActiveInterpolation();
    }

    public final void moveOrInterpolateTo(final Vec3 position, final float yRot, final float xRot) {
        this.moveOrInterpolateTo(Optional.of(position), Optional.of(yRot), Optional.of(xRot));
    }

    public final void moveOrInterpolateTo(final float yRot, final float xRot) {
        this.moveOrInterpolateTo(Optional.empty(), Optional.of(yRot), Optional.of(xRot));
    }

    public final void moveOrInterpolateTo(final Vec3 position) {
        this.moveOrInterpolateTo(Optional.of(position), Optional.empty(), Optional.empty());
    }

    public final void moveOrInterpolateTo(final Optional<Vec3> position, final Optional<Float> yRot, final Optional<Float> xRot) {
        InterpolationHandler interpolationHandler = this.getInterpolation();
        if (interpolationHandler != null) {
            interpolationHandler.interpolateTo(
                position.orElse(interpolationHandler.position()), yRot.orElse(interpolationHandler.yRot()), xRot.orElse(interpolationHandler.xRot())
            );
        } else {
            position.ifPresent(this::setPos);
            yRot.ifPresent(y -> this.setYRot(y % 360.0F));
            xRot.ifPresent(x -> this.setXRot(x % 360.0F));
        }
    }

    public @Nullable InterpolationHandler getInterpolation() {
        return null;
    }

    public void lerpHeadTo(final float yRot, final int steps) {
        this.setYHeadRot(yRot);
    }

    public float getPickRadius() {
        return 0.0F;
    }

    public Vec3 getLookAngle() {
        return this.calculateViewVector(this.getXRot(), this.getYRot());
    }

    public Vec3 getHeadLookAngle() {
        return this.calculateViewVector(this.getXRot(), this.getYHeadRot());
    }

    public Vec3 getHandHoldingItemAngle(final Item item) {
        if (!(this instanceof Player player)) {
            return Vec3.ZERO;
        } else {
            boolean itemOnlyInOffhand = player.getOffhandItem().is(item) && !player.getMainHandItem().is(item);
            HumanoidArm itemArm = itemOnlyInOffhand ? player.getMainArm().getOpposite() : player.getMainArm();
            return this.calculateViewVector(0.0F, this.getYRot() + (itemArm == HumanoidArm.RIGHT ? 80 : -80)).scale(0.5);
        }
    }

    public Vec2 getRotationVector() {
        return new Vec2(this.getXRot(), this.getYRot());
    }

    public Vec3 getForward() {
        return Vec3.directionFromRotation(this.getRotationVector());
    }

    public void setAsInsidePortal(final Portal portal, final BlockPos pos) {
        if (this.isOnPortalCooldown()) {
            this.setPortalCooldown();
        } else {
            if (this.portalProcess == null || !this.portalProcess.isSamePortal(portal)) {
                this.portalProcess = new PortalProcessor(portal, pos.immutable());
            } else if (!this.portalProcess.isInsidePortalThisTick()) {
                this.portalProcess.updateEntryPosition(pos.immutable());
                this.portalProcess.setAsInsidePortalThisTick(true);
            }
        }
    }

    public boolean handlePortal() { // Folia - region threading - public, ret type -> boolean
        if (this.level() instanceof ServerLevel level) {
            this.processPortalCooldown();
            if (this.portalProcess != null) {
                if (this.portalProcess.processPortalTeleportation(level, this, this.canUsePortal(false))) {
                    ProfilerFiller profiler = Profiler.get();
                    profiler.push("portal");
                    this.setPortalCooldown();
                    // Folia start - region threading
                    try {
                        return this.portalProcess.portalAsync(level, this);
                    } finally {
                        profiler.pop();
                    }
                    // Folia end - region threading
                } else if (this.portalProcess.hasExpired()) {
                    this.portalProcess = null;
                }
            }
        }

        return false; // Folia - region threading
    }

    public int getDimensionChangingDelay() {
        Entity firstPassenger = this.getFirstPassenger();
        return firstPassenger instanceof ServerPlayer ? firstPassenger.getDimensionChangingDelay() : 300;
    }

    public void lerpMotion(final Vec3 movement) {
        this.setDeltaMovement(movement);
    }

    public void handleDamageEvent(final DamageSource source) {
    }

    public void handleEntityEvent(final byte id) {
        switch (id) {
            case 53:
                HoneyBlock.showSlideParticles(this);
        }
    }

    public void animateHurt(final float direction) {
    }

    public boolean isOnFire() {
        boolean isClientSide = this.level() != null && this.level().isClientSide();
        return !this.fireImmune() && (this.remainingFireTicks > 0 || isClientSide && this.getSharedFlag(FLAG_ONFIRE));
    }

    public boolean isPassenger() {
        return this.getVehicle() != null;
    }

    public boolean isVehicle() {
        return !this.passengers.isEmpty();
    }

    public boolean dismountsUnderwater() {
        return this.is(EntityTypeTags.DISMOUNTS_UNDERWATER);
    }

    public boolean canControlVehicle() {
        return !this.is(EntityTypeTags.NON_CONTROLLING_RIDER);
    }

    public void setShiftKeyDown(final boolean shiftKeyDown) {
        this.setSharedFlag(FLAG_SHIFT_KEY_DOWN, shiftKeyDown);
    }

    public boolean isShiftKeyDown() {
        return this.getSharedFlag(FLAG_SHIFT_KEY_DOWN);
    }

    public boolean isSteppingCarefully() {
        return this.isShiftKeyDown();
    }

    public boolean isSuppressingBounce() {
        return this.isShiftKeyDown();
    }

    public boolean isDiscrete() {
        return this.isShiftKeyDown();
    }

    public boolean isDescending() {
        return this.isShiftKeyDown();
    }

    public boolean isCrouching() {
        return this.hasPose(net.minecraft.world.entity.Pose.CROUCHING);
    }

    public boolean isSprinting() {
        return this.getSharedFlag(FLAG_SPRINTING);
    }

    public void setSprinting(final boolean isSprinting) {
        this.setSharedFlag(FLAG_SPRINTING, isSprinting);
    }

    public boolean isSwimming() {
        return this.getSharedFlag(FLAG_SWIMMING);
    }

    public boolean isVisuallySwimming() {
        return this.hasPose(net.minecraft.world.entity.Pose.SWIMMING);
    }

    public boolean isVisuallyCrawling() {
        return this.isVisuallySwimming() && !this.isInWater();
    }

    public void setSwimming(final boolean swimming) {
        // CraftBukkit start
        if (this.valid && this.isSwimming() != swimming && this instanceof net.minecraft.world.entity.LivingEntity) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callToggleSwimEvent((net.minecraft.world.entity.LivingEntity) this, swimming).isCancelled()) {
                return;
            }
        }
        // CraftBukkit end
        this.setSharedFlag(FLAG_SWIMMING, swimming);
    }

    public final boolean hasGlowingTag() {
        return this.hasGlowingTag;
    }

    public final void setGlowingTag(final boolean value) {
        this.hasGlowingTag = value;
        this.setSharedFlag(FLAG_GLOWING, this.isCurrentlyGlowing());
    }

    public boolean isCurrentlyGlowing() {
        return this.level().isClientSide() ? this.getSharedFlag(FLAG_GLOWING) : this.hasGlowingTag;
    }

    public boolean isInvisible() {
        return this.getSharedFlag(FLAG_INVISIBLE);
    }

    public boolean isInvisibleTo(final Player player) {
        if (player.isSpectator()) {
            return false;
        }

        Team team = this.getTeam();
        return (team == null || player == null || player.getTeam() != team || !team.canSeeFriendlyInvisibles()) && this.isInvisible();
    }

    public boolean isOnRails() {
        return false;
    }

    public void updateDynamicGameEventListener(final BiConsumer<DynamicGameEventListener<?>, ServerLevel> action) {
    }

    public @Nullable PlayerTeam getTeam() {
        // Folia start - region threading
        if (true) {
            return null;
        }
        // Folia end - region threading
        if (!this.level().paperConfig().scoreboards.allowNonPlayerEntitiesOnScoreboards && !(this instanceof Player)) { return null; } // Paper - Perf: Disable Scoreboards for non players by default
        return this.level().getScoreboard().getPlayersTeam(this.getScoreboardName());
    }

    public final boolean isAlliedTo(final @Nullable Entity other) {
        return other != null && (this == other || this.considersEntityAsAlly(other) || other.considersEntityAsAlly(this));
    }

    protected boolean considersEntityAsAlly(final Entity other) {
        return this.isAlliedTo(other.getTeam());
    }

    public boolean isAlliedTo(final @Nullable Team other) {
        return this.getTeam() != null && this.getTeam().isAlliedTo(other);
    }

    public void setInvisible(final boolean invisible) {
        // CraftBukkit - start
        if (!this.persistentInvisibility) { // Prevent Minecraft from removing our invisibility flag
            this.setSharedFlag(FLAG_INVISIBLE, invisible);
        }
        // CraftBukkit - end
    }

    public boolean getSharedFlag(final @Entity.Flags int flag) {
        return (this.entityData.get(DATA_SHARED_FLAGS_ID) & 1 << flag) != 0;
    }

    public void setSharedFlag(final @Entity.Flags int flag, final boolean value) {
        byte currentValue = this.entityData.get(DATA_SHARED_FLAGS_ID);
        if (value) {
            this.entityData.set(DATA_SHARED_FLAGS_ID, (byte)(currentValue | 1 << flag));
        } else {
            this.entityData.set(DATA_SHARED_FLAGS_ID, (byte)(currentValue & ~(1 << flag)));
        }
    }

    public int getMaxAirSupply() {
        return this.maxAirTicks; // CraftBukkit - SPIGOT-6907: re-implement LivingEntity#setMaximumAir()
    }

    public int getAirSupply() {
        return this.entityData.get(DATA_AIR_SUPPLY_ID);
    }

    public void setAirSupply(final int supply) {
        // CraftBukkit start
        org.bukkit.event.entity.EntityAirChangeEvent event = new org.bukkit.event.entity.EntityAirChangeEvent(this.getBukkitEntity(), supply);
        // Suppress during worldgen
        if (this.valid) {
            event.getEntity().getServer().getPluginManager().callEvent(event);
        }
        if (event.isCancelled() && this.getAirSupply() != supply) {
            if (this instanceof ServerPlayer player) {
                this.resendPossiblyDesyncedDataValues(java.util.List.of(DATA_AIR_SUPPLY_ID), player); // todo is that even needed?
            }
            return;
        }
        this.entityData.set(DATA_AIR_SUPPLY_ID, event.getAmount());
        // CraftBukkit end
    }

    public void clearFreeze() {
        if (this.freezeLocked) return; // Paper - Freeze Tick Lock API
        this.setTicksFrozen(0);
    }

    public int getTicksFrozen() {
        return this.entityData.get(DATA_TICKS_FROZEN);
    }

    public void setTicksFrozen(final int ticks) {
        this.entityData.set(DATA_TICKS_FROZEN, ticks);
    }

    public float getPercentFrozen() {
        int ticksToFreeze = this.getTicksRequiredToFreeze();
        return (float)Math.min(this.getTicksFrozen(), ticksToFreeze) / ticksToFreeze;
    }

    public boolean isFullyFrozen() {
        return this.getTicksFrozen() >= this.getTicksRequiredToFreeze();
    }

    public int getTicksRequiredToFreeze() {
        return 140;
    }

    public void thunderHit(final ServerLevel level, final LightningBolt lightningBolt) {
        this.setRemainingFireTicks(this.remainingFireTicks + 1);
        // CraftBukkit start
        final org.bukkit.entity.Entity thisBukkitEntity = this.getBukkitEntity();
        final org.bukkit.entity.Entity stormBukkitEntity = lightningBolt.getBukkitEntity();
        final org.bukkit.plugin.PluginManager pluginManager = org.bukkit.Bukkit.getPluginManager();
        // CraftBukkit end
        if (this.remainingFireTicks == 0) {
            // CraftBukkit start - Call a combust event when lightning strikes
            org.bukkit.event.entity.EntityCombustByEntityEvent entityCombustEvent = new org.bukkit.event.entity.EntityCombustByEntityEvent(stormBukkitEntity, thisBukkitEntity, 8.0F);
            pluginManager.callEvent(entityCombustEvent);
            if (!entityCombustEvent.isCancelled()) {
                this.igniteForSeconds(entityCombustEvent.getDuration(), false);
            // Paper start - fix EntityCombustEvent cancellation
            } else {
                this.setRemainingFireTicks(this.remainingFireTicks - 1);
            // Paper end - fix EntityCombustEvent cancellation
            }
            // CraftBukkit end
        }

        // CraftBukkit start
        final DamageSource damageSource = this.damageSources().lightningBolt().eventEntityDamager(lightningBolt);
        if (thisBukkitEntity instanceof org.bukkit.entity.Hanging) {
            org.bukkit.event.hanging.HangingBreakByEntityEvent hangingEvent = new org.bukkit.event.hanging.HangingBreakByEntityEvent((org.bukkit.entity.Hanging) thisBukkitEntity, stormBukkitEntity, new org.bukkit.craftbukkit.damage.CraftDamageSource(damageSource));
            if (!hangingEvent.callEvent()) {
                return;
            }
        }

        if (this.fireImmune()) {
            return;
        }

        if (!this.hurtServer(level, damageSource, 5.0F)) { // Paper - fix DamageSource API
            return;
        }
        // CraftBukkit end
    }

    public void onAboveBubbleColumn(final boolean dragDown, final BlockPos pos) {
        handleOnAboveBubbleColumn(this, dragDown, pos);
    }

    protected static void handleOnAboveBubbleColumn(final Entity entity, final boolean dragDown, final BlockPos pos) {
        Vec3 movement = entity.getDeltaMovement();
        double yd;
        if (dragDown) {
            yd = Math.max(-0.9, movement.y - 0.03);
        } else {
            yd = Math.min(1.8, movement.y + 0.1);
        }

        entity.setDeltaMovement(movement.x, yd, movement.z);
        sendBubbleColumnParticles(entity.level, pos);
    }

    protected static void sendBubbleColumnParticles(final Level level, final BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            RandomSource random = level.getRandom();

            for (int i = 0; i < 2; i++) {
                serverLevel.sendParticles(
                    ParticleTypes.SPLASH, pos.getX() + random.nextDouble(), pos.getY() + 1, pos.getZ() + random.nextDouble(), 1, 0.0, 0.0, 0.0, 1.0
                );
                serverLevel.sendParticles(
                    ParticleTypes.BUBBLE, pos.getX() + random.nextDouble(), pos.getY() + 1, pos.getZ() + random.nextDouble(), 1, 0.0, 0.01, 0.0, 0.2
                );
            }
        }
    }

    public void onInsideBubbleColumn(final boolean dragDown) {
        handleOnInsideBubbleColumn(this, dragDown);
    }

    protected static void handleOnInsideBubbleColumn(final Entity entity, final boolean dragDown) {
        Vec3 movement = entity.getDeltaMovement();
        double yd;
        if (dragDown) {
            yd = Math.max(-0.3, movement.y - 0.03);
        } else {
            yd = Math.min(0.7, movement.y + 0.06);
        }

        entity.setDeltaMovement(movement.x, yd, movement.z);
        entity.resetFallDistance();
    }

    // Paper start
    public boolean killedEntityPreEvent(final ServerLevel level, final LivingEntity entity, final DamageSource source) {
        return true;
    }
    // Paper end

    public boolean killedEntity(final ServerLevel level, final LivingEntity entity, final DamageSource source) {
        return true;
    }

    public void checkFallDistanceAccumulation() {
        if (this.getDeltaMovement().y() > -0.5 && this.fallDistance > 1.0) {
            this.fallDistance = 1.0;
        }
    }

    public void resetFallDistance() {
        this.fallDistance = 0.0;
    }

    protected void moveTowardsClosestSpace(final double x, final double y, final double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        Vec3 delta = new Vec3(x - pos.getX(), y - pos.getY(), z - pos.getZ());
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        Direction closestDirection = Direction.UP;
        double closest = Double.MAX_VALUE;

        for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
            neighborPos.setWithOffset(pos, direction);
            if (!this.level().getBlockState(neighborPos).isCollisionShapeFullBlock(this.level(), neighborPos)) {
                double d = delta.get(direction.getAxis());
                double orientedDelta = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - d : d;
                if (orientedDelta < closest) {
                    closest = orientedDelta;
                    closestDirection = direction;
                }
            }
        }

        float speed = this.random.nextFloat() * 0.2F + 0.1F;
        float step = closestDirection.getAxisDirection().getStep();
        Vec3 scaledMovement = this.getDeltaMovement().scale(0.75);
        if (closestDirection.getAxis() == Direction.Axis.X) {
            this.setDeltaMovement(step * speed, scaledMovement.y, scaledMovement.z);
        } else if (closestDirection.getAxis() == Direction.Axis.Y) {
            this.setDeltaMovement(scaledMovement.x, step * speed, scaledMovement.z);
        } else if (closestDirection.getAxis() == Direction.Axis.Z) {
            this.setDeltaMovement(scaledMovement.x, scaledMovement.y, step * speed);
        }
    }

    public void makeStuckInBlock(final BlockState blockState, final Vec3 speedMultiplier) {
        this.resetFallDistance();
        this.stuckSpeedMultiplier = speedMultiplier;
    }

    private static Component removeAction(final Component component) {
        MutableComponent result = component.plainCopy().setStyle(component.getStyle().withClickEvent(null));

        for (Component s : component.getSiblings()) {
            result.append(removeAction(s));
        }

        return result;
    }

    @Override
    public Component getName() {
        Component customName = this.getCustomName();
        return customName != null ? removeAction(customName) : this.getTypeName();
    }

    protected Component getTypeName() {
        return this.type.getDescription();
    }

    public boolean is(final Entity other) {
        return this == other;
    }

    public float getYHeadRot() {
        return 0.0F;
    }

    public void setYHeadRot(final float yHeadRot) {
    }

    public void setYBodyRot(final float yBodyRot) {
    }

    public boolean isAttackable() {
        return true;
    }

    public boolean skipAttackInteraction(final Entity source) {
        return false;
    }

    @Override
    public String toString() {
        String levelId = this.level() == null ? "~NULL~" : this.level().toString();
        return this.removalReason != null
            ? String.format(
                Locale.ROOT,
                "%s['%s'/%d, uuid='%s', l='%s', x=%.2f, y=%.2f, z=%.2f, cpos=%s, tl=%d, v=%b, removed=%s]", // Paper - add more info
                this.getClass().getSimpleName(),
                this.getPlainTextName(),
                this.id,
                this.uuid, // Paper - add more info
                levelId,
                this.getX(),
                this.getY(),
                this.getZ(),
                this.chunkPosition(), this.tickCount, this.valid, // Paper - add more info
                this.removalReason
            )
            : String.format(
                Locale.ROOT,
                "%s['%s'/%d, uuid='%s', l='%s', x=%.2f, y=%.2f, z=%.2f, cpos=%s, tl=%d, v=%b]", // Paper - add more info
                this.getClass().getSimpleName(),
                this.getPlainTextName(),
                this.id,
                this.uuid, // Paper - add more info
                levelId,
                this.getX(),
                this.getY(),
                this.getZ(),
                this.chunkPosition(), this.tickCount, this.valid // Paper - add more info
            );
    }

    public final boolean isInvulnerableToBase(final DamageSource source) {
        return this.isRemoved()
            || this.invulnerable && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !source.isCreativePlayer()
            || source.is(DamageTypeTags.IS_FIRE) && this.fireImmune()
            || source.is(DamageTypeTags.IS_FALL) && this.is(EntityTypeTags.FALL_DAMAGE_IMMUNE);
    }

    public boolean isInvulnerable() {
        return this.invulnerable;
    }

    public boolean isInvulnerableToPiercingWeapon() {
        return this.isInvulnerable();
    }

    public void setInvulnerable(final boolean invulnerable) {
        this.invulnerable = invulnerable;
    }

    public void copyPosition(final Entity target) {
        this.snapTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
    }

    public void restoreFrom(final Entity oldEntity) {
        // Paper start - Forward CraftEntity in teleport command
        org.bukkit.craftbukkit.entity.CraftEntity bukkitEntity = oldEntity.bukkitEntity;
        if (bukkitEntity != null) {
            bukkitEntity.setHandle(this);
            this.bukkitEntity = bukkitEntity;
        }
        // Paper end - Forward CraftEntity in teleport command
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(this.problemPath(), LOGGER)) {
            TagValueOutput entityData = TagValueOutput.createWithContext(reporter, oldEntity.registryAccess());
            oldEntity.saveWithoutId(entityData);
            this.load(TagValueInput.create(reporter, this.registryAccess(), entityData.buildResult()));
        }

        this.portalCooldown = oldEntity.portalCooldown;
        this.portalProcess = oldEntity.portalProcess;
    }

    // Folia start - region threading
    public static class EntityTreeNode {
        @Nullable
        public EntityTreeNode parent;
        public Entity root;
        @Nullable
        public EntityTreeNode[] passengers;

        public EntityTreeNode(EntityTreeNode parent, Entity root) {
            this.parent = parent;
            this.root = root;
        }

        public EntityTreeNode(EntityTreeNode parent, Entity root, EntityTreeNode[] passengers) {
            this.parent = parent;
            this.root = root;
            this.passengers = passengers;
        }

        public List<EntityTreeNode> getFullTree() {
            List<EntityTreeNode> ret = new java.util.ArrayList<>();
            ret.add(this);

            // this is just a BFS except we don't remove from head, we just advance down the list
            for (int i = 0; i < ret.size(); ++i) {
                EntityTreeNode node = ret.get(i);

                EntityTreeNode[] passengers = node.passengers;
                if (passengers == null) {
                    continue;
                }
                for (EntityTreeNode passenger : passengers) {
                    ret.add(passenger);
                }
            }

            return ret;
        }

        public void restore() {
            java.util.ArrayDeque<EntityTreeNode> queue = new java.util.ArrayDeque<>();
            queue.add(this);

            EntityTreeNode curr;
            while ((curr = queue.pollFirst()) != null) {
                EntityTreeNode[] passengers = curr.passengers;
                if (passengers == null) {
                    continue;
                }

                List<Entity> newPassengers = new java.util.ArrayList<>();
                for (EntityTreeNode passenger : passengers) {
                    queue.add(passenger);
                    newPassengers.add(passenger.root);
                    passenger.root.vehicle = curr.root;
                }

                curr.root.passengers = ImmutableList.copyOf(newPassengers);
            }
        }

        public void addTracker() {
            for (final EntityTreeNode node : this.getFullTree()) {
                if (node.root.moonrise$getTrackedEntity() != null) {
                    for (final ServerPlayer player : node.root.level.getLocalPlayers()) {
                        node.root.moonrise$getTrackedEntity().updatePlayer(player);
                    }
                }
            }
        }

        public void clearTracker() {
            for (final EntityTreeNode node : this.getFullTree()) {
                if (node.root.moonrise$getTrackedEntity() != null) {
                    node.root.moonrise$getTrackedEntity().moonrise$removeNonTickThreadPlayers();
                    for (final ServerPlayer player : node.root.level.getLocalPlayers()) {
                        node.root.moonrise$getTrackedEntity().removePlayer(player);
                    }
                }
            }
        }

        public void adjustRiders(boolean teleport) {
            java.util.ArrayDeque<EntityTreeNode> queue = new java.util.ArrayDeque<>();
            queue.add(this);

            EntityTreeNode curr;
            while ((curr = queue.pollFirst()) != null) {
                EntityTreeNode[] passengers = curr.passengers;
                if (passengers == null) {
                    continue;
                }

                for (EntityTreeNode passenger : passengers) {
                    queue.add(passenger);
                    curr.root.positionRider(passenger.root, teleport ? Entity::snapTo : Entity::setPos);
                }
            }
        }
    }

    public void repositionAllPassengers(boolean teleport) {
        this.makePassengerTree().adjustRiders(teleport);
    }

    protected EntityTreeNode makePassengerTree() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot read passengers off of the main thread");

        EntityTreeNode root = new EntityTreeNode(null, this);
        java.util.ArrayDeque<EntityTreeNode> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        EntityTreeNode curr;
        while ((curr = queue.pollFirst()) != null) {
            Entity vehicle = curr.root;
            List<Entity> passengers = vehicle.passengers;
            if (passengers.isEmpty()) {
                continue;
            }

            EntityTreeNode[] treePassengers = new EntityTreeNode[passengers.size()];
            curr.passengers = treePassengers;

            for (int i = 0; i < passengers.size(); ++i) {
                Entity passenger = passengers.get(i);
                queue.addLast(treePassengers[i] = new EntityTreeNode(curr, passenger));
            }
        }

        return root;
    }

    protected EntityTreeNode detachPassengers() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot adjust passengers/vehicle off of the main thread");

        EntityTreeNode root = new EntityTreeNode(null, this);
        java.util.ArrayDeque<EntityTreeNode> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        EntityTreeNode curr;
        while ((curr = queue.pollFirst()) != null) {
            Entity vehicle = curr.root;
            List<Entity> passengers = vehicle.passengers;
            if (passengers.isEmpty()) {
                continue;
            }

            vehicle.passengers = ImmutableList.of();

            EntityTreeNode[] treePassengers = new EntityTreeNode[passengers.size()];
            curr.passengers = treePassengers;

            for (int i = 0; i < passengers.size(); ++i) {
                Entity passenger = passengers.get(i);
                passenger.vehicle = null;
                queue.addLast(treePassengers[i] = new EntityTreeNode(curr, passenger));
            }
        }

        return root;
    }

    /**
     * This flag will perform an async load on the chunks determined by
     * the entity's bounding box before teleporting the entity.
     */
    public static final long TELEPORT_FLAG_LOAD_CHUNK = 1L << 0;
    /**
     * This flag requires the entity being teleported to be a root vehicle.
     * Thus, if you want to teleport a non-root vehicle, you must dismount
     * the target entity before calling teleport, otherwise the
     * teleport will be refused.
     */
    public static final long TELEPORT_FLAG_TELEPORT_PASSENGERS = 1L << 1;
    /**
     * The flag will dismount any passengers and dismout from the current vehicle
     * to teleport if and only if dismounting would result in the teleport being allowed.
     */
    public static final long TELEPORT_FLAG_UNMOUNT = 1L << 2;

    protected void placeSingleSync(ServerLevel originWorld, ServerLevel destination, EntityTreeNode treeNode, long teleportFlags) {
        destination.addDuringTeleport(this);
    }

    protected final void placeInAsync(ServerLevel originWorld, ServerLevel destination, long teleportFlags,
                                      EntityTreeNode passengerTree, java.util.function.Consumer<Entity> teleportComplete) {
        Vec3 pos = this.position();
        ChunkPos posChunk = new ChunkPos(
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(pos),
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(pos)
        );

        // ensure the region is always ticking in case of a shutdown
        // otherwise, the shutdown will not be able to complete the shutdown as it requires a ticking region
        Long teleportHoldId = Long.valueOf(TELEPORT_HOLD_TICKET_GEN.getAndIncrement());
        originWorld.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
            TicketType.TELEPORT_HOLD_TICKET, posChunk,
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL,
            teleportHoldId
        );
        final ServerLevel.PendingTeleport pendingTeleport = new ServerLevel.PendingTeleport(passengerTree, pos);
        destination.pushPendingTeleport(pendingTeleport);

        Runnable scheduleEntityJoin = () -> {
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                destination,
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(pos), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(pos),
                () -> {
                    if (!destination.removePendingTeleport(pendingTeleport)) {
                        // shutdown logic placed the entity already, and we are shutting down - do nothing to ensure
                        // we do not produce any errors here
                        return;
                    }
                    originWorld.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                        TicketType.TELEPORT_HOLD_TICKET, posChunk,
                        ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL,
                        teleportHoldId
                    );
                    List<EntityTreeNode> fullTree = passengerTree.getFullTree();
                    for (EntityTreeNode node : fullTree) {
                        node.root.placeSingleSync(originWorld, destination, node, teleportFlags);
                    }

                    // restore passenger tree
                    passengerTree.restore();
                    passengerTree.adjustRiders(true);

                    // invoke post dimension change now
                    for (EntityTreeNode node : fullTree) {
                        node.root.postChangeDimension();
                    }

                    if (teleportComplete != null) {
                        teleportComplete.accept(Entity.this);
                    }
                }
            );
        };

        if ((teleportFlags & TELEPORT_FLAG_LOAD_CHUNK) != 0L) {
            destination.loadChunksForMoveAsync(
                this.getBoundingBox(), ca.spottedleaf.concurrentutil.util.Priority.HIGHER,
                (chunkList) -> {
                    for (net.minecraft.world.level.chunk.ChunkAccess chunk : chunkList) {
                        destination.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
                            TicketType.POST_TELEPORT, chunk.getPos(),
                            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.FULL_LOADED_TICKET_LEVEL,
                            null
                        );
                    }
                    scheduleEntityJoin.run();
                }
            );
        } else {
            scheduleEntityJoin.run();
        }
    }

    protected boolean canTeleportAsync() {
        return !this.hasNullCallback() && !this.isRemoved() && this.isAlive() && (!(this instanceof net.minecraft.world.entity.LivingEntity livingEntity) || !livingEntity.isSleeping());
    }

    // Mojang for whatever reason has started storing positions to cache certain physics properties that entities collide with
    // As usual though, they don't properly do anything to prevent serious desync with respect to the current entity position
    // We add additional logic to reset these before teleporting to prevent issues with them possibly tripping thread checks.
    protected void resetStoredPositions() {
        this.mainSupportingBlockPos = Optional.empty();
        // this is copied from teleportSetPosition
        this.movementThisTick.clear();
    }

    protected void teleportSyncSameRegion(Vec3 pos, Float yaw, Float pitch, Vec3 velocity) {
        if (yaw != null) {
            this.setYRot(yaw.floatValue());
            this.setYHeadRot(yaw.floatValue());
        }
        if (pitch != null) {
            this.setXRot(pitch.floatValue());
        }
        if (velocity != null) {
            this.setDeltaMovement(velocity);
        }
        this.snapTo(pos.x, pos.y, pos.z);
        this.setOldPosAndRot();
        this.resetStoredPositions();
    }

    protected final void transform(TeleportTransition telpeort) {
        PositionMoveRotation move = PositionMoveRotation.calculateAbsolute(
            PositionMoveRotation.of(this), PositionMoveRotation.of(telpeort), telpeort.relatives()
        );
        this.transform(
            move.position(), Float.valueOf(move.yRot()), Float.valueOf(move.xRot()), move.deltaMovement()
        );
    }

    protected void transform(Vec3 pos, Float yaw, Float pitch, Vec3 velocity) {
        if (yaw != null) {
            this.setYRot(yaw.floatValue());
            this.setYHeadRot(yaw.floatValue());
        }
        if (pitch != null) {
            this.setXRot(pitch.floatValue());
        }
        if (velocity != null) {
            this.setDeltaMovement(velocity);
        }
        if (pos != null) {
            this.setPosRaw(pos.x, pos.y, pos.z);
        }
        this.setOldPosAndRot();
    }

    protected final Entity transformForAsyncTeleport(TeleportTransition telpeort) {
        PositionMoveRotation move = PositionMoveRotation.calculateAbsolute(
            PositionMoveRotation.of(this), PositionMoveRotation.of(telpeort), telpeort.relatives()
        );
        return this.transformForAsyncTeleport(
            telpeort.newLevel(), telpeort.position(), Float.valueOf(move.yRot()), Float.valueOf(move.xRot()), move.deltaMovement()
        );
    }

    protected Entity transformForAsyncTeleport(ServerLevel destination, Vec3 pos, Float yaw, Float pitch, Vec3 velocity) {
        this.removeAfterChangingDimensions(); // remove before so that any CBEntity#getHandle call affects this entity before copying

        Entity copy = this.getType().create(destination, EntitySpawnReason.DIMENSION_TRAVEL);
        copy.restoreFrom(this);
        copy.transform(pos, yaw, pitch, velocity);
        // vanilla code used to call remove _after_ copying, and some stuff is required to be after copy - so add hook here
        // for example, clearing of inventory after switching dimensions
        this.postRemoveAfterChangingDimensions();

        return copy;
    }

    public final boolean teleportAsync(TeleportTransition teleportTarget, long teleportFlags,
                                       java.util.function.Consumer<Entity> teleportComplete) {
        PositionMoveRotation move = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(this), PositionMoveRotation.of(teleportTarget), teleportTarget.relatives());

        return this.teleportAsync(
            teleportTarget.newLevel(), move.position(), Float.valueOf(move.yRot()), Float.valueOf(move.xRot()), move.deltaMovement(),
            teleportTarget.cause(), teleportFlags, teleportComplete
        );
    }

    // LightingLuminol start - Prevent teleportAsync calls in move events
    public boolean blockTeleportAsync = false;
    // LightingLuminol end

    public final boolean teleportAsync(ServerLevel destination, Vec3 pos, Float yaw, Float pitch, Vec3 velocity,
                                       org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause, long teleportFlags,
                                       java.util.function.Consumer<Entity> teleportComplete) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot teleport entity async");

        // LightingLuminol start - Prevent teleportAsync calls in move events
        if (meow.bacteriawa.lightingluminol.config.FixesConfig.PreventIncorrectTeleportAsyncCallsDuringMoveEvent.enabled && this.blockTeleportAsync) {
            if (meow.bacteriawa.lightingluminol.config.FixesConfig.PreventIncorrectTeleportAsyncCallsDuringMoveEvent.throwWhenCaught) {
                throw new IllegalStateException("teleportAsync called during move event! Plugin should use PlayerMoveEvent handlers that do not teleport.");
            }
            LOGGER.error("teleportAsync called during move event! Plugin should use PlayerMoveEvent handlers that do not teleport.", new Throwable());
            return false;
        }
        // LightingLuminol end

        if (!ServerLevel.isInSpawnableBounds(new BlockPos(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getBlockX(pos), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getBlockY(pos), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getBlockZ(pos)))) {
            return false;
        }

        if (!this.canTeleportAsync()) {
            return false;
        }
        this.getBukkitEntity(); // force bukkit entity to be created before TPing
        if ((teleportFlags & TELEPORT_FLAG_UNMOUNT) == 0L) {
            for (Entity entity : this.getIndirectPassengers()) {
                if (!entity.canTeleportAsync()) {
                    return false;
                }
                entity.getBukkitEntity(); // force bukkit entity to be created before TPing
            }
        } else {
            this.unRide();
        }

        if ((teleportFlags & TELEPORT_FLAG_TELEPORT_PASSENGERS) != 0L) {
            if (this.isPassenger()) {
                return false;
            }
        } else {
            if (this.isVehicle() || this.isPassenger()) {
                return false;
            }
        }

        // TODO any events that can modify go HERE

        // check for same region
        if (destination == this.level()
                && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(destination, ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(pos), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(pos), 8)
                && (((teleportFlags & TELEPORT_FLAG_LOAD_CHUNK) == 0L) || destination.areChunksLoadedForMove(this.getBoundingBoxAt(pos.x, pos.y, pos.z)))) {
            boolean hasPassengers = !this.passengers.isEmpty();
            EntityTreeNode passengerTree = this.detachPassengers();

            if (hasPassengers) {
                // Note: The client does not accept position updates for controlled entities. So, we must
                // perform a lot of tracker updates here to make it all work out.

                // first, clear the tracker
                passengerTree.clearTracker();
            }

            for (EntityTreeNode entity : passengerTree.getFullTree()) {
                entity.root.teleportSyncSameRegion(pos, yaw, pitch, velocity);
            }

            if (hasPassengers) {
                passengerTree.restore();
                // re-add to the tracker once the tree is restored
                passengerTree.addTracker();

                // adjust entities to final position
                passengerTree.adjustRiders(true);

                // the tracker clear/add logic is only used in the same region, as the other logic
                // performs add/remove from world logic which will also perform add/remove tracker logic
            }

            if (teleportComplete != null) {
                teleportComplete.accept(this);
            }
            return true;
        }

        EntityTreeNode passengerTree = this.detachPassengers();
        List<EntityTreeNode> fullPassengerTree = passengerTree.getFullTree();
        ServerLevel originWorld = (ServerLevel)this.level;

        for (EntityTreeNode node : fullPassengerTree) {
            node.root.preChangeDimension();
        }

        for (EntityTreeNode node : fullPassengerTree) {
            node.root = node.root.transformForAsyncTeleport(destination, pos, yaw, pitch, velocity);
        }

        passengerTree.root.placeInAsync(originWorld, destination, teleportFlags, passengerTree, teleportComplete);

        return true;
    }

    public void preChangeDimension() {
        if (this instanceof Leashable leashable) {
            leashable.dropLeash();
        }
    }

    public void postChangeDimension() {
        this.resetStoredPositions();
    }

    protected static enum PortalType {
        NETHER, END;
    }

    public boolean endPortalLogicAsync(BlockPos portalPos) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot portal entity async");

        ServerLevel destination = this.level().getServer().getLevel(this.level().getTypeKey() == net.minecraft.world.level.dimension.LevelStem.END ? Level.OVERWORLD : Level.END);
        if (destination == null) {
            // wat
            return false;
        }

        return this.portalToAsync(destination, portalPos, true, PortalType.END, null);
    }

    public boolean netherPortalLogicAsync(BlockPos portalPos) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot portal entity async");

        ServerLevel destination = this.level().getServer().getLevel(this.level().getTypeKey() == net.minecraft.world.level.dimension.LevelStem.NETHER ? Level.OVERWORLD : Level.NETHER);
        if (destination == null) {
            // wat
            return false;
        }

        return this.portalToAsync(destination, portalPos, true, PortalType.NETHER, null);
    }

    private static final java.util.concurrent.atomic.AtomicLong CREATE_PORTAL_DOUBLE_CHECK = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong TELEPORT_HOLD_TICKET_GEN = new java.util.concurrent.atomic.AtomicLong();

    // To simplify portal logic, in region threading both players
    // and non-player entities will create portals. By guaranteeing
    // that the teleportation can take place, we can simply
    // remove the entity, find/create the portal, and place async.
    // If we have to worry about whether the entity may not teleport,
    // we need to first search, then report back, ...
    protected void findOrCreatePortalAsync(ServerLevel origin, BlockPos originPortal, ServerLevel destination, PortalType type,
                                           ca.spottedleaf.concurrentutil.completable.CallbackCompletable<TeleportTransition> portalInfoCompletable) {
        switch (type) {
            // end portal logic is quite simple, the spawn in the end is fixed and when returning to the overworld
            // we just select the spawn position
            case END: {
                if (destination.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.END) {
                    BlockPos targetPos = ServerLevel.END_SPAWN_POINT;
                    // need to load chunks so we can create the platform
                    destination.moonrise$loadChunksAsync(
                        targetPos, 16, // load 16 blocks to be safe from block physics
                        ca.spottedleaf.concurrentutil.util.Priority.HIGH,
                        (chunks) -> {
                            net.minecraft.world.level.levelgen.feature.EndPlatformFeature.createEndPlatform(destination, targetPos.below(), true, null);

                            // the portal obsidian is placed at targetPos.y - 2, so if we want to place the entity
                            // on the obsidian, we need to spawn at targetPos.y - 1
                            portalInfoCompletable.complete(
                                new net.minecraft.world.level.portal.TeleportTransition(
                                    destination, Vec3.atBottomCenterOf(targetPos.below()), Vec3.ZERO, Direction.WEST.toYRot(), 0.0f,
                                    Relative.union(Relative.DELTA, Set.of(Relative.X_ROT)),
                                    TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET),
                                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL
                                )
                            );
                        }
                    );
                } else {
                    BlockPos spawnPos = destination.getRespawnData().pos();
                    // need to load chunk for heightmap
                    destination.moonrise$loadChunksAsync(
                        spawnPos, 0,
                        ca.spottedleaf.concurrentutil.util.Priority.HIGH,
                        (chunks) -> {
                            BlockPos adjustedSpawn = destination.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnPos);

                            // done
                            portalInfoCompletable.complete(
                                new net.minecraft.world.level.portal.TeleportTransition(
                                    destination, Vec3.atBottomCenterOf(adjustedSpawn), Vec3.ZERO, 0.0f, 0.0f,
                                    Relative.union(Relative.DELTA, Relative.ROTATION),
                                    TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET),
                                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL
                                )
                            );
                        }
                    );
                }

                break;
            }
            // for the nether logic, we need to first load the chunks in radius to empty (so that POI is created)
            // then we can search for an existing portal using the POI routines
            // if we don't find a portal, then we bring the chunks in the create radius to full and
            // create it
            case NETHER: {
                // hoisted from the create fallback, so that we can avoid the sync load later if we need it
                BlockState originalPortalBlock = origin.getBlockStateIfLoaded(originPortal);
                Direction.Axis originalPortalDirection = originalPortalBlock == null ? Direction.Axis.X :
                    originalPortalBlock.getOptionalValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS).orElse(Direction.Axis.X);
                BlockUtil.FoundRectangle originalPortalRectangle =
                    originalPortalBlock == null || !originalPortalBlock.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_AXIS)
                        ? null
                        : BlockUtil.getLargestRectangleAround(
                            originPortal, originalPortalDirection, 21, Direction.Axis.Y, 21,
                            (blockpos) -> {
                                return origin.getBlockStateFromEmptyChunkIfLoaded(blockpos) == originalPortalBlock;
                            }
                        );

                boolean destinationIsNether = destination.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.NETHER;

                int portalSearchRadius = origin.paperConfig().environment.portalSearchVanillaDimensionScaling && destinationIsNether ?
                    (int)(destination.paperConfig().environment.portalSearchRadius / destination.dimensionType().coordinateScale()) :
                    destination.paperConfig().environment.portalSearchRadius;
                int portalCreateRadius = destination.paperConfig().environment.portalCreateRadius;

                WorldBorder destinationBorder = destination.getWorldBorder();
                double dimensionScale = net.minecraft.world.level.dimension.DimensionType.getTeleportationScale(origin.dimensionType(), destination.dimensionType());
                BlockPos targetPos = destination.getWorldBorder().clampToBounds(this.getX() * dimensionScale, this.getY(), this.getZ() * dimensionScale);

                ca.spottedleaf.concurrentutil.completable.CallbackCompletable<BlockUtil.FoundRectangle> portalFound
                    = new ca.spottedleaf.concurrentutil.completable.CallbackCompletable<>();

                // post portal find/create logic
                portalFound.addWaiter(
                    (BlockUtil.FoundRectangle portal, Throwable thr) -> {
                        // no portal could be created
                        if (portal == null) {
                            portalInfoCompletable.complete(
                                new TeleportTransition(destination, Vec3.atCenterOf(targetPos), Vec3.ZERO,
                                    90.0f, 0.0f,
                                    TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET))
                            );
                            return;
                        }

                        Vec3 relativePos = originalPortalRectangle == null ?
                            new Vec3(0.5, 0.0, 0.0) :
                            Entity.this.getRelativePortalPosition(originalPortalDirection, originalPortalRectangle);

                        portalInfoCompletable.complete(
                            net.minecraft.world.level.block.NetherPortalBlock.createDimensionTransition(
                                destination, portal, originalPortalDirection, relativePos,
                                Entity.this, TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET)
                            )
                        );
                    }
                );

                // kick off search for existing portal or creation
                destination.moonrise$loadChunksAsync(
                    // add 32 so that the final search for a portal frame doesn't load any chunks
                    targetPos, portalSearchRadius + 32,
                    net.minecraft.world.level.chunk.status.ChunkStatus.EMPTY,
                    ca.spottedleaf.concurrentutil.util.Priority.HIGH,
                    (chunks) -> {
                        BlockUtil.FoundRectangle portal =
                            net.minecraft.world.level.block.NetherPortalBlock.findPortalAround(destination, targetPos, destinationBorder, portalSearchRadius);
                        if (portal != null) {
                            portalFound.complete(portal);
                            return;
                        }

                        // add tickets so that we can re-search for a portal once the chunks are loaded
                        Long ticketId = Long.valueOf(CREATE_PORTAL_DOUBLE_CHECK.getAndIncrement());
                        for (net.minecraft.world.level.chunk.ChunkAccess chunk : chunks) {
                            destination.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
                                TicketType.NETHER_PORTAL_DOUBLE_CHECK, chunk.getPos(),
                                ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL,
                                ticketId
                            );
                        }

                        // no portal found - create one
                        destination.moonrise$loadChunksAsync(
                            targetPos, portalCreateRadius + 32,
                            ca.spottedleaf.concurrentutil.util.Priority.HIGH,
                            (chunks2) -> {
                                // don't need the tickets anymore
                                // note: we expect removeTicketsAtLevel to add an unknown ticket for us automatically
                                // if the ticket level were to decrease
                                for (net.minecraft.world.level.chunk.ChunkAccess chunk : chunks) {
                                    destination.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                                        TicketType.NETHER_PORTAL_DOUBLE_CHECK, chunk.getPos(),
                                        ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL,
                                        ticketId
                                    );
                                }

                                // when two entities portal at the same time, it is possible that both entities reach this
                                // part of the code - and create a double portal
                                // to fix this, we just issue another search to try and see if another entity created
                                // a portal nearby
                                BlockUtil.FoundRectangle existingTryAgain =
                                    net.minecraft.world.level.block.NetherPortalBlock.findPortalAround(destination, targetPos, destinationBorder, portalSearchRadius);
                                if (existingTryAgain != null) {
                                    portalFound.complete(existingTryAgain);
                                    return;
                                }

                                // we do not have the correct entity reference here
                                BlockUtil.FoundRectangle createdPortal =
                                    destination.getPortalForcer().createPortal(targetPos, originalPortalDirection, null, portalCreateRadius).orElse(null);
                                // if it wasn't created, passing null is expected here
                                portalFound.complete(createdPortal);
                            }
                        );
                    }
                );
                break;
            }
            default: {
                throw new IllegalStateException("Unknown portal type " + type);
            }
        }
    }

    public boolean canPortalAsync(ServerLevel to, boolean considerPassengers) {
        return this.canPortalAsync(to, considerPassengers, false);
    }

    protected boolean canPortalAsync(ServerLevel to, boolean considerPassengers, boolean skipPassengerCheck) {
        if (considerPassengers) {
            if (!skipPassengerCheck && this.isPassenger()) {
                return false;
            }
        } else {
            if (this.isVehicle() || (!skipPassengerCheck && this.isPassenger())) {
                return false;
            }
        }
        this.getBukkitEntity(); // force bukkit entity to be created before TPing
        if (!this.canTeleportAsync()) {
            return false;
        }
        if (considerPassengers) {
            for (Entity entity : this.passengers) {
                if (!entity.canPortalAsync(to, true, true)) {
                    return false;
                }
            }
        }

        return true;
    }

    protected void prePortalLogic(ServerLevel origin, ServerLevel destination, PortalType type) {

    }

    protected boolean portalToAsync(ServerLevel destination, BlockPos portalPos, boolean takePassengers,
                                    PortalType type, java.util.function.Consumer<Entity> teleportComplete) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot portal entity async");
        if (!this.canPortalAsync(destination, takePassengers)) {
            return false;
        }

        Vec3 initialPosition = this.position();
        ChunkPos initialPositionChunk = new ChunkPos(
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(initialPosition),
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(initialPosition)
        );

        // first, remove entity/passengers from world
        EntityTreeNode passengerTree = this.detachPassengers();
        List<EntityTreeNode> fullPassengerTree = passengerTree.getFullTree();
        ServerLevel originWorld = (ServerLevel)this.level;

        for (EntityTreeNode node : fullPassengerTree) {
            node.root.preChangeDimension();
            node.root.prePortalLogic(originWorld, destination, type);
        }

        for (EntityTreeNode node : fullPassengerTree) {
            // we will update pos/rot/speed later
            node.root = node.root.transformForAsyncTeleport(destination, null, null, null, null);
            // set portal cooldown
            node.root.setPortalCooldown();
        }

        // ensure the region is always ticking in case of a shutdown
        // otherwise, the shutdown will not be able to complete the shutdown as it requires a ticking region
        Long teleportHoldId = Long.valueOf(TELEPORT_HOLD_TICKET_GEN.getAndIncrement());
        originWorld.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
            TicketType.TELEPORT_HOLD_TICKET, initialPositionChunk,
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL,
            teleportHoldId
        );

        ServerLevel.PendingTeleport beforeFindDestination = new ServerLevel.PendingTeleport(passengerTree, initialPosition);
        originWorld.pushPendingTeleport(beforeFindDestination);

        ca.spottedleaf.concurrentutil.completable.CallbackCompletable<TeleportTransition> portalInfoCompletable
            = new ca.spottedleaf.concurrentutil.completable.CallbackCompletable<>();

        portalInfoCompletable.addWaiter((TeleportTransition info, Throwable throwable) -> {
            if (!originWorld.removePendingTeleport(beforeFindDestination)) {
                // the shutdown thread has placed us back into the origin world at the original position
                // we just have to abandon this teleport to prevent duplication
                return;
            }
            originWorld.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                TicketType.TELEPORT_HOLD_TICKET, initialPositionChunk,
                ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL,
                teleportHoldId
            );
            // adjust passenger tree to final pos/rot/speed
            for (EntityTreeNode node : fullPassengerTree) {
                node.root.transform(info);
            }

            // place
            passengerTree.root.placeInAsync(
                originWorld, destination, Entity.TELEPORT_FLAG_LOAD_CHUNK | (takePassengers ? Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS : 0L),
                passengerTree,
                (Entity teleported) -> {
                    if (info.postTeleportTransition() != null) {
                        info.postTeleportTransition().onTransition(teleported);
                    }

                    if (teleportComplete != null) {
                        teleportComplete.accept(teleported);
                    }
                }
            );
        });


        passengerTree.root.findOrCreatePortalAsync(originWorld, portalPos, destination, type, portalInfoCompletable);

        return true;
    }
    // Folia end - region threading
    // Folia start - fix passenger desync
    public void fixPassengerDesync() {
        if (this.isPassenger() || !this.isVehicle()) {
            return;
        }

        List<Entity> indirectPassengers = (List<Entity>)this.getIndirectPassengers();
        List<ChunkPos> positions = new java.util.ArrayList<>(indirectPassengers.size() + 1);
        positions.add(this.chunkPosition);
        for (Entity passenger : this.getIndirectPassengers()) {
            positions.add(passenger.chunkPosition);
        }
        int maxDistance = Integer.MIN_VALUE;
        for (int i = 0; i < positions.size(); ++i) {
            for (int j = i + 1; j < positions.size(); ++j) {
                ChunkPos from = positions.get(i);
                ChunkPos to = positions.get(j);

                int diffX = to.x() - from.x();
                int diffZ = to.z() - from.z();

                int dist = Math.max(Math.abs(diffX), Math.abs(diffZ));
                maxDistance = Math.max(maxDistance, dist);
            }
        }

        if (maxDistance <= 1) {
            return;
        }

        LOGGER.warn("Vehicle " + this + " desynced from passengers by " + maxDistance + " chunks, teleporting to root vehicle");
        for (Entity passenger : indirectPassengers) {
            LOGGER.warn("Desynced passenger " + passenger + " has vehicle " + passenger.getVehicle());
        }

        // force connect the chunks between this entity and its passengers
        ChunkPos srcChunk = this.chunkPosition;
        for (Entity passenger : indirectPassengers) {
            ChunkPos dstChunk = passenger.chunkPosition;

            int srcX = Math.min(srcChunk.x(), dstChunk.x());
            int dstX = Math.max(srcChunk.x(), dstChunk.x());

            int srcZ = Math.min(srcChunk.z(), dstChunk.z());
            int dstZ = Math.max(srcChunk.z(), dstChunk.z());

            if (srcX == dstX && srcZ == dstZ) {
                continue;
            }

            // along x
            for (int currX = srcX; currX <= dstX; ++currX) {
                ChunkPos pos = new ChunkPos(currX, srcZ);
                ((ServerLevel)this.level()).getChunkSource().addTicketAtLevel(
                    TicketType.UNKNOWN, pos, ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL
                );
            }

            // along z
            for (int currZ = srcZ; currZ <= dstZ; ++currZ) {
                ChunkPos pos = new ChunkPos(dstX, currZ);
                ((ServerLevel)this.level()).getChunkSource().addTicketAtLevel(
                    TicketType.UNKNOWN, pos, ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL
                );
            }
        }

        this.teleportAsync(
            (ServerLevel)this.level(), this.position, null, null, null,
            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN, Entity.TELEPORT_FLAG_LOAD_CHUNK | Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS,
            null
        );
    }
    // Folia end - fix passenger desync

    public @Nullable Entity teleport(TeleportTransition transition) { // Paper - remove param final
        // Folia start - region threading
        if (true) {
            throw new UnsupportedOperationException("Must use teleportAsync while in region threading");
        }
        // Folia end - region threading
        // Paper start - Fix item duplication and teleport issues
        if ((!this.isAlive() || !this.valid) && (transition.newLevel() != this.level)) {
            LOGGER.warn("Illegal Entity Teleport {} to {}:{}", this, transition.newLevel(), transition.position(), new Throwable());
            return null;
        }
        // Paper end - Fix item duplication and teleport issues
        if (this.level() instanceof ServerLevel serverLevel && !this.isRemoved()) {
            // CraftBukkit start
            PositionMoveRotation absolutePosition = PositionMoveRotation.calculateAbsolute(PositionMoveRotation.of(this), PositionMoveRotation.of(transition), transition.relatives());
            Vec3 velocity = absolutePosition.deltaMovement(); // Paper
            org.bukkit.Location to = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(absolutePosition.position(), transition.newLevel(), absolutePosition.yRot(), absolutePosition.xRot());
            // Paper start - gateway-specific teleport event
            final org.bukkit.event.entity.EntityTeleportEvent teleEvent;
            if (this.portalProcess != null && this.portalProcess.isSamePortal(((net.minecraft.world.level.block.EndGatewayBlock) Blocks.END_GATEWAY)) && this.level.getBlockEntity(this.portalProcess.getEntryPosition()) instanceof net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity theEndGatewayBlockEntity) {
                teleEvent = new com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent(this.getBukkitEntity(), this.getBukkitEntity().getLocation(), to, new org.bukkit.craftbukkit.block.CraftEndGateway(to.getWorld(), theEndGatewayBlockEntity));
                teleEvent.callEvent();
            } else {
                teleEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callEntityTeleportEvent(this, to);
            }
            // Paper end - gateway-specific teleport event
            if (teleEvent.isCancelled() || teleEvent.getTo() == null) {
                return null;
            }
            if (!to.equals(teleEvent.getTo())) {
                to = teleEvent.getTo();
                transition = new TeleportTransition(((org.bukkit.craftbukkit.CraftWorld) to.getWorld()).getHandle(), org.bukkit.craftbukkit.util.CraftLocation.toVec3(to), Vec3.ZERO, to.getYaw(), to.getPitch(), transition.missingRespawnBlock(), transition.asPassenger(), Set.of(), transition.postTeleportTransition(), transition.cause());
                // Paper start - Call EntityPortalExitEvent
                velocity = Vec3.ZERO;
            }
            if (this.portalProcess != null) { // if in a portal
                org.bukkit.entity.Entity bukkitEntity = this.getBukkitEntity();
                org.bukkit.util.Vector before = bukkitEntity.getVelocity();
                org.bukkit.util.Vector after = org.bukkit.craftbukkit.util.CraftVector.toBukkit(velocity);
                org.bukkit.event.entity.EntityPortalExitEvent event = new org.bukkit.event.entity.EntityPortalExitEvent(
                    bukkitEntity,
                    bukkitEntity.getLocation(), to.clone(),
                    before, after
                );
                event.callEvent();

                // Only change the target if actually needed, since we reset relative flags
                if (event.isCancelled() || !to.equals(event.getTo()) || !after.equals(event.getAfter())) {
                    if (event.isCancelled() || event.getTo() == null) {
                        org.bukkit.World toWorld = to.getWorld();
                        to = event.getFrom().clone();
                        to.setWorld(toWorld); // cancelling doesn't cancel the teleport just the position/velocity (old quirk)
                        velocity = org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getBefore());
                    } else {
                        to = event.getTo().clone();
                        velocity = org.bukkit.craftbukkit.util.CraftVector.toVec3(event.getAfter());
                    }
                    transition = new TeleportTransition(((org.bukkit.craftbukkit.CraftWorld) to.getWorld()).getHandle(), org.bukkit.craftbukkit.util.CraftLocation.toVec3(to), velocity, to.getYaw(), to.getPitch(), transition.missingRespawnBlock(), transition.asPassenger(), Set.of(), transition.postTeleportTransition(), transition.cause());
                }
            }
            if (this.isRemoved()) {
                return null;
            }
            // Paper end - Call EntityPortalExitEvent
            // CraftBukkit end
            ServerLevel newLevel = transition.newLevel();
            boolean otherDimension = newLevel.dimension() != serverLevel.dimension();
            if (!transition.asPassenger()) {
                this.stopRiding();
            }

            return otherDimension ? this.teleportCrossDimension(serverLevel, newLevel, transition) : this.teleportSameDimension(serverLevel, transition);
        } else {
            return null;
        }
    }

    private Entity teleportSameDimension(final ServerLevel level, final TeleportTransition transition) {
        for (Entity passenger : this.getPassengers()) {
            passenger.teleport(this.calculatePassengerTransition(transition, passenger));
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("teleportSameDimension");
        this.teleportSetPosition(PositionMoveRotation.of(transition), transition.relatives());
        if (!transition.asPassenger()) {
            this.sendTeleportTransitionToRidingPlayers(transition);
        }

        transition.postTeleportTransition().onTransition(this);
        profiler.pop();
        return this;
    }

    private @Nullable Entity teleportCrossDimension(final ServerLevel oldLevel, final ServerLevel newLevel, final TeleportTransition transition) {
        List<Entity> oldPassengers = this.getPassengers();
        List<Entity> newPassengers = new ArrayList<>(oldPassengers.size());
        this.ejectPassengers();

        for (Entity passenger : oldPassengers) {
            Entity newPassenger = passenger.teleport(this.calculatePassengerTransition(transition, passenger));
            if (newPassenger != null) {
                newPassengers.add(newPassenger);
            }
        }

        ProfilerFiller profiler = Profiler.get();
        profiler.push("teleportCrossDimension");
        Entity newEntity = this.getType().create(newLevel, EntitySpawnReason.DIMENSION_TRAVEL);
        if (newEntity == null) {
            profiler.pop();
            return null;
        }

        // Paper start - Fix item duplication and teleport issues
        if (this instanceof Leashable leashable) {
            leashable.dropLeash(); // Paper drop lead
        }
        // Paper end - Fix item duplication and teleport issues
        newEntity.restoreFrom(this);
        this.removeAfterChangingDimensions();
        newEntity.teleportSetPosition(PositionMoveRotation.of(this), PositionMoveRotation.of(transition), transition.relatives());
        if (this.inWorld) newLevel.addDuringTeleport(newEntity); // CraftBukkit - Don't spawn the new entity if the current entity isn't spawned

        for (Entity newPassenger : newPassengers) {
            newPassenger.startRiding(newEntity, true, false);
        }

        newLevel.resetEmptyTime();
        transition.postTeleportTransition().onTransition(newEntity);
        this.teleportSpectators(transition, oldLevel);
        profiler.pop();
        return newEntity;
    }

    protected void teleportSpectators(final TeleportTransition transition, final ServerLevel oldLevel) {
        for (ServerPlayer serverPlayer : List.copyOf(oldLevel.players())) {
            if (serverPlayer.getCamera() == this) {
                serverPlayer.teleport(transition);
                serverPlayer.setCamera(null);
            }
        }
    }

    private TeleportTransition calculatePassengerTransition(final TeleportTransition transition, final Entity passenger) {
        float passengerYRot = transition.yRot() + (transition.relatives().contains(Relative.Y_ROT) ? 0.0F : passenger.getYRot() - this.getYRot());
        float passengerXRot = transition.xRot() + (transition.relatives().contains(Relative.X_ROT) ? 0.0F : passenger.getXRot() - this.getXRot());
        Vec3 passengerOffset = passenger.position().subtract(this.position());
        Vec3 passengerPos = transition.position()
            .add(
                transition.relatives().contains(Relative.X) ? 0.0 : passengerOffset.x(),
                transition.relatives().contains(Relative.Y) ? 0.0 : passengerOffset.y(),
                transition.relatives().contains(Relative.Z) ? 0.0 : passengerOffset.z()
            );
        return transition.withPosition(passengerPos).withRotation(passengerYRot, passengerXRot).transitionAsPassenger();
    }

    private void sendTeleportTransitionToRidingPlayers(final TeleportTransition transition) {
        Entity controller = this.getControllingPassenger();

        for (Entity passenger : this.getIndirectPassengers()) {
            if (passenger instanceof ServerPlayer player) {
                if (controller != null && player.getId() == controller.getId()) {
                    player.connection
                        .send(
                            ClientboundTeleportEntityPacket.teleport(this.getId(), PositionMoveRotation.of(transition), transition.relatives(), this.onGround)
                        );
                } else {
                    player.connection.send(ClientboundTeleportEntityPacket.teleport(this.getId(), PositionMoveRotation.of(this), Set.of(), this.onGround));
                }
            }
        }
    }

    public void teleportSetPosition(final PositionMoveRotation destination, final Set<Relative> relatives) {
        this.teleportSetPosition(PositionMoveRotation.of(this), destination, relatives);
    }

    public void teleportSetPosition(final PositionMoveRotation currentValues, final PositionMoveRotation destination, final Set<Relative> relatives) {
        PositionMoveRotation absoluteDestination = PositionMoveRotation.calculateAbsolute(currentValues, destination, relatives);
        this.setPosRaw(absoluteDestination.position().x, absoluteDestination.position().y, absoluteDestination.position().z);
        this.setYRot(absoluteDestination.yRot());
        this.setYHeadRot(absoluteDestination.yRot());
        this.setXRot(absoluteDestination.xRot());
        this.reapplyPosition();
        this.setOldPosAndRot();
        this.setDeltaMovement(absoluteDestination.deltaMovement());
        this.clearMovementThisTick();
    }

    public void forceSetRotation(final float yRot, final boolean relativeY, final float xRot, final boolean relativeX) {
        Set<Relative> relatives = Relative.rotation(relativeY, relativeX);
        PositionMoveRotation currentValues = PositionMoveRotation.of(this);
        PositionMoveRotation destination = currentValues.withRotation(yRot, xRot);
        PositionMoveRotation absoluteDestination = PositionMoveRotation.calculateAbsolute(currentValues, destination, relatives);
        this.setYRot(absoluteDestination.yRot());
        this.setYHeadRot(absoluteDestination.yRot());
        this.setXRot(absoluteDestination.xRot());
        this.setOldRot();
    }

    public void placePortalTicket(final BlockPos ticketPosition) {
        if (this.level() instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(ticketPosition), 3);
        }
    }

    // Folia start - region threading - move inventory clearing until after the dimension change
    protected void postRemoveAfterChangingDimensions() {

    }
    // Folia end - region threading - move inventory clearing until after the dimension change

    protected void removeAfterChangingDimensions() {
        this.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION, null); // CraftBukkit - add Bukkit remove cause
        if (this instanceof Leashable leashable && leashable.isLeashed()) { // Paper - only call if it is leashed
            // Paper start - Expand EntityUnleashEvent
            final org.bukkit.event.entity.EntityUnleashEvent event = new org.bukkit.event.entity.EntityUnleashEvent(this.getBukkitEntity(), org.bukkit.event.entity.EntityUnleashEvent.UnleashReason.UNKNOWN, false); // CraftBukkit
            event.callEvent();
            if (!event.isDropLeash()) {
                leashable.removeLeash();
            } else {
                leashable.dropLeash();
            }
            // Paper end - Expand EntityUnleashEvent
        }

        if (this instanceof WaypointTransmitter waypoint && this.level instanceof ServerLevel serverLevel) {
            serverLevel.getWaypointManager().untrackWaypoint(waypoint);
        }
    }

    public Vec3 getRelativePortalPosition(final Direction.Axis axis, final BlockUtil.FoundRectangle portalArea) {
        return PortalShape.getRelativePosition(portalArea, axis, this.position(), this.getDimensions(this.getPose()));
    }

    public boolean canUsePortal(final boolean ignorePassenger) {
        return (ignorePassenger || !this.isPassenger()) && this.isAlive();
    }

    public boolean canTeleport(final Level from, final Level to) {
        if (!this.isAlive() || !this.valid) return false; // Paper - Fix item duplication and teleport issues
        if (from.dimension() == Level.END && to.dimension() == Level.OVERWORLD) {
            for (Entity passenger : this.getPassengers()) {
                if (passenger instanceof ServerPlayer player && !player.seenCredits) {
                    return false;
                }
            }
        }

        return true;
    }

    public float getBlockExplosionResistance(
        final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState block, final FluidState fluid, final float resistance
    ) {
        return resistance;
    }

    public boolean shouldBlockExplode(final Explosion explosion, final BlockGetter level, final BlockPos pos, final BlockState state, final float power) {
        return true;
    }

    public int getMaxFallDistance() {
        return 3;
    }

    public boolean isIgnoringBlockTriggers() {
        return false;
    }

    public void fillCrashReportCategory(final CrashReportCategory category) {
        category.setDetail("Entity Type", () -> this.typeHolder().getRegisteredName() + " (" + this.getClass().getCanonicalName() + ")");
        category.setDetail("Entity ID", this.id);
        category.setDetail("Entity Name", () -> this.getPlainTextName());
        category.setDetail("Entity's Exact location", String.format(Locale.ROOT, "%.2f, %.2f, %.2f", this.getX(), this.getY(), this.getZ()));
        category.setDetail(
            "Entity's Block location", CrashReportCategory.formatLocation(this.level(), Mth.floor(this.getX()), Mth.floor(this.getY()), Mth.floor(this.getZ()))
        );
        Vec3 movement = this.getDeltaMovement();
        category.setDetail("Entity's Momentum", String.format(Locale.ROOT, "%.2f, %.2f, %.2f", movement.x, movement.y, movement.z));
        category.setDetail("Entity's Passengers", () -> this.getPassengers().toString());
        category.setDetail("Entity's Vehicle", () -> String.valueOf(this.getVehicle()));
    }

    public boolean displayFireAnimation() {
        return this.isOnFire() && !this.isSpectator();
    }

    public void setUUID(final UUID uuid) {
        this.uuid = uuid;
        this.stringUUID = this.uuid.toString();
    }

    @Override
    public UUID getUUID() {
        return this.uuid;
    }

    public String getStringUUID() {
        return this.stringUUID;
    }

    @Override
    public String getScoreboardName() {
        return this.stringUUID;
    }

    public boolean isPushedByFluid() {
        return true;
    }

    public static double getViewScale() {
        return viewScale;
    }

    public static void setViewScale(final double viewScale) {
        Entity.viewScale = viewScale;
    }

    @Override
    public Component getDisplayName() {
        return PlayerTeam.formatNameForTeam(this.getTeam(), this.getName())
            .withStyle(s -> s.withHoverEvent(this.createHoverEvent()).withInsertion(this.getStringUUID()));
    }

    public void setCustomName(final @Nullable Component name) {
        this.entityData.set(DATA_CUSTOM_NAME, Optional.ofNullable(name));
    }

    @Override
    public @Nullable Component getCustomName() {
        return this.entityData.get(DATA_CUSTOM_NAME).orElse(null);
    }

    @Override
    public boolean hasCustomName() {
        return this.entityData.get(DATA_CUSTOM_NAME).isPresent();
    }

    public void setCustomNameVisible(final boolean visible) {
        this.entityData.set(DATA_CUSTOM_NAME_VISIBLE, visible);
    }

    public boolean isCustomNameVisible() {
        return this.entityData.get(DATA_CUSTOM_NAME_VISIBLE);
    }

    public @Nullable Component belowNameDisplay() {
        Scoreboard scoreboard = this.level().getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BELOW_NAME);
        if (objective != null) {
            ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(this, objective);
            if (score != null) {
                Component formattedValue = score.formatValue(objective.numberFormatOrDefault(StyledFormat.NO_STYLE));
                return Component.empty().append(formattedValue).append(CommonComponents.SPACE).append(objective.getDisplayName());
            }
        }

        return null;
    }

    public final boolean teleportTo( // CraftBukkit - final
        final ServerLevel level,
        final double x,
        final double y,
        final double z,
        final Set<Relative> relatives,
        final float newYRot,
        final float newXRot,
        final boolean resetCamera
    ) {
        // CraftBukkit start
        return this.teleportTo(level, x, y, z, relatives, newYRot, newXRot, resetCamera, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    public boolean teleportTo(
        final ServerLevel level,
        final double x,
        final double y,
        final double z,
        final Set<Relative> relatives,
        final float newYRot,
        final float newXRot,
        final boolean resetCamera,
        final org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause
    ) {
        // CraftBukkit end
        Entity newEntity = this.teleport(
            new TeleportTransition(level, new Vec3(x, y, z), Vec3.ZERO, newYRot, newXRot, relatives, TeleportTransition.DO_NOTHING, cause) // CraftBukkit
        );
        return newEntity != null;
    }

    public void dismountTo(final double x, final double y, final double z) {
        this.teleportTo(x, y, z); // Paper - diff on change for override
    }

    public void teleportTo(final double x, final double y, final double z) {
        if (this.level() instanceof ServerLevel) {
            this.snapTo(x, y, z, this.getYRot(), this.getXRot());
            this.teleportPassengers();
        }
    }

    private void teleportPassengers() {
        this.getSelfAndPassengers().forEach(entity -> {
            for (Entity passenger : entity.passengers) {
                entity.positionRider(passenger, Entity::snapTo);
            }
        });
    }

    public void teleportRelative(final double dx, final double dy, final double dz) {
        this.teleportTo(this.getX() + dx, this.getY() + dy, this.getZ() + dz);
    }

    public boolean shouldShowName() {
        return this.isCustomNameVisible();
    }

    @Override
    public void onSyncedDataUpdated(final List<SynchedEntityData.DataValue<?>> updatedItems) {
    }

    @Override
    public void onSyncedDataUpdated(final EntityDataAccessor<?> accessor) {
        if (DATA_POSE.equals(accessor)) {
            this.refreshDimensions();
        }
    }

    @Deprecated
    protected void fixupDimensions() {
        Pose pose = this.getPose();
        EntityDimensions newDim = this.getDimensions(pose);
        this.dimensions = newDim;
        this.eyeHeight = newDim.eyeHeight();
    }

    public void refreshDimensions() {
        EntityDimensions oldDim = this.dimensions;
        Pose pose = this.getPose();
        EntityDimensions newDim = this.getDimensions(pose);
        this.dimensions = newDim;
        this.eyeHeight = newDim.eyeHeight();
        this.reapplyPosition();
        boolean isSmall = newDim.width() <= 4.0F && newDim.height() <= 4.0F;
        if (!this.level.isClientSide()
            && !this.firstTick
            && !this.noPhysics
            && isSmall
            && (newDim.width() > oldDim.width() || newDim.height() > oldDim.height())
            && !(this instanceof Player)) {
            this.fudgePositionAfterSizeChange(oldDim);
        }
    }

    public boolean fudgePositionAfterSizeChange(final EntityDimensions previousDimensions) {
        EntityDimensions newDimensions = this.getDimensions(this.getPose());
        Vec3 oldCenter = this.position().add(0.0, previousDimensions.height() / 2.0, 0.0);
        double widthDelta = Math.max(0.0F, newDimensions.width() - previousDimensions.width()) + 1.0E-6;
        double heightDelta = Math.max(0.0F, newDimensions.height() - previousDimensions.height()) + 1.0E-6;
        VoxelShape allowedCenters = Shapes.create(AABB.ofSize(oldCenter, widthDelta, heightDelta, widthDelta));
        Optional<Vec3> freePosition = this.level
            .findFreePosition(this, allowedCenters, oldCenter, newDimensions.width(), newDimensions.height(), newDimensions.width());
        if (freePosition.isPresent()) {
            this.setPos(freePosition.get().add(0.0, -newDimensions.height() / 2.0, 0.0));
            return true;
        }

        if (newDimensions.width() > previousDimensions.width() && newDimensions.height() > previousDimensions.height()) {
            VoxelShape allowedCentersIgnoringY = Shapes.create(AABB.ofSize(oldCenter, widthDelta, 1.0E-6, widthDelta));
            Optional<Vec3> freePositionIgnoreVertical = this.level
                .findFreePosition(this, allowedCentersIgnoringY, oldCenter, newDimensions.width(), previousDimensions.height(), newDimensions.width());
            if (freePositionIgnoreVertical.isPresent()) {
                this.setPos(freePositionIgnoreVertical.get().add(0.0, -previousDimensions.height() / 2.0 + 1.0E-6, 0.0));
                return true;
            }
        }

        return false;
    }

    public Direction getDirection() {
        return Direction.fromYRot(this.getYRot());
    }

    public Direction getMotionDirection() {
        return this.getDirection();
    }

    protected HoverEvent createHoverEvent() {
        return new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(this.getType(), this.getUUID(), this.getName()));
    }

    public boolean broadcastToPlayer(final ServerPlayer player) {
        return true;
    }

    @Override
    public final AABB getBoundingBox() {
        return this.bb;
    }

    public final void setBoundingBox(final AABB bb) {
        // CraftBukkit start - block invalid bounding boxes
        double minX = bb.minX,
                minY = bb.minY,
                minZ = bb.minZ,
                maxX = bb.maxX,
                maxY = bb.maxY,
                maxZ = bb.maxZ;
        double len = bb.maxX - bb.minX;
        if (len < 0) maxX = minX;
        if (len > 64) maxX = minX + 64.0;

        len = bb.maxY - bb.minY;
        if (len < 0) maxY = minY;
        if (len > 64) maxY = minY + 64.0;

        len = bb.maxZ - bb.minZ;
        if (len < 0) maxZ = minZ;
        if (len > 64) maxZ = minZ + 64.0;
        this.bb = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        // CraftBukkit end
    }

    public final float getEyeHeight(final Pose pose) {
        return this.getDimensions(pose).eyeHeight();
    }

    public final float getEyeHeight() {
        return this.eyeHeight;
    }

    @Override
    public @Nullable SlotAccess getSlot(final int slot) {
        return null;
    }

    public boolean ignoreExplosion(final Explosion explosion) {
        return false;
    }

    public void startSeenByPlayer(final ServerPlayer player) {
        // Folia start - region threading
        if (player.getCamera() == this) {
            // set camera again
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCameraPacket(this));
        }
        // Folia end - region threading
    }

    public void stopSeenByPlayer(final ServerPlayer player) {
        // Paper start - entity tracking events
        // Since this event cannot be cancelled, we should call it here to catch all "un-tracks"
        if (io.papermc.paper.event.player.PlayerUntrackEntityEvent.getHandlerList().getRegisteredListeners().length > 0) {
            new io.papermc.paper.event.player.PlayerUntrackEntityEvent(player.getBukkitEntity(), this.getBukkitEntity()).callEvent();
        }
        // Paper end - entity tracking events
        // Folia start - region threading
        if (player.getCamera() == this) {
            // unset camera, the player tick method should TP us close enough again to invoke startSeenByPlayer
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCameraPacket(player));
        }
        // Folia end - region threading
    }

    public float rotate(final Rotation rotation) {
        float angle = Mth.wrapDegrees(this.getYRot());

        return switch (rotation) {
            case CLOCKWISE_180 -> angle + 180.0F;
            case COUNTERCLOCKWISE_90 -> angle + 270.0F;
            case CLOCKWISE_90 -> angle + 90.0F;
            default -> angle;
        };
    }

    public float mirror(final Mirror mirror) {
        float angle = Mth.wrapDegrees(this.getYRot());

        return switch (mirror) {
            case FRONT_BACK -> -angle;
            case LEFT_RIGHT -> 180.0F - angle;
            default -> angle;
        };
    }

    public ProjectileDeflection deflection(final Projectile projectile) {
        return this.is(EntityTypeTags.DEFLECTS_PROJECTILES) ? ProjectileDeflection.REVERSE : ProjectileDeflection.NONE;
    }

    public @Nullable LivingEntity getControllingPassenger() {
        return null;
    }

    public final boolean hasControllingPassenger() {
        return this.getControllingPassenger() != null;
    }

    public final List<Entity> getPassengers() {
        return this.passengers;
    }

    public @Nullable Entity getFirstPassenger() {
        return this.passengers.isEmpty() ? null : this.passengers.get(0);
    }

    public boolean hasPassenger(final Entity entity) {
        return this.passengers.contains(entity);
    }

    public boolean hasPassenger(final Predicate<Entity> test) {
        for (Entity passenger : this.passengers) {
            if (test.test(passenger)) {
                return true;
            }
        }

        return false;
    }

    private Stream<Entity> getIndirectPassengersStream() {
        if (this.passengers.isEmpty()) { return Stream.of(); } // Paper - Optimize indirect passenger iteration
        return this.passengers.stream().flatMap(Entity::getSelfAndPassengers);
    }

    @Override
    public Stream<Entity> getSelfAndPassengers() {
        if (this.passengers.isEmpty()) { return Stream.of(this); } // Paper - Optimize indirect passenger iteration
        return Stream.concat(Stream.of(this), this.getIndirectPassengersStream());
    }

    @Override
    public Stream<Entity> getPassengersAndSelf() {
        if (this.passengers.isEmpty()) { return Stream.of(this); } // Paper - Optimize indirect passenger iteration
        return Stream.concat(this.passengers.stream().flatMap(Entity::getPassengersAndSelf), Stream.of(this));
    }

    public Iterable<Entity> getIndirectPassengers() {
        // Paper start - optimise entity tracker
        final List<Entity> ret = new ArrayList<>();

        if (this.passengers.isEmpty()) {
            return ret;
        }

        collectIndirectPassengers(ret, this.passengers);

        return ret;
        // Paper end - optimise entity tracker
    }

    public int countPlayerPassengers() {
        return (int)this.getIndirectPassengersStream().filter(e -> e instanceof Player).count();
    }

    public boolean hasExactlyOnePlayerPassenger() {
        if (this.passengers.isEmpty()) { return false; } // Paper - Optimize indirect passenger iteration
        return this.countPlayerPassengers() == 1;
    }

    public Entity getRootVehicle() {
        Entity result = this;

        while (result.isPassenger()) {
            result = result.getVehicle();
        }

        return result;
    }

    public boolean isPassengerOfSameVehicle(final Entity other) {
        return this.getRootVehicle() == other.getRootVehicle();
    }

    public boolean hasIndirectPassenger(final Entity entity) {
        if (!entity.isPassenger()) {
            return false;
        }

        Entity ridden = entity.getVehicle();
        return ridden == this || this.hasIndirectPassenger(ridden);
    }

    public final boolean isLocalInstanceAuthoritative() {
        return this.level.isClientSide() ? this.isLocalClientAuthoritative() : !this.isClientAuthoritative();
    }

    protected boolean isLocalClientAuthoritative() {
        LivingEntity passenger = this.getControllingPassenger();
        return passenger != null && passenger.isLocalClientAuthoritative();
    }

    public boolean isClientAuthoritative() {
        LivingEntity passenger = this.getControllingPassenger();
        return passenger != null && passenger.isClientAuthoritative();
    }

    public boolean canSimulateMovement() {
        return this.isLocalInstanceAuthoritative();
    }

    public boolean isEffectiveAi() {
        return this.isLocalInstanceAuthoritative();
    }

    protected static Vec3 getCollisionHorizontalEscapeVector(final double colliderWidth, final double collidingWidth, final float directionDegrees) {
        double distance = (colliderWidth + collidingWidth + 1.0E-5F) / 2.0;
        float directionX = -Mth.sin(directionDegrees * Mth.DEG_TO_RAD);
        float directionZ = Mth.cos(directionDegrees * Mth.DEG_TO_RAD);
        float scale = Math.max(Math.abs(directionX), Math.abs(directionZ));
        return new Vec3(directionX * distance / scale, 0.0, directionZ * distance / scale);
    }

    public Vec3 getDismountLocationForPassenger(final LivingEntity passenger) {
        return new Vec3(this.getX(), this.getBoundingBox().maxY, this.getZ());
    }

    public @Nullable Entity getVehicle() {
        return this.vehicle;
    }

    public @Nullable Entity getControlledVehicle() {
        return this.vehicle != null && this.vehicle.getControllingPassenger() == this ? this.vehicle : null;
    }

    public PushReaction getPistonPushReaction() {
        return PushReaction.NORMAL;
    }

    public SoundSource getSoundSource() {
        return SoundSource.NEUTRAL;
    }

    public int getFireImmuneTicks() {
        return 0;
    }

    // CraftBukkit start
    private final CommandSource commandSource = new CommandSource() {

        @Override
        public void sendSystemMessage(Component message) {
        }

        @Override
        public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
            return Entity.this.getBukkitEntity();
        }

        @Override
        public boolean acceptsSuccess() {
            return ((ServerLevel) Entity.this.level()).getGameRules().get(net.minecraft.world.level.gamerules.GameRules.COMMAND_BLOCK_OUTPUT);
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return true;
        }
    };
    // CraftBukkit end

    public CommandSourceStack createCommandSourceStackForNameResolution(final ServerLevel level) {
        return new CommandSourceStack(
            this.commandSource, // CraftBukkit
            this.position(),
            this.getRotationVector(),
            level,
            PermissionSet.NO_PERMISSIONS,
            this.getPlainTextName(),
            this.getDisplayName(),
            level.getServer(),
            this
        );
    }

    public void lookAt(final EntityAnchorArgument.Anchor anchor, final Vec3 pos) {
        Vec3 from = anchor.apply(this);
        double xd = pos.x - from.x;
        double yd = pos.y - from.y;
        double zd = pos.z - from.z;
        double sd = Math.sqrt(xd * xd + zd * zd);
        this.setXRot(Mth.wrapDegrees((float)(-(Mth.atan2(yd, sd) * 180.0F / (float)Math.PI))));
        this.setYRot(Mth.wrapDegrees((float)(Mth.atan2(zd, xd) * 180.0F / (float)Math.PI) - 90.0F));
        this.setYHeadRot(this.getYRot());
        this.xRotO = this.getXRot();
        this.yRotO = this.getYRot();
    }

    public float getPreciseBodyRotation(final float partial) {
        return Mth.lerp(partial, this.yRotO, this.yRot);
    }

    public boolean touchingUnloadedChunk() {
        AABB box = this.getBoundingBox().inflate(1.0);
        int x0 = Mth.floor(box.minX);
        int x1 = Mth.ceil(box.maxX);
        int z0 = Mth.floor(box.minZ);
        int z1 = Mth.ceil(box.maxZ);
        return !this.level().hasChunksAt(x0, z0, x1, z1);
    }

    public double getFluidHeight(final TagKey<Fluid> type) {
        return this.fluidInteraction.getFluidHeight(type);
    }

    public double getFluidJumpThreshold() {
        return this.getEyeHeight() < 0.4 ? 0.0 : 0.4;
    }

    public final float getBbWidth() {
        return this.dimensions.width();
    }

    public final float getBbHeight() {
        return this.dimensions.height();
    }

    public Packet<ClientGamePacketListener> getAddEntityPacket(final ServerEntity serverEntity) {
        return new ClientboundAddEntityPacket(this, serverEntity);
    }

    public EntityDimensions getDimensions(final Pose pose) {
        return this.type.getDimensions();
    }

    public final EntityAttachments getAttachments() {
        return this.dimensions.attachments();
    }

    @Override
    public Vec3 position() {
        return this.position;
    }

    public Vec3 trackingPosition() {
        return this.position();
    }

    @Override
    public BlockPos blockPosition() {
        return this.blockPosition;
    }

    public BlockState getInBlockState() {
        if (this.inBlockState == null) {
            this.inBlockState = this.level().getBlockState(this.blockPosition());
        }

        return this.inBlockState;
    }

    public ChunkPos chunkPosition() {
        return this.chunkPosition;
    }

    public Vec3 getDeltaMovement() {
        return this.deltaMovement;
    }

    public void setDeltaMovement(final Vec3 deltaMovement) {
        if (deltaMovement.isFinite()) {
            synchronized (this.posLock) { // Paper - detailed watchdog information
            this.deltaMovement = deltaMovement;
            } // Paper - detailed watchdog information
        }
    }

    public void addDeltaMovement(final Vec3 momentum) {
        if (momentum.isFinite()) {
            this.setDeltaMovement(this.getDeltaMovement().add(momentum));
        }
    }

    public void setDeltaMovement(final double xd, final double yd, final double zd) {
        this.setDeltaMovement(new Vec3(xd, yd, zd));
    }

    public final int getBlockX() {
        return this.blockPosition.getX();
    }

    public final double getX() {
        return this.position.x;
    }

    public double getX(final double progress) {
        return this.position.x + this.getBbWidth() * progress;
    }

    public double getRandomX(final double spread) {
        return this.getX((2.0 * this.random.nextDouble() - 1.0) * spread);
    }

    public final int getBlockY() {
        return this.blockPosition.getY();
    }

    public final double getY() {
        return this.position.y;
    }

    public double getY(final double progress) {
        return this.position.y + this.getBbHeight() * progress;
    }

    public double getRandomY(final double spread) {
        return this.getY((2.0 * this.random.nextDouble() - 1.0) * spread);
    }

    public double getRandomY() {
        return this.getY(this.random.nextDouble());
    }

    public double getEyeY() {
        return this.position.y + this.eyeHeight;
    }

    public final int getBlockZ() {
        return this.blockPosition.getZ();
    }

    public final double getZ() {
        return this.position.z;
    }

    public double getZ(final double progress) {
        return this.position.z + this.getBbWidth() * progress;
    }

    public double getRandomZ(final double spread) {
        return this.getZ((2.0 * this.random.nextDouble() - 1.0) * spread);
    }

    public final void setPosRaw(final double x, final double y, final double z) {
        // Paper start - Block invalid positions and bounding box
        this.setPosRaw(x, y, z, false);
    }

    public static boolean checkPosition(Entity entity, double newX, double newY, double newZ) {
        if (Double.isFinite(newX) && Double.isFinite(newY) && Double.isFinite(newZ)) {
            return true;
        }

        String entityInfo;
        try {
            entityInfo = entity.toString();
        } catch (Exception ex) {
            entityInfo = "[Entity info unavailable] ";
        }
        LOGGER.error("New entity position is invalid! Tried to set invalid position ({},{},{}) for entity {} located at {}, entity info: {}", newX, newY, newZ, entity.getClass().getName(), entity.position(), entityInfo, new Throwable());
        return false;
    }

    public final void setPosRaw(double x, double y, double z, boolean forceBoundingBoxUpdate) {
        // Paper start - rewrite chunk system
        if (this.updatingSectionStatus) {
            LOGGER.error(
                "Refusing to update position for entity " + this + " to position " + new Vec3(x, y, z)
                    + " since it is processing a section status update", new Throwable()
            );
            return;
        }
        // Paper end - rewrite chunk system
        if (!checkPosition(this, x, y, z)) {
            return;
        }
        // Paper end - Block invalid positions and bounding box
        boolean posChanged = this.position.x != x || this.position.y != y || this.position.z != z;
        if (posChanged) { // Folia - region threading
            synchronized (this.posLock) { // Paper - detailed watchdog information
            this.position = new Vec3(x, y, z);
            } // Paper - detailed watchdog information
            int fx = Mth.floor(x);
            int fy = Mth.floor(y);
            int fz = Mth.floor(z);
            if (fx != this.blockPosition.getX() || fy != this.blockPosition.getY() || fz != this.blockPosition.getZ()) {
                this.blockPosition = new BlockPos(fx, fy, fz);
                this.inBlockState = null;
                if (SectionPos.blockToSectionCoord(fx) != this.chunkPosition.x() || SectionPos.blockToSectionCoord(fz) != this.chunkPosition.z()) {
                    this.chunkPosition = ChunkPos.containing(this.blockPosition);
                }
            }

            this.levelCallback.onMove();
            if (!this.firstTick && this.level instanceof ServerLevel serverLevel && !this.isRemoved()) {
                if (this instanceof WaypointTransmitter waypoint && waypoint.isTransmittingWaypoint()) {
                    serverLevel.getWaypointManager().updateWaypoint(waypoint);
                }

                if (this instanceof ServerPlayer player && player.isReceivingWaypoints() && player.connection != null) {
                    serverLevel.getWaypointManager().updatePlayer(player);
                }
            }
            // Paper start - Fix MC-44654
            if (this.getType().updateInterval() == Integer.MAX_VALUE) {
                this.needsSync = true;
            }
            // Paper end - Fix MC-44654
        }
        // Paper start - Block invalid positions and bounding box; don't allow desync of pos and AABB
        // hanging has its own special logic
        if (!(this instanceof net.minecraft.world.entity.decoration.HangingEntity) && (forceBoundingBoxUpdate || posChanged)) {
            this.setBoundingBox(this.makeBoundingBox());
        }
        // Paper end - Block invalid positions and bounding box
    }

    public void checkDespawn() {
    }

    public Vec3[] getQuadLeashHolderOffsets() {
        return Leashable.createQuadLeashOffsets(this, 0.0, 0.5, 0.5, 0.0);
    }

    public boolean supportQuadLeashAsHolder() {
        return false;
    }

    public void notifyLeashHolder(final Leashable entity) {
    }

    public void notifyLeasheeRemoved(final Leashable entity) {
    }

    public Vec3 getRopeHoldPosition(final float partialTickTime) {
        return this.getPosition(partialTickTime).add(0.0, this.eyeHeight * 0.7, 0.0);
    }

    public void recreateFromPacket(final ClientboundAddEntityPacket packet) {
        int entityId = packet.getId();
        double x = packet.getX();
        double y = packet.getY();
        double z = packet.getZ();
        this.syncPacketPositionCodec(x, y, z);
        this.snapTo(x, y, z, packet.getYRot(), packet.getXRot());
        this.setId(entityId);
        this.setUUID(packet.getUUID());
        this.setDeltaMovement(packet.getMovement());
    }

    public @Nullable ItemStack getPickResult() {
        return null;
    }

    public void setIsInPowderSnow(final boolean isInPowderSnow) {
        this.isInPowderSnow = isInPowderSnow;
    }

    public boolean canFreeze() {
        return !this.is(EntityTypeTags.FREEZE_IMMUNE_ENTITY_TYPES);
    }

    public boolean isFreezing() {
        return this.getTicksFrozen() > 0;
    }

    // CraftBukkit start
    public float getBukkitYaw() {
        return this.yRot;
    }
    // CraftBukkit end

    public float getYRot() {
        return this.yRot;
    }

    @Override
    public float getVisualRotationYInDegrees() {
        return this.getYRot();
    }

    public void setYRot(final float yRot) {
        if (!Float.isFinite(yRot)) {
            Util.logAndPauseIfInIde("Invalid entity rotation: " + yRot + ", discarding.");
        } else {
            this.yRot = yRot;
        }
    }

    public float getXRot() {
        return this.xRot;
    }

    public void setXRot(final float xRot) {
        if (!Float.isFinite(xRot)) {
            Util.logAndPauseIfInIde("Invalid entity rotation: " + xRot + ", discarding.");
        } else {
            this.xRot = Math.clamp(xRot % 360.0F, -90.0F, 90.0F);
        }
    }

    public boolean canSprint() {
        return false;
    }

    public float maxUpStep() {
        return 0.0F;
    }

    public void onExplosionHit(final @Nullable Entity explosionCausedBy) {
    }

    @Override
    public final boolean isRemoved() {
        return this.removalReason != null;
    }

    // Folia start - region threading
    public final boolean hasNullCallback() {
        return this.levelCallback == EntityInLevelCallback.NULL;
    }
    // Folia end - region threading

    public Entity.@Nullable RemovalReason getRemovalReason() {
        return this.removalReason;
    }

    @Override
    public final void setRemoved(final Entity.RemovalReason reason, org.bukkit.event.entity.EntityRemoveEvent.@Nullable Cause cause) { // CraftBukkit - add Bukkit remove cause
        // Paper start - rewrite chunk system
        if (!((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel)this.level).moonrise$getEntityLookup().canRemoveEntity((Entity)(Object)this)) {
            LOGGER.warn("Entity " + this + " is currently prevented from being removed from the world since it is processing section status updates", new Throwable());
            return;
        }
        // Paper end - rewrite chunk system
        org.bukkit.craftbukkit.event.CraftEventFactory.callEntityRemoveEvent(this, cause); // CraftBukkit
        final boolean alreadyRemoved = this.removalReason != null; // Paper - Folia schedulers
        // Folia start - region threading
        this.preRemove(removalReason);
        // Folia end - region threading
        if (this.removalReason == null) {
            this.removalReason = reason;
        }

        if (this.removalReason.shouldDestroy()) {
            this.stopRiding();
        }

        if (this.removalReason != Entity.RemovalReason.UNLOADED_TO_CHUNK) { this.getPassengers().forEach(Entity::stopRiding); } // Paper - rewrite chunk system
        this.levelCallback.onRemove(reason);
        this.onRemoval(reason);
        // Paper start - Folia schedulers
        if (!(this instanceof ServerPlayer) && reason != RemovalReason.CHANGED_DIMENSION && !alreadyRemoved) {
            // Players need to be special cased, because they are regularly removed from the world
            this.retireScheduler();
        }
        // Paper end - Folia schedulers
    }

    public void unsetRemoved() {
        this.removalReason = null;
    }

    // Folia start - region threading
    protected void preRemove(Entity.RemovalReason reason) {}
    // Folia end - region threading

    // Paper start - Folia schedulers
    /**
     * Invoked only when the entity is truly removed from the server, never to be added to any world.
     */
    public final void retireScheduler() {
        // we need to force create the bukkit entity so that the scheduler can be retired...
        this.getBukkitEntity().taskScheduler.retire();
    }
    // Paper end - Folia schedulers
    // Paper start - optimise Folia entity scheduler
    public final void registerScheduler() {
        this.getBukkitEntity().taskScheduler.registerTo(io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().entitySchedulerTickList); // Folia - region threading
    }
    // Paper end - optimise Folia entity scheduler

    @Override
    public void setLevelCallback(final EntityInLevelCallback levelCallback) {
        this.levelCallback = levelCallback;
    }

    @Override
    public boolean shouldBeSaved() {
        return (this.removalReason == null || this.removalReason.shouldSave())
            && !this.isPassenger()
            && (!this.isVehicle() || !((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity)this).moonrise$hasAnyPlayerPassengers()); // Paper - rewrite chunk system
    }

    @Override
    public boolean isAlwaysTicking() {
        return false;
    }

    public boolean mayInteract(final ServerLevel level, final BlockPos pos) {
        return true;
    }

    public boolean isFlyingVehicle() {
        return false;
    }

    @Override
    public Level level() {
        return this.level;
    }

    public void setLevel(final Level level) {
        this.level = level;
    }

    public DamageSources damageSources() {
        return this.level().damageSources();
    }

    public RegistryAccess registryAccess() {
        return this.level().registryAccess();
    }

    protected void lerpPositionAndRotationStep(
        final int stepsToTarget, final double targetX, final double targetY, final double targetZ, final double targetYRot, final double targetXRot
    ) {
        double alpha = 1.0 / stepsToTarget;
        double x = Mth.lerp(alpha, this.getX(), targetX);
        double y = Mth.lerp(alpha, this.getY(), targetY);
        double z = Mth.lerp(alpha, this.getZ(), targetZ);
        float yRot = (float)Mth.rotLerp(alpha, this.getYRot(), targetYRot);
        float xRot = (float)Mth.lerp(alpha, this.getXRot(), targetXRot);
        this.setPos(x, y, z);
        this.setRot(yRot, xRot);
    }

    public RandomSource getRandom() {
        return this.random;
    }

    public Vec3 getKnownMovement() {
        return this.getControllingPassenger() instanceof Player controller && this.isAlive() ? controller.getKnownMovement() : this.getDeltaMovement();
    }

    public Vec3 getKnownSpeed() {
        return this.getControllingPassenger() instanceof Player controller && this.isAlive() ? controller.getKnownSpeed() : this.lastKnownSpeed;
    }

    public @Nullable ItemStack getWeaponItem() {
        return null;
    }

    public Optional<ResourceKey<LootTable>> getLootTable() {
        return this.type.getDefaultLootTable();
    }

    protected void applyImplicitComponents(final DataComponentGetter components) {
        this.applyImplicitComponentIfPresent(components, DataComponents.CUSTOM_NAME);
        this.applyImplicitComponentIfPresent(components, DataComponents.CUSTOM_DATA);
    }

    public final void applyComponentsFromItemStack(final ItemStack stack) {
        this.applyImplicitComponents(stack.getComponents());
    }

    @Override
    public <T> @Nullable T get(final DataComponentType<? extends T> type) {
        if (type == DataComponents.CUSTOM_NAME) {
            return castComponentValue((DataComponentType<T>)type, this.getCustomName());
        } else {
            return type == DataComponents.CUSTOM_DATA
                ? castComponentValue((DataComponentType<T>)type, this.customData)
                : this.typeHolder().components().get(type);
        }
    }

    @Contract("_,!null->!null;_,_->_")
    protected static <T> @Nullable T castComponentValue(final DataComponentType<T> type, final @Nullable Object value) {
        return (T)value;
    }

    public <T> void setComponent(final DataComponentType<T> type, final T value) {
        this.applyImplicitComponent(type, value);
    }

    protected <T> boolean applyImplicitComponent(final DataComponentType<T> type, final T value) {
        if (type == DataComponents.CUSTOM_NAME) {
            this.setCustomName(castComponentValue(DataComponents.CUSTOM_NAME, value));
            return true;
        } else if (type == DataComponents.CUSTOM_DATA) {
            this.customData = castComponentValue(DataComponents.CUSTOM_DATA, value);
            return true;
        } else {
            return false;
        }
    }

    protected <T> boolean applyImplicitComponentIfPresent(final DataComponentGetter components, final DataComponentType<T> type) {
        T value = components.get(type);
        return value != null && this.applyImplicitComponent(type, value);
    }

    public ProblemReporter.PathElement problemPath() {
        return new Entity.EntityPathElement(this);
    }

    @Override
    public void registerDebugValues(final ServerLevel level, final DebugValueSource.Registration registration) {
    }

    public @Nullable AABB getFluidInteractionBox() {
        double margin = 0.001;
        AABB box = this.getBoundingBox().deflate(0.001);
        Entity vehicle = this.getVehicle();
        if (vehicle != null) {
            box = vehicle.modifyPassengerFluidInteractionBox(box);
        }

        return box;
    }

    protected @Nullable AABB modifyPassengerFluidInteractionBox(final AABB passengerBox) {
        return passengerBox;
    }

    private record EntityPathElement(Entity entity) implements ProblemReporter.PathElement {
        @Override
        public String get() {
            return this.entity.toString();
        }
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    public @interface Flags {
    }

    @FunctionalInterface
    public interface MoveFunction {
        void accept(Entity target, double x, double y, double z);
    }

    private record Movement(Vec3 from, Vec3 to, Optional<Vec3> axisDependentOriginalMovement) {
        public Movement(final Vec3 from, final Vec3 to, final Vec3 axisDependentOriginalMovement) {
            this(from, to, Optional.of(axisDependentOriginalMovement));
        }

        public Movement(final Vec3 from, final Vec3 to) {
            this(from, to, Optional.empty());
        }
    }

    public enum MovementEmission {
        NONE(false, false),
        SOUNDS(true, false),
        EVENTS(false, true),
        ALL(true, true);

        private final boolean sounds;
        private final boolean events;

        MovementEmission(final boolean sounds, final boolean events) {
            this.sounds = sounds;
            this.events = events;
        }

        public boolean emitsAnything() {
            return this.events || this.sounds;
        }

        public boolean emitsEvents() {
            return this.events;
        }

        public boolean emitsSounds() {
            return this.sounds;
        }
    }

    public enum RemovalReason {
        KILLED(true, false),
        DISCARDED(true, false),
        UNLOADED_TO_CHUNK(false, true),
        UNLOADED_WITH_PLAYER(false, false),
        CHANGED_DIMENSION(false, false);

        private final boolean destroy;
        private final boolean save;

        RemovalReason(final boolean destroy, final boolean save) {
            this.destroy = destroy;
            this.save = save;
        }

        public boolean shouldDestroy() {
            return this.destroy;
        }

        public boolean shouldSave() {
            return this.save;
        }
    }

    // Paper start
    public boolean isTicking() {
        return ((ServerLevel) this.level()).isPositionEntityTicking(this.blockPosition());
    }
    // Paper end
}
