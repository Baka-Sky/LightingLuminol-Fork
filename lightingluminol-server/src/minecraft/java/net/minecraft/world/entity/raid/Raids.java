package net.minecraft.world.entity.raid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class Raids extends SavedData {
    private static final Identifier RAID_FILE_ID = Identifier.withDefaultNamespace("raids");
    public static final Codec<Raids> CODEC = RecordCodecBuilder.create(
        i -> i.group(
                Raids.RaidWithId.CODEC
                    .listOf()
                    .optionalFieldOf("raids", List.of())
                    .forGetter(r -> r.raidMap.entrySet().stream().map(Raids.RaidWithId::from).toList()), // Folia - make raids thread-safe
                Codec.INT.fieldOf("next_id").forGetter(r -> r.nextAvailableID.get()), // Folia - make raids thread-safe
                Codec.INT.fieldOf("tick").forGetter(r -> r.tick)
            )
            .apply(i, Raids::new)
    );
    public static final SavedDataType<Raids> TYPE = new SavedDataType<>(RAID_FILE_ID, Raids::new, CODEC, DataFixTypes.SAVED_DATA_RAIDS);
    public final ca.spottedleaf.concurrentutil.map.concurrent.ints.ConcurrentChainedInt2ReferenceHashTable<Raid> raidMap = new ca.spottedleaf.concurrentutil.map.concurrent.ints.ConcurrentChainedInt2ReferenceHashTable<>(); // Folia - make raids thread-safe
    private final java.util.concurrent.atomic.AtomicInteger nextAvailableID = new java.util.concurrent.atomic.AtomicInteger(); // Folia - make raids thread-safe
    private int tick;

    public Raids() {
        this.nextAvailableID.set(1); // Folia - make raids thread-safe
        this.setDirty();
    }

    private Raids(final List<Raids.RaidWithId> raids, final int nextId, final int tick) {
        for (Raids.RaidWithId raid : raids) {
            this.raidMap.put(raid.id, raid.raid);
            raid.raid.idOrNegativeOne = raid.id; // Paper - expose id of raids while method is kept around as deprecated for removal
        }

        this.nextAvailableID.set(nextId); // Folia - make raids thread-safe
        this.tick = tick;
    }

    public @Nullable Raid get(final int raidId) {
        return this.raidMap.get(raidId);
    }

    public OptionalInt getId(final Raid raid) {
        for (ca.spottedleaf.concurrentutil.map.concurrent.ints.ConcurrentChainedInt2ReferenceHashTable.TableEntry<Raid> entry : this.raidMap.entrySet()) { // Folia - make raids thread-safe
            if (entry.getValue() == raid) {
                return OptionalInt.of(entry.getKey()); // Folia - make raids thread-safe
            }
        }

        return OptionalInt.empty();
    }

    // Folia start - make raids thread-safe
    public void globalTick() {
        this.tick++;
    }

    public void tick(final ServerLevel level) {
        // Folia end - make raids thread-safe
        this.tick++;
        Iterator<Raid> raidIterator = this.raidMap.values().iterator();

        while (raidIterator.hasNext()) {
            Raid raid = raidIterator.next();
            // Folia start - make raids thread-safe
            if (!raid.ownsRaid(level)) {
                continue;
            }
            // Folia end - make raids thread-safe
            if (!level.getGameRules().get(GameRules.RAIDS)) {
                raid.stop();
            }

            if (raid.isStopped()) {
                raidIterator.remove();
                this.setDirty();
            } else {
                raid.tick(level);
            }
        }

        // Folia - make raids thread-safe - move to globalTick()
    }

    public static boolean canJoinRaid(final Raider raider) {
        return raider.isAlive() && raider.canJoinRaid() && raider.getNoActionTime() <= 2400;
    }

    public @Nullable Raid createOrExtendRaid(final ServerPlayer player, final BlockPos raidPosition) {
        if (player.isSpectator()) {
            return null;
        }

        ServerLevel level = player.level();
        if (!level.getGameRules().get(GameRules.RAIDS) || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(player) || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, raidPosition.getX() >> 4, raidPosition.getZ() >> 4, 8) || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, player.chunkPosition().x(), player.chunkPosition().z(), 8)) { // Folia - region threading
            return null;
        }

        if (!level.environmentAttributes().getValue(EnvironmentAttributes.CAN_START_RAID, raidPosition)) {
            return null;
        }

        List<PoiRecord> posses = level.getPoiManager().getInRange(e -> e.is(PoiTypeTags.VILLAGE), raidPosition, 64, PoiManager.Occupancy.IS_OCCUPIED).toList();
        int count = 0;
        Vec3 posTotals = Vec3.ZERO;

        for (PoiRecord p : posses) {
            BlockPos pos = p.getPos();
            posTotals = posTotals.add(pos.getX(), pos.getY(), pos.getZ());
            count++;
        }

        BlockPos raidCenterPos;
        if (count > 0) {
            posTotals = posTotals.scale(1.0 / count);
            raidCenterPos = BlockPos.containing(posTotals);
        } else {
            raidCenterPos = raidPosition;
        }

        Raid raid = this.getOrCreateRaid(level, raidCenterPos);
        // CraftBukkit - moved down
        // if (!raid.isStarted() && !this.raidMap.containsValue(raid)) {
        //     this.raidMap.put(this.getUniqueId(), raid);
        // }

        if (!raid.isStarted() || (raid.isInProgress() && raid.getRaidOmenLevel() < raid.getMaxRaidOmenLevel())) { // CraftBukkit - fixed a bug with raid: players could add up Bad Omen level even when the raid had finished
            // CraftBukkit start
            if (!org.bukkit.craftbukkit.event.CraftEventFactory.callRaidTriggerEvent(level, raid, player)) {
                player.removeEffect(net.minecraft.world.effect.MobEffects.RAID_OMEN);
                return null;
            }

            if (!raid.isStarted() && !this.raidMap.containsValue(raid)) {
                int id = this.getUniqueId(); this.raidMap.put(id, raid); // Folia - region threading
                raid.idOrNegativeOne = id; // Paper - expose id of raids while method is kept around as deprecated for removal // Folia - region threading
            }
            // CraftBukkit end
            raid.absorbRaidOmen(player);
        }

        this.setDirty();
        return raid;
    }

    private Raid getOrCreateRaid(final ServerLevel level, final BlockPos pos) {
        Raid raid = level.getRaidAt(pos);
        return raid != null ? raid : new Raid(pos, level.getDifficulty());
    }

    public static Raids load(final CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).resultOrPartial().orElseGet(Raids::new);
    }

    private int getUniqueId() {
        return this.nextAvailableID.incrementAndGet(); // Folia - make raids thread-safe
    }

    public @Nullable Raid getNearbyRaid(final ServerLevel world, final BlockPos pos, final int maxDistSqr) { // Folia - make raids thread-safe - add ServerLevel param
        Raid closest = null;
        double closestDistanceSqr = maxDistSqr;

        for (Raid raid : this.raidMap.values()) {
            // Folia start - make raids thread-safe
            if (!raid.ownsRaid(world)) {
                continue;
            }
            // Folia end - make raids thread-safe
            double distance = raid.getCenter().distSqr(pos);
            if (raid.isActive() && distance < closestDistanceSqr) {
                closest = raid;
                closestDistanceSqr = distance;
            }
        }

        return closest;
    }

    @VisibleForDebug
    public List<BlockPos> getRaidCentersInChunk(final ChunkPos chunkPos) {
        return this.raidMap.values().stream().map(Raid::getCenter).filter(chunkPos::contains).toList();
    }

    private record RaidWithId(int id, Raid raid) {
        public static final Codec<Raids.RaidWithId> CODEC = RecordCodecBuilder.create(
            i -> i.group(Codec.INT.fieldOf("id").forGetter(Raids.RaidWithId::id), Raid.MAP_CODEC.forGetter(Raids.RaidWithId::raid))
                .apply(i, Raids.RaidWithId::new)
        );

        public static Raids.RaidWithId from(final ca.spottedleaf.concurrentutil.map.concurrent.ints.ConcurrentChainedInt2ReferenceHashTable.TableEntry<Raid> entry) { // Folia - make raids thread-safe
            return new Raids.RaidWithId(entry.getKey(), entry.getValue()); // Folia - make raids thread-safe
        }
    }
}
