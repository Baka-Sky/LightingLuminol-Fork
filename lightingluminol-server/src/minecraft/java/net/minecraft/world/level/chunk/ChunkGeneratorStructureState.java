package net.minecraft.world.level.chunk;

import com.google.common.base.Stopwatch;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ChunkGeneratorStructureState {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final long levelSeed;
    private final long concentricRingsSeed;
    private final Map<Structure, List<StructurePlacement>> placementsForStructure = new Object2ObjectOpenHashMap<>();
    private final Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> ringPositions = new Object2ObjectArrayMap<>();
    private boolean hasGeneratedPositions;
    private final List<Holder<StructureSet>> possibleStructureSets;
    public final org.spigotmc.SpigotWorldConfig conf; // Paper - Add missing structure set seed configs

    public static ChunkGeneratorStructureState createForFlat(
        final RandomState randomState, final long levelSeed, final BiomeSource biomeSource, final Stream<Holder<StructureSet>> structureOverrides, org.spigotmc.SpigotWorldConfig conf // Spigot
    ) {
        List<Holder<StructureSet>> structures = structureOverrides.filter(structureSet -> hasBiomesForStructureSet(structureSet.value(), biomeSource)).toList();
        return new ChunkGeneratorStructureState(randomState, biomeSource, levelSeed, 0L, ChunkGeneratorStructureState.injectSpigot(structures, conf), conf); // Spigot
    }

    public static ChunkGeneratorStructureState createForNormal(
        final RandomState randomState, final long levelSeed, final BiomeSource biomeSource, final HolderLookup<StructureSet> allStructures, org.spigotmc.SpigotWorldConfig conf // Spigot
    ) {
        List<Holder<StructureSet>> structures = allStructures.listElements()
            .filter(structureSet -> hasBiomesForStructureSet(structureSet.value(), biomeSource))
            .collect(Collectors.toUnmodifiableList());
        return new ChunkGeneratorStructureState(randomState, biomeSource, levelSeed, levelSeed, ChunkGeneratorStructureState.injectSpigot(structures, conf), conf); // Spigot
    }
    // Paper start - Add missing structure set seed configs; horrible hack because spigot creates a ton of direct Holders which lose track of the identifying key
    public static final class KeyedRandomSpreadStructurePlacement extends net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement {
        public final net.minecraft.resources.ResourceKey<StructureSet> key;
        public KeyedRandomSpreadStructurePlacement(net.minecraft.resources.ResourceKey<StructureSet> key, net.minecraft.core.Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod, float frequency, int salt, java.util.Optional<StructurePlacement.ExclusionZone> exclusionZone, int spacing, int separation, net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType spreadType) {
            super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone, spacing, separation, spreadType);
            this.key = key;
        }
    }
    // Paper end - Add missing structure set seed configs

    // Spigot start
    private static List<Holder<StructureSet>> injectSpigot(List<Holder<StructureSet>> list, org.spigotmc.SpigotWorldConfig conf) {
        return list.stream().map((holder) -> {
            StructureSet structureset = holder.value();
            final Holder<StructureSet> newHolder; // Paper - Add missing structure set seed configs
            if (structureset.placement() instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement randomConfig && holder.unwrapKey().orElseThrow().identifier().getNamespace().equals(net.minecraft.resources.Identifier.DEFAULT_NAMESPACE)) { // Paper - Add missing structure set seed configs; check namespace cause datapacks could add structure sets with the same path
                String name = holder.unwrapKey().orElseThrow().identifier().getPath();
                int seed = randomConfig.salt;

                switch (name) {
                    case "desert_pyramids":
                        seed = conf.desertSeed;
                        break;
                    case "end_cities":
                        seed = conf.endCitySeed;
                        break;
                    case "nether_complexes":
                        seed = conf.netherSeed;
                        break;
                    case "igloos":
                        seed = conf.iglooSeed;
                        break;
                    case "jungle_temples":
                        seed = conf.jungleSeed;
                        break;
                    case "woodland_mansions":
                        seed = conf.mansionSeed;
                        break;
                    case "ocean_monuments":
                        seed = conf.monumentSeed;
                        break;
                    case "nether_fossils":
                        seed = conf.fossilSeed;
                        break;
                    case "ocean_ruins":
                        seed = conf.oceanSeed;
                        break;
                    case "pillager_outposts":
                        seed = conf.outpostSeed;
                        break;
                    case "ruined_portals":
                        seed = conf.portalSeed;
                        break;
                    case "shipwrecks":
                        seed = conf.shipwreckSeed;
                        break;
                    case "swamp_huts":
                        seed = conf.swampSeed;
                        break;
                    case "villages":
                        seed = conf.villageSeed;
                        break;
                    // Paper start - Add missing structure set seed configs
                    case "ancient_cities":
                        seed = conf.ancientCitySeed;
                        break;
                    case "trail_ruins":
                        seed = conf.trailRuinsSeed;
                        break;
                    case "trial_chambers":
                        seed = conf.trialChambersSeed;
                        break;
                    // Paper end - Add missing structure set seed configs
                }

            // Paper start - Add missing structure set seed configs
                structureset = new StructureSet(structureset.structures(), new KeyedRandomSpreadStructurePlacement(holder.unwrapKey().orElseThrow(), randomConfig.locateOffset, randomConfig.frequencyReductionMethod, randomConfig.frequency, seed, randomConfig.exclusionZone, randomConfig.spacing(), randomConfig.separation(), randomConfig.spreadType()));
                newHolder = Holder.direct(structureset); // I really wish we didn't have to do this here
            } else {
                newHolder = holder;
            }
            return newHolder;
            // Paper end - Add missing structure set seed configs
        }).collect(Collectors.toUnmodifiableList());
    }
    // Spigot end

    private static boolean hasBiomesForStructureSet(final StructureSet structureSet, final BiomeSource biomeSource) {
        Stream<Holder<Biome>> structureBiomes = structureSet.structures().stream().flatMap(entry -> {
            Structure structure = entry.structure().value();
            return structure.biomes().stream();
        });
        return structureBiomes.anyMatch(biomeSource.possibleBiomes()::contains);
    }

    private ChunkGeneratorStructureState(
        final RandomState randomState,
        final BiomeSource biomeSource,
        final long levelSeed,
        final long concentricRingsSeed,
        final List<Holder<StructureSet>> possibleStructureSets, org.spigotmc.SpigotWorldConfig conf // Paper - Add missing structure set seed configs
    ) {
        this.randomState = randomState;
        this.levelSeed = levelSeed;
        this.biomeSource = biomeSource;
        this.concentricRingsSeed = concentricRingsSeed;
        this.possibleStructureSets = possibleStructureSets;
        this.conf = conf; // Paper - Add missing structure set seed configs
    }

    public List<Holder<StructureSet>> possibleStructureSets() {
        return this.possibleStructureSets;
    }

    private void generatePositions() {
        Set<Holder<Biome>> possibleBiomes = this.biomeSource.possibleBiomes();
        this.possibleStructureSets().forEach(setHolder -> {
            StructureSet set = setHolder.value();
            boolean hasAnyPlaceableStructures = false;

            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                Structure structure = entry.structure().value();
                if (structure.biomes().stream().anyMatch(possibleBiomes::contains)) {
                    this.placementsForStructure.computeIfAbsent(structure, s -> new ArrayList<>()).add(set.placement());
                    hasAnyPlaceableStructures = true;
                }
            }

            if (hasAnyPlaceableStructures && set.placement() instanceof ConcentricRingsStructurePlacement ringsPlacement) {
                this.ringPositions.put(ringsPlacement, this.generateRingPositions((Holder<StructureSet>)setHolder, ringsPlacement));
            }
        });
    }

    private CompletableFuture<List<ChunkPos>> generateRingPositions(final Holder<StructureSet> structureSet, final ConcentricRingsStructurePlacement placement) {
        if (placement.count() == 0) {
            return CompletableFuture.completedFuture(List.of());
        }

        Stopwatch stopwatch = Stopwatch.createStarted(Util.TICKER);
        int distance = placement.distance();
        int count = placement.count();
        List<CompletableFuture<ChunkPos>> tasks = new ArrayList<>(count);
        int spread = placement.spread();
        HolderSet<Biome> preferredBiomes = placement.preferredBiomes();
        RandomSource random = RandomSource.create();
        // Paper start - Add missing structure set seed configs
        if (this.conf.strongholdSeed != null && structureSet.is(net.minecraft.world.level.levelgen.structure.BuiltinStructureSets.STRONGHOLDS)) {
            random.setSeed(this.conf.strongholdSeed);
        } else {
        // Paper end - Add missing structure set seed configs
        random.setSeed(this.concentricRingsSeed);
        } // Paper - Add missing structure set seed configs
        double angle = random.nextDouble() * Math.PI * 2.0;
        int positionInCircle = 0;
        int circle = 0;

        for (int i = 0; i < count; i++) {
            double dist = 4 * distance + distance * circle * 6 + (random.nextDouble() - 0.5) * (distance * 2.5);
            int initialX = (int)Math.round(Math.cos(angle) * dist);
            int initialZ = (int)Math.round(Math.sin(angle) * dist);
            RandomSource biomeSearchGenerator = random.fork();
            tasks.add(
                CompletableFuture.supplyAsync(
                    () -> {
                        Pair<BlockPos, Holder<Biome>> closestBiome = this.biomeSource
                            .findBiomeHorizontal(
                                SectionPos.sectionToBlockCoord(initialX, 8),
                                0,
                                SectionPos.sectionToBlockCoord(initialZ, 8),
                                112,
                                preferredBiomes::contains,
                                biomeSearchGenerator,
                                this.randomState.sampler()
                            );
                        if (closestBiome != null) {
                            BlockPos position = closestBiome.getFirst();
                            return new ChunkPos(SectionPos.blockToSectionCoord(position.getX()), SectionPos.blockToSectionCoord(position.getZ()));
                        } else {
                            return new ChunkPos(initialX, initialZ);
                        }
                    },
                    Util.backgroundExecutor().forName("structureRings")
                )
            );
            angle += (Math.PI * 2) / spread;
            if (++positionInCircle == spread) {
                circle++;
                positionInCircle = 0;
                spread += 2 * spread / (circle + 1);
                spread = Math.min(spread, count - i);
                angle += random.nextDouble() * Math.PI * 2.0;
            }
        }

        return Util.sequence(tasks).thenApply(ringPositions -> {
            double elapsedSeconds = stopwatch.stop().elapsed(TimeUnit.MILLISECONDS) / 1000.0;
            LOGGER.debug("Calculation for {} took {}s", structureSet, elapsedSeconds);
            return ringPositions;
        });
    }

    public void ensureStructuresGenerated() {
        if (!this.hasGeneratedPositions) {
            this.generatePositions();
            this.hasGeneratedPositions = true;
        }
    }

    public @Nullable List<ChunkPos> getRingPositionsFor(final ConcentricRingsStructurePlacement placement) {
        this.ensureStructuresGenerated();
        CompletableFuture<List<ChunkPos>> result = this.ringPositions.get(placement);
        return result != null ? result.join() : null;
    }

    public List<StructurePlacement> getPlacementsForStructure(final Holder<Structure> structure) {
        this.ensureStructuresGenerated();
        return this.placementsForStructure.getOrDefault(structure.value(), List.of());
    }

    public RandomState randomState() {
        return this.randomState;
    }

    public boolean hasStructureChunkInRange(final Holder<StructureSet> structureSet, final int sourceX, final int sourceZ, final int range) {
        StructurePlacement placement = structureSet.value().placement();

        for (int testX = sourceX - range; testX <= sourceX + range; testX++) {
            for (int testZ = sourceZ - range; testZ <= sourceZ + range; testZ++) {
                if (placement.isStructureChunk(this, testX, testZ, placement instanceof KeyedRandomSpreadStructurePlacement keyed ? keyed.key : null)) { // Paper - Add missing structure set seed configs
                    return true;
                }
            }
        }

        return false;
    }

    public long getLevelSeed() {
        return this.levelSeed;
    }
}
