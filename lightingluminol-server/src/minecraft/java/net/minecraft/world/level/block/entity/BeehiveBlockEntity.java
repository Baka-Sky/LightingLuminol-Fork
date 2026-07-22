package net.minecraft.world.level.block.entity;

import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.debug.DebugHiveInfo;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.DebugValueSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.Bees;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class BeehiveBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG_FLOWER_POS = "flower_pos";
    private static final String BEES = "bees";
    private static final List<String> IGNORED_BEE_TAGS = Arrays.asList(
        "Air",
        "drop_chances",
        "equipment",
        "Brain",
        "CanPickUpLoot",
        "DeathTime",
        "fall_distance",
        "FallFlying",
        "Fire",
        "HurtTime",
        "LeftHanded",
        "Motion",
        "NoGravity",
        "OnGround",
        "PortalCooldown",
        "Pos",
        "Rotation",
        "sleeping_pos",
        "CannotEnterHiveTicks",
        "TicksSincePollination",
        "CropsGrownSincePollination",
        "hive_pos",
        "Passengers",
        "leash",
        "UUID"
    );
    public static final int MAX_OCCUPANTS = 3;
    private static final int MIN_TICKS_BEFORE_REENTERING_HIVE = 400;
    private static final int MIN_OCCUPATION_TICKS_NECTAR = 2400;
    public static final int MIN_OCCUPATION_TICKS_NECTARLESS = 600;
    private List<BeehiveBlockEntity.BeeData> stored = Lists.newArrayList();
    public @Nullable BlockPos savedFlowerPos;
    public int maxBees = MAX_OCCUPANTS; // CraftBukkit - allow setting max amount of bees a hive can hold

    public BeehiveBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        super(BlockEntityTypes.BEEHIVE, worldPosition, blockState);
    }

    @Override
    public void setChanged() {
        if (this.isFireNearby()) {
            this.emptyAllLivingFromHive(null, this.level.getBlockState(this.getBlockPos()), BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY);
        }

        super.setChanged();
    }

    public boolean isFireNearby() {
        if (this.level == null) {
            return false;
        }

        for (BlockPos pos : BlockPos.betweenClosed(this.worldPosition.offset(-1, -1, -1), this.worldPosition.offset(1, 1, 1))) {
            if (this.level.getBlockState(pos).getBlock() instanceof FireBlock) {
                return true;
            }
        }

        return false;
    }

    public boolean isEmpty() {
        return this.stored.isEmpty();
    }

    public boolean isFull() {
        return this.stored.size() == this.maxBees; // CraftBukkit
    }

    public void emptyAllLivingFromHive(final @Nullable Player player, final BlockState state, final BeehiveBlockEntity.BeeReleaseStatus releaseReason) {
        if (this.level == null) return; // Paper - do not fire from worldgen thread
        List<Entity> releasedFromHive = this.releaseAllOccupants(state, releaseReason);
        if (player != null) {
            for (Entity released : releasedFromHive) {
                if (released instanceof Bee bee && player.position().distanceToSqr(released.position()) <= 16.0) {
                    if (!this.isSedated()) {
                        bee.setTarget(player, org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_PLAYER); // CraftBukkit
                    } else {
                        bee.setStayOutOfHiveCountdown(400);
                    }
                }
            }
        }
    }

    private List<Entity> releaseAllOccupants(final BlockState state, final BeehiveBlockEntity.BeeReleaseStatus releaseStatus) {
        // CraftBukkit start - This allows us to bypass the night/rain/emergency check
        return this.releaseBees(state, releaseStatus, false);
    }
    public List<Entity> releaseBees(final BlockState state, final BeehiveBlockEntity.BeeReleaseStatus releaseStatus, final boolean force) {
        // CraftBukkit end - This allows us to bypass the night/rain/emergency check
        List<Entity> spawned = Lists.newArrayList();
        this.stored
            .removeIf(
                occupantEntry -> releaseOccupant(this.level, this.worldPosition, state, occupantEntry.toOccupant(), spawned, releaseStatus, this.savedFlowerPos, force) // CraftBukkit - This allows us to bypass the night/rain/emergency check
            );
        if (!spawned.isEmpty()) {
            super.setChanged();
        }

        return spawned;
    }

    @VisibleForDebug
    public int getOccupantCount() {
        return this.stored.size();
    }

    // Paper start - Add EntityBlockStorage clearEntities
    public void clearBees() {
        this.stored.clear();
    }
    // Paper end - Add EntityBlockStorage clearEntities

    public static int getHoneyLevel(final BlockState blockState) {
        return blockState.getValue(BeehiveBlock.HONEY_LEVEL);
    }

    @VisibleForDebug
    public boolean isSedated() {
        return CampfireBlock.isSmokeyPos(this.level, this.getBlockPos());
    }

    public void addOccupant(final Bee bee) {
        if (this.stored.size() < this.maxBees) { // CraftBukkit
            // CraftBukkit start
            if (this.level != null) {
                org.bukkit.event.entity.EntityEnterBlockEvent event = new org.bukkit.event.entity.EntityEnterBlockEvent(bee.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(this.level, this.getBlockPos()));
                if (!event.callEvent()) {
                    bee.setStayOutOfHiveCountdown(MIN_TICKS_BEFORE_REENTERING_HIVE);
                    return;
                }
            }
            // CraftBukkit end
            bee.stopRiding();
            bee.ejectPassengers();
            bee.dropLeash();
            this.storeBee(BeehiveBlockEntity.Occupant.of(bee));
            if (this.level != null) {
                if (bee.hasSavedFlowerPos() && (!this.hasSavedFlowerPos() || this.level.getRandom().nextBoolean())) {
                    this.savedFlowerPos = bee.getSavedFlowerPos();
                }

                BlockPos blockPos = this.getBlockPos();
                this.level.playSound(null, blockPos.getX(), blockPos.getY(), blockPos.getZ(), SoundEvents.BEEHIVE_ENTER, SoundSource.BLOCKS, 1.0F, 1.0F);
                this.level.gameEvent(GameEvent.BLOCK_CHANGE, blockPos, GameEvent.Context.of(bee, this.getBlockState()));
            }

            bee.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.ENTER_BLOCK); // CraftBukkit - add Bukkit remove cause
            super.setChanged();
        }
    }

    public void storeBee(final BeehiveBlockEntity.Occupant occupant) {
        this.stored.add(new BeehiveBlockEntity.BeeData(occupant));
    }

    // CraftBukkit start
    private static boolean releaseOccupant(final Level level, final BlockPos blockPos, final BlockState state, final BeehiveBlockEntity.Occupant beeData, final @Nullable List<Entity> spawned, final BeehiveBlockEntity.BeeReleaseStatus releaseStatus, final @Nullable BlockPos savedFlowerPos) {
        return releaseOccupant(level, blockPos, state, beeData, spawned, releaseStatus, savedFlowerPos, false);
    }
    // CraftBukkit end

    private static boolean releaseOccupant(
        final Level level,
        final BlockPos blockPos,
        final BlockState state,
        final BeehiveBlockEntity.Occupant beeData,
        final @Nullable List<Entity> spawned,
        final BeehiveBlockEntity.BeeReleaseStatus releaseStatus,
        final @Nullable BlockPos savedFlowerPos
        , final boolean force // CraftBukkit
    ) {
        if (!force && level.environmentAttributes().getValue(EnvironmentAttributes.BEES_STAY_IN_HIVE, blockPos) // CraftBukkit
            && releaseStatus != BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY) {
            return false;
        }

        Direction facing = state.getValue(BeehiveBlock.FACING);
        BlockPos facingPos = blockPos.relative(facing);
        boolean frontBlocked = !level.getBlockState(facingPos).getCollisionShape(level, facingPos).isEmpty();
        if (frontBlocked && releaseStatus != BeehiveBlockEntity.BeeReleaseStatus.EMERGENCY) {
            return false;
        }

        Entity entity = beeData.createEntity(level, blockPos);
        if (entity != null) {
            // CraftBukkit start
            if (entity instanceof Bee) {
                float bbWidth = entity.getBbWidth();
                double delta = frontBlocked ? 0.0 : 0.55 + bbWidth / 2.0F;
                double spawnX = blockPos.getX() + 0.5 + delta * facing.getStepX();
                double spawnY = blockPos.getY() + 0.5 - entity.getBbHeight() / 2.0F;
                double spawnZ = blockPos.getZ() + 0.5 + delta * facing.getStepZ();
                entity.snapTo(spawnX, spawnY, spawnZ, entity.getYRot(), entity.getXRot());
            }
            if (!level.addFreshEntity(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BEEHIVE)) return false; // CraftBukkit - SpawnReason, moved from below
            // CraftBukkit end
            if (entity instanceof Bee bee) {
                RandomSource random = level.getRandom();
                if (savedFlowerPos != null && !bee.hasSavedFlowerPos() && random.nextFloat() < 0.9F) {
                    bee.setSavedFlowerPos(savedFlowerPos);
                }

                if (releaseStatus == BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED) {
                    bee.dropOffNectar();
                    if (state.is(BlockTags.BEEHIVES, s -> s.hasProperty(BeehiveBlock.HONEY_LEVEL))) {
                        int honeyLevel = getHoneyLevel(state);
                        if (honeyLevel < 5) {
                            int levelIncrease = random.nextInt(100) == 0 ? 2 : 1;
                            if (honeyLevel + levelIncrease > 5) {
                                levelIncrease--;
                            }

                            // Paper start - Fire EntityChangeBlockEvent in more places
                            BlockState newBlockState = state.setValue(BeehiveBlock.HONEY_LEVEL, honeyLevel + levelIncrease);

                            if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(entity, blockPos, newBlockState)) {
                                level.setBlockAndUpdate(blockPos, newBlockState);
                            }
                            // Paper end - Fire EntityChangeBlockEvent in more places
                        }
                    }
                }

                if (spawned != null) {
                    spawned.add(bee);
                }

                /* CraftBukkit start - move up
                float bbWidth = entity.getBbWidth();
                double delta = frontBlocked ? 0.0 : 0.55 + bbWidth / 2.0F;
                double spawnX = blockPos.getX() + 0.5 + delta * facing.getStepX();
                double spawnY = blockPos.getY() + 0.5 - entity.getBbHeight() / 2.0F;
                double spawnZ = blockPos.getZ() + 0.5 + delta * facing.getStepZ();
                entity.snapTo(spawnX, spawnY, spawnZ, entity.getYRot(), entity.getXRot());
                */ // CraftBukkit end
            }

            level.playSound(null, blockPos, SoundEvents.BEEHIVE_EXIT, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(GameEvent.BLOCK_CHANGE, blockPos, GameEvent.Context.of(entity, level.getBlockState(blockPos)));
            return true; // CraftBukkit - moved up
        } else {
            return false;
        }
    }

    private boolean hasSavedFlowerPos() {
        return this.savedFlowerPos != null;
    }

    private static void tickOccupants(
        final Level level, final BlockPos pos, final BlockState state, final List<BeehiveBlockEntity.BeeData> stored, final @Nullable BlockPos savedFlowerPos
    ) {
        boolean changed = false;
        Iterator<BeehiveBlockEntity.BeeData> iterator = stored.iterator();

        while (iterator.hasNext()) {
            BeehiveBlockEntity.BeeData data = iterator.next();
            if (data.tick()) {
                BeehiveBlockEntity.BeeReleaseStatus releaseStatus = data.hasNectar()
                    ? BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED
                    : BeehiveBlockEntity.BeeReleaseStatus.BEE_RELEASED;
                if (releaseOccupant(level, pos, state, data.toOccupant(), null, releaseStatus, savedFlowerPos)) {
                    changed = true;
                    iterator.remove();
                }
                // Paper start - Fix bees aging inside; use exitTickCounter to keep actual bee life
                else if (level.paperConfig().entities.behavior.cooldownFailedBeehiveReleases) {
                    data.exitTickCounter = data.occupant.minTicksInHive / 2;
                }
                // Paper end - Fix bees aging inside; use exitTickCounter to keep actual bee life
            }
        }

        if (changed) {
            setChanged(level, pos, state);
        }
    }

    public static void serverTick(final Level level, final BlockPos blockPos, final BlockState state, final BeehiveBlockEntity entity) {
        tickOccupants(level, blockPos, state, entity.stored, entity.savedFlowerPos);
        if (!entity.stored.isEmpty() && level.getRandom().nextDouble() < 0.005) {
            double x = blockPos.getX() + 0.5;
            double y = blockPos.getY();
            double z = blockPos.getZ() + 0.5;
            level.playSound(null, x, y, z, SoundEvents.BEEHIVE_WORK, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.stored = Lists.newArrayList(); // CraftBukkit - SPIGOT-7790: create new copy (may be modified in physics event triggered by honey change)
        input.read("bees", BeehiveBlockEntity.Occupant.LIST_CODEC).orElse(List.of()).forEach(this::storeBee);
        this.savedFlowerPos = input.read("flower_pos", BlockPos.CODEC).orElse(null);
        this.maxBees = input.getIntOr("Bukkit.MaxEntities", MAX_OCCUPANTS); // Paper - persist max bukkit occupants
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        output.store("bees", BeehiveBlockEntity.Occupant.LIST_CODEC, this.getBees());
        output.storeNullable("flower_pos", BlockPos.CODEC, this.savedFlowerPos);
        output.putInt("Bukkit.MaxEntities", this.maxBees); // Paper - persist max bukkit occupants
    }

    @Override
    protected void applyImplicitComponents(final DataComponentGetter components) {
        super.applyImplicitComponents(components);
        this.stored = Lists.newArrayList(); // CraftBukkit - SPIGOT-7790: create new copy (may be modified in physics event triggered by honey change)
        List<BeehiveBlockEntity.Occupant> bees = components.getOrDefault(DataComponents.BEES, Bees.EMPTY).bees();
        bees.forEach(this::storeBee);
    }

    @Override
    protected void collectImplicitComponents(final DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(DataComponents.BEES, new Bees(this.getBees()));
    }

    @Override
    public void removeComponentsFromTag(final ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("bees");
    }

    private List<BeehiveBlockEntity.Occupant> getBees() {
        return this.stored.stream().map(BeehiveBlockEntity.BeeData::toOccupant).toList();
    }

    @Override
    public void registerDebugValues(final ServerLevel level, final DebugValueSource.Registration registration) {
        registration.register(DebugSubscriptions.BEE_HIVES, () -> DebugHiveInfo.pack(this));
    }

    private static class BeeData {
        private final BeehiveBlockEntity.Occupant occupant;
        private int exitTickCounter; // Paper - Fix bees aging inside hives; separate counter for checking if bee should exit to reduce exit attempts
        private int ticksInHive;

        private BeeData(final BeehiveBlockEntity.Occupant occupant) {
            this.occupant = occupant;
            this.ticksInHive = occupant.ticksInHive();
            this.exitTickCounter = this.ticksInHive; // Paper - Fix bees aging inside hives
        }

        public boolean tick() {
            this.ticksInHive++; // Paper - Fix bees aging inside hives
            return this.exitTickCounter++ > this.occupant.minTicksInHive; // Paper - Fix bees aging inside hives
        }

        public BeehiveBlockEntity.Occupant toOccupant() {
            return new BeehiveBlockEntity.Occupant(this.occupant.entityData, this.ticksInHive, this.occupant.minTicksInHive);
        }

        public boolean hasNectar() {
            return this.occupant.entityData.getUnsafe().getBooleanOr("HasNectar", false);
        }
    }

    public enum BeeReleaseStatus {
        HONEY_DELIVERED,
        BEE_RELEASED,
        EMERGENCY;
    }

    public record Occupant(TypedEntityData<EntityType<?>> entityData, int ticksInHive, int minTicksInHive) {
        public static final Codec<BeehiveBlockEntity.Occupant> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                    TypedEntityData.codec(EntityType.CODEC).fieldOf("entity_data").forGetter(BeehiveBlockEntity.Occupant::entityData),
                    Codec.INT.fieldOf("ticks_in_hive").forGetter(BeehiveBlockEntity.Occupant::ticksInHive),
                    Codec.INT.fieldOf("min_ticks_in_hive").forGetter(BeehiveBlockEntity.Occupant::minTicksInHive)
                )
                .apply(i, BeehiveBlockEntity.Occupant::new)
        );
        public static final Codec<List<BeehiveBlockEntity.Occupant>> LIST_CODEC = CODEC.listOf();
        public static final StreamCodec<RegistryFriendlyByteBuf, BeehiveBlockEntity.Occupant> STREAM_CODEC = StreamCodec.composite(
            TypedEntityData.streamCodec(EntityType.STREAM_CODEC),
            BeehiveBlockEntity.Occupant::entityData,
            ByteBufCodecs.VAR_INT,
            BeehiveBlockEntity.Occupant::ticksInHive,
            ByteBufCodecs.VAR_INT,
            BeehiveBlockEntity.Occupant::minTicksInHive,
            BeehiveBlockEntity.Occupant::new
        );

        public static BeehiveBlockEntity.Occupant of(final Entity entity) {
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), BeehiveBlockEntity.LOGGER)) {
                TagValueOutput output = TagValueOutput.createWithContext(reporter, entity.registryAccess());
                entity.save(output);
                BeehiveBlockEntity.IGNORED_BEE_TAGS.forEach(output::discard);
                CompoundTag entityTag = output.buildResult();
                boolean hasNectar = entityTag.getBooleanOr("HasNectar", false);
                return new BeehiveBlockEntity.Occupant(TypedEntityData.of(entity.getType(), entityTag), 0, hasNectar ? 2400 : 600);
            }
        }

        public static BeehiveBlockEntity.Occupant create(final int ticksInHive) {
            return new BeehiveBlockEntity.Occupant(TypedEntityData.of(EntityTypes.BEE, new CompoundTag()), ticksInHive, 600);
        }

        public @Nullable Entity createEntity(final Level level, final BlockPos hivePos) {
            CompoundTag entityTag = this.entityData.copyTagWithoutId();
            BeehiveBlockEntity.IGNORED_BEE_TAGS.forEach(entityTag::remove);
            Entity entity = EntityType.loadEntityRecursive(this.entityData.type(), entityTag, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity != null && entity.is(EntityTypeTags.BEEHIVE_INHABITORS)) {
                entity.setNoGravity(true);
                if (entity instanceof Bee bee) {
                    bee.setHivePos(hivePos);
                    setBeeReleaseData(this.ticksInHive, bee);
                }

                return entity;
            } else {
                return null;
            }
        }

        private static void setBeeReleaseData(final int ticksInHive, final Bee bee) {
            updateBeeAge(ticksInHive, bee);
            bee.setInLoveTime(Math.max(0, bee.getInLoveTime() - ticksInHive));
        }

        private static void updateBeeAge(final int ticksInHive, final Bee bee) {
            if (!bee.isAgeLocked()) {
                int age = bee.getAge();
                if (age < 0) {
                    bee.setAge(Math.min(0, age + ticksInHive));
                } else if (age > 0) {
                    bee.setAge(Math.max(0, age - ticksInHive));
                }
            }
        }
    }
}
