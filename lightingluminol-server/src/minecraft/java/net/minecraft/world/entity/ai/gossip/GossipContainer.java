package net.minecraft.world.entity.ai.gossip;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.util.VisibleForDebug;

public class GossipContainer {
    public static final Codec<GossipContainer> CODEC = GossipContainer.GossipEntry.CODEC
        .listOf()
        .xmap(GossipContainer::new, GossipContainer::decompress); // Paper - Perf: Remove streams from hot code
    public static final int DISCARD_THRESHOLD = 2;
    public final Map<UUID, GossipContainer.EntityGossips> gossips = new HashMap<>();

    public GossipContainer() {
    }

    private GossipContainer(final List<GossipContainer.GossipEntry> entries) {
        entries.forEach(e -> this.getOrCreate(e.target).entries.put(e.type, e.value));
    }

    @VisibleForDebug
    public Map<UUID, Object2IntMap<GossipType>> getGossipEntries() {
        Map<UUID, Object2IntMap<GossipType>> result = Maps.newHashMap();
        this.gossips.keySet().forEach(uuid -> {
            GossipContainer.EntityGossips entityGossips = this.gossips.get(uuid);
            result.put(uuid, entityGossips.entries);
        });
        return result;
    }

    public void decay() {
        Iterator<GossipContainer.EntityGossips> iterator = this.gossips.values().iterator();

        while (iterator.hasNext()) {
            GossipContainer.EntityGossips entityGossips = iterator.next();
            entityGossips.decay();
            if (entityGossips.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private Stream<GossipContainer.GossipEntry> unpack() {
        return this.gossips.entrySet().stream().flatMap(e -> e.getValue().unpack(e.getKey()));
    }

    // Paper start - Perf: Remove streams from hot code
    private List<GossipContainer.GossipEntry> decompress() {
        final List<GossipContainer.GossipEntry> entries = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
        for (final Map.Entry<UUID, GossipContainer.EntityGossips> gossips : this.gossips.entrySet()) {
            for (final GossipContainer.GossipEntry entry : gossips.getValue().decompress(gossips.getKey())) {
                if (entry.weightedValue() != 0) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }
    // Paper end - Perf: Remove streams from hot code

    private Collection<GossipContainer.GossipEntry> selectGossipsForTransfer(final RandomSource random, final int maxCount) {
        List<GossipContainer.GossipEntry> entries = this.decompress(); // Paper - Perf: Remove streams from hot code
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        int[] ranges = new int[entries.size()];
        int rangesEnd = 0;

        for (int i = 0; i < entries.size(); i++) {
            GossipContainer.GossipEntry gossip = entries.get(i);
            rangesEnd += Math.abs(gossip.weightedValue());
            ranges[i] = rangesEnd - 1;
        }

        Set<GossipContainer.GossipEntry> results = Sets.newIdentityHashSet();

        for (int i = 0; i < maxCount; i++) {
            int choice = random.nextInt(rangesEnd);
            int selectedIndex = Arrays.binarySearch(ranges, choice);
            results.add(entries.get(selectedIndex < 0 ? -selectedIndex - 1 : selectedIndex));
        }

        return results;
    }

    private GossipContainer.EntityGossips getOrCreate(final UUID target) {
        return this.gossips.computeIfAbsent(target, uuid -> new GossipContainer.EntityGossips());
    }

    public void transferFrom(final GossipContainer source, final RandomSource random, final int maxCount) {
        Collection<GossipContainer.GossipEntry> newGossips = source.selectGossipsForTransfer(random, maxCount);
        newGossips.forEach(newGossip -> {
            int decayedValue = newGossip.value - newGossip.type.decayPerTransfer;
            if (decayedValue >= 2) {
                this.getOrCreate(newGossip.target).entries.mergeInt(newGossip.type, decayedValue, GossipContainer::mergeValuesForTransfer);
            }
        });
    }

    public int getReputation(final UUID entity, final Predicate<GossipType> types) {
        GossipContainer.EntityGossips entry = this.gossips.get(entity);
        return entry != null ? entry.weightedValue(types) : 0;
    }

    public long getCountForType(final GossipType type, final DoublePredicate valueTest) {
        return this.gossips.values().stream().filter(e -> valueTest.test(e.entries.getOrDefault(type, 0) * type.weight)).count();
    }

    public void add(final UUID target, final GossipType type, final int amountToAdd) {
        GossipContainer.EntityGossips entityGossips = this.getOrCreate(target);
        entityGossips.entries.mergeInt(type, amountToAdd, (o, n) -> this.mergeValuesForAddition(type, o, n));
        entityGossips.makeSureValueIsntTooLowOrTooHigh(type);
        if (entityGossips.isEmpty()) {
            this.gossips.remove(target);
        }
    }

    public void remove(final UUID target, final GossipType type, final int amountToRemove) {
        this.add(target, type, -amountToRemove);
    }

    public void remove(final UUID target, final GossipType type) {
        GossipContainer.EntityGossips entityGossips = this.gossips.get(target);
        if (entityGossips != null) {
            entityGossips.remove(type);
            if (entityGossips.isEmpty()) {
                this.gossips.remove(target);
            }
        }
    }

    public void remove(final GossipType type) {
        Iterator<GossipContainer.EntityGossips> iterator = this.gossips.values().iterator();

        while (iterator.hasNext()) {
            GossipContainer.EntityGossips entityGossips = iterator.next();
            entityGossips.remove(type);
            if (entityGossips.isEmpty()) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        this.gossips.clear();
    }

    public void putAll(final GossipContainer container) {
        container.gossips.forEach((target, gossips) -> this.getOrCreate(target).entries.putAll(gossips.entries));
    }

    private static int mergeValuesForTransfer(final int oldValue, final int newValue) {
        return Math.max(oldValue, newValue);
    }

    private int mergeValuesForAddition(final GossipType type, final int oldValue, final int newValue) {
        int sum = oldValue + newValue;
        return sum > type.max ? Math.max(type.max, oldValue) : sum;
    }

    public GossipContainer copy() {
        GossipContainer container = new GossipContainer();
        container.putAll(this);
        return container;
    }

    public static class EntityGossips {
        private final Object2IntMap<GossipType> entries = new Object2IntOpenHashMap<>();

        public int weightedValue(final Predicate<GossipType> types) {
            // Paper start - Perf: Remove streams from hot code
            int weight = 0;
            for (Object2IntMap.Entry<GossipType> entry : this.entries.object2IntEntrySet()) {
                if (types.test(entry.getKey())) {
                    weight += entry.getIntValue() * entry.getKey().weight;
                }
            }
            return weight;
        }

        public List<GossipContainer.GossipEntry> decompress(final UUID target) {
            List<GossipContainer.GossipEntry> entries = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
            for (Object2IntMap.Entry<GossipType> entry : this.entries.object2IntEntrySet()) {
                entries.add(new GossipContainer.GossipEntry(target, entry.getKey(), entry.getIntValue()));
            }
            return entries;
            // Paper end - Perf: Remove streams from hot code
        }

        public Stream<GossipContainer.GossipEntry> unpack(final UUID target) {
            return this.entries.object2IntEntrySet().stream().map(e -> new GossipContainer.GossipEntry(target, e.getKey(), e.getIntValue()));
        }

        public void decay() {
            ObjectIterator<Entry<GossipType>> it = this.entries.object2IntEntrySet().iterator();

            while (it.hasNext()) {
                Entry<GossipType> gossip = it.next();
                int newValue = gossip.getIntValue() - gossip.getKey().decayPerDay;
                if (newValue < 2) {
                    it.remove();
                } else {
                    gossip.setValue(newValue);
                }
            }
        }

        public boolean isEmpty() {
            return this.entries.isEmpty();
        }

        public void makeSureValueIsntTooLowOrTooHigh(final GossipType type) {
            int value = this.entries.getInt(type);
            if (value > type.max) {
                this.entries.put(type, type.max);
            }

            if (value < 2) {
                this.remove(type);
            }
        }

        public void remove(final GossipType type) {
            this.entries.removeInt(type);
        }

        // Paper start - Add villager reputation API
        private static final GossipType[] TYPES = GossipType.values();

        public com.destroystokyo.paper.entity.villager.Reputation asReputation() {
            Map<com.destroystokyo.paper.entity.villager.ReputationType, Integer> map = new java.util.EnumMap<>(com.destroystokyo.paper.entity.villager.ReputationType.class);
            for (Object2IntMap.Entry<GossipType> type : this.entries.object2IntEntrySet()) {
                map.put(toApi(type.getKey()), type.getIntValue());
            }

            return new com.destroystokyo.paper.entity.villager.Reputation(map);
        }

        public void assignFromReputation(com.destroystokyo.paper.entity.villager.Reputation rep) {
            for (GossipType type : TYPES) {
                com.destroystokyo.paper.entity.villager.ReputationType api = toApi(type);

                if (rep.hasReputationSet(api)) {
                    int reputation = rep.getReputation(api);
                    if (reputation == 0) {
                        this.entries.removeInt(type);
                    } else {
                        this.entries.put(type, reputation);
                    }
                }
            }
        }

        private static com.destroystokyo.paper.entity.villager.ReputationType toApi(GossipType type) {
            return switch (type) {
                case MAJOR_NEGATIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MAJOR_NEGATIVE;
                case MINOR_NEGATIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MINOR_NEGATIVE;
                case MINOR_POSITIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MINOR_POSITIVE;
                case MAJOR_POSITIVE -> com.destroystokyo.paper.entity.villager.ReputationType.MAJOR_POSITIVE;
                case TRADING -> com.destroystokyo.paper.entity.villager.ReputationType.TRADING;
            };
        }
        // Paper end - Add villager reputation API
    }

    private record GossipEntry(UUID target, GossipType type, int value) {
        public static final Codec<GossipContainer.GossipEntry> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                    UUIDUtil.CODEC.fieldOf("Target").forGetter(GossipContainer.GossipEntry::target),
                    GossipType.CODEC.fieldOf("Type").forGetter(GossipContainer.GossipEntry::type),
                    ExtraCodecs.POSITIVE_INT.fieldOf("Value").forGetter(GossipContainer.GossipEntry::value)
                )
                .apply(i, GossipContainer.GossipEntry::new)
        );

        public int weightedValue() {
            return this.value * this.type.weight;
        }
    }
}
