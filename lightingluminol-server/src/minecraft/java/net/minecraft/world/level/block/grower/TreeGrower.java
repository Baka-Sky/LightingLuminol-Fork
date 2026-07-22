package net.minecraft.world.level.block.grower;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import org.jspecify.annotations.Nullable;

public final class TreeGrower {
    private static final Map<String, TreeGrower> GROWERS = new Object2ObjectArrayMap<>();
    public static final Codec<TreeGrower> CODEC = Codec.stringResolver(g -> g.name, GROWERS::get);
    public static final TreeGrower OAK = new TreeGrower(
        "oak",
        0.1F,
        Optional.empty(),
        Optional.empty(),
        Optional.of(TreeFeatures.OAK),
        Optional.of(TreeFeatures.FANCY_OAK),
        Optional.of(TreeFeatures.OAK_BEES_005),
        Optional.of(TreeFeatures.FANCY_OAK_BEES_005)
    );
    public static final TreeGrower SPRUCE = new TreeGrower(
        "spruce",
        0.5F,
        Optional.of(TreeFeatures.MEGA_SPRUCE),
        Optional.of(TreeFeatures.MEGA_PINE),
        Optional.of(TreeFeatures.SPRUCE),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );
    public static final TreeGrower MANGROVE = new TreeGrower(
        "mangrove",
        0.85F,
        Optional.empty(),
        Optional.empty(),
        Optional.of(TreeFeatures.MANGROVE),
        Optional.of(TreeFeatures.TALL_MANGROVE),
        Optional.empty(),
        Optional.empty()
    );
    public static final TreeGrower AZALEA = new TreeGrower("azalea", Optional.empty(), Optional.of(TreeFeatures.AZALEA_TREE), Optional.empty());
    public static final TreeGrower BIRCH = new TreeGrower("birch", Optional.empty(), Optional.of(TreeFeatures.BIRCH), Optional.of(TreeFeatures.BIRCH_BEES_005));
    public static final TreeGrower JUNGLE = new TreeGrower(
        "jungle", Optional.of(TreeFeatures.MEGA_JUNGLE_TREE), Optional.of(TreeFeatures.JUNGLE_TREE_NO_VINE), Optional.empty()
    );
    public static final TreeGrower ACACIA = new TreeGrower("acacia", Optional.empty(), Optional.of(TreeFeatures.ACACIA), Optional.empty());
    public static final TreeGrower CHERRY = new TreeGrower(
        "cherry", Optional.empty(), Optional.of(TreeFeatures.CHERRY), Optional.of(TreeFeatures.CHERRY_BEES_005)
    );
    public static final TreeGrower DARK_OAK = new TreeGrower("dark_oak", Optional.of(TreeFeatures.DARK_OAK), Optional.empty(), Optional.empty());
    public static final TreeGrower PALE_OAK = new TreeGrower("pale_oak", Optional.of(TreeFeatures.PALE_OAK_BONEMEAL), Optional.empty(), Optional.empty());
    private final String name;
    private final float secondaryChance;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> megaTree;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> secondaryMegaTree;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> tree;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> secondaryTree;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> flowers;
    private final Optional<ResourceKey<ConfiguredFeature<?, ?>>> secondaryFlowers;

    public TreeGrower(
        final String name,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> megaTree,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> tree,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> flowers
    ) {
        this(name, 0.0F, megaTree, Optional.empty(), tree, Optional.empty(), flowers, Optional.empty());
    }

    public TreeGrower(
        final String name,
        final float secondaryChance,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> megaTree,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> secondaryMegaTree,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> tree,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> secondaryTree,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> flowers,
        final Optional<ResourceKey<ConfiguredFeature<?, ?>>> secondaryFlowers
    ) {
        this.name = name;
        this.secondaryChance = secondaryChance;
        this.megaTree = megaTree;
        this.secondaryMegaTree = secondaryMegaTree;
        this.tree = tree;
        this.secondaryTree = secondaryTree;
        this.flowers = flowers;
        this.secondaryFlowers = secondaryFlowers;
        GROWERS.put(name, this);
    }

    private @Nullable ResourceKey<ConfiguredFeature<?, ?>> getConfiguredFeature(final RandomSource random, final boolean hasFlowers) {
        if (random.nextFloat() < this.secondaryChance) {
            if (hasFlowers && this.secondaryFlowers.isPresent()) {
                return this.secondaryFlowers.get();
            }

            if (this.secondaryTree.isPresent()) {
                return this.secondaryTree.get();
            }
        }

        return hasFlowers && this.flowers.isPresent() ? this.flowers.get() : this.tree.orElse(null);
    }

    private @Nullable ResourceKey<ConfiguredFeature<?, ?>> getConfiguredMegaFeature(final RandomSource random) {
        return this.secondaryMegaTree.isPresent() && random.nextFloat() < this.secondaryChance ? this.secondaryMegaTree.get() : this.megaTree.orElse(null);
    }

    public boolean growTree(final ServerLevel level, final ChunkGenerator generator, final BlockPos pos, final BlockState state, final RandomSource random) {
        ResourceKey<ConfiguredFeature<?, ?>> megaFeatureKey = this.getConfiguredMegaFeature(random);
        if (megaFeatureKey != null) {
            Holder<ConfiguredFeature<?, ?>> featureHolder = level.registryAccess()
                .lookupOrThrow(Registries.CONFIGURED_FEATURE)
                .get(megaFeatureKey)
                .orElse(null);
            if (featureHolder != null) {
                this.setTreeType(featureHolder); // CraftBukkit
                for (int dx = 0; dx >= -1; dx--) {
                    for (int dz = 0; dz >= -1; dz--) {
                        if (isTwoByTwoSapling(state, level, pos, dx, dz)) {
                            ConfiguredFeature<?, ?> feature = featureHolder.value();
                            BlockState air = Blocks.AIR.defaultBlockState();
                            level.setBlock(pos.offset(dx, 0, dz), air, Block.UPDATE_NONE);
                            level.setBlock(pos.offset(dx + 1, 0, dz), air, Block.UPDATE_NONE);
                            level.setBlock(pos.offset(dx, 0, dz + 1), air, Block.UPDATE_NONE);
                            level.setBlock(pos.offset(dx + 1, 0, dz + 1), air, Block.UPDATE_NONE);
                            if (feature.place(level, generator, random, pos.offset(dx, 0, dz))) {
                                return true;
                            }

                            level.setBlock(pos.offset(dx, 0, dz), state, Block.UPDATE_NONE);
                            level.setBlock(pos.offset(dx + 1, 0, dz), state, Block.UPDATE_NONE);
                            level.setBlock(pos.offset(dx, 0, dz + 1), state, Block.UPDATE_NONE);
                            level.setBlock(pos.offset(dx + 1, 0, dz + 1), state, Block.UPDATE_NONE);
                            return false;
                        }
                    }
                }
            }
        }

        ResourceKey<ConfiguredFeature<?, ?>> featureKey = this.getConfiguredFeature(random, this.hasFlowers(level, pos));
        if (featureKey == null) {
            return false;
        }

        Holder<ConfiguredFeature<?, ?>> featureHolder = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE).get(featureKey).orElse(null);
        if (featureHolder == null) {
            return false;
        }

        this.setTreeType(featureHolder); // CraftBukkit
        ConfiguredFeature<?, ?> feature = featureHolder.value();
        BlockState emptyBlock = level.getFluidState(pos).createLegacyBlock();
        level.setBlock(pos, emptyBlock, Block.UPDATE_NONE);
        if (feature.place(level, generator, random, pos)) {
            if (level.getBlockState(pos) == emptyBlock) {
                level.sendBlockUpdated(pos, state, emptyBlock, Block.UPDATE_CLIENTS);
            }

            return true;
        } else {
            level.setBlock(pos, state, Block.UPDATE_NONE);
            return false;
        }
    }

    private static boolean isTwoByTwoSapling(final BlockState state, final BlockGetter level, final BlockPos pos, final int ox, final int oz) {
        Block block = state.getBlock();
        return level.getBlockState(pos.offset(ox, 0, oz)).is(block)
            && level.getBlockState(pos.offset(ox + 1, 0, oz)).is(block)
            && level.getBlockState(pos.offset(ox, 0, oz + 1)).is(block)
            && level.getBlockState(pos.offset(ox + 1, 0, oz + 1)).is(block);
    }

    private boolean hasFlowers(final LevelAccessor level, final BlockPos pos) {
        for (BlockPos p : BlockPos.MutableBlockPos.betweenClosed(pos.below().north(2).west(2), pos.above().south(2).east(2))) {
            if (level.getBlockState(p).is(BlockTags.FLOWERS)) {
                return true;
            }
        }

        return false;
    }

    public OptionalInt getMinimumHeight(final ServerLevel level) {
        ResourceKey<ConfiguredFeature<?, ?>> featureKey = this.tree.orElse(null);
        if (featureKey == null) {
            return OptionalInt.empty();
        }

        Holder<ConfiguredFeature<?, ?>> featureHolder = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE).get(featureKey).orElse(null);
        return featureHolder != null && featureHolder.value().config() instanceof TreeConfiguration treeConfig
            ? OptionalInt.of(treeConfig.trunkPlacer.getBaseHeight())
            : OptionalInt.empty();
    }

    // CraftBukkit start
    private void setTreeType(Holder<ConfiguredFeature<?, ?>> feature) {
        org.bukkit.TreeType treeType; // Folia - region threading
        if (feature.is(TreeFeatures.OAK) || feature.is(TreeFeatures.OAK_BEES_005)) {
            treeType = org.bukkit.TreeType.TREE; // Folia - region threading
        } else if (feature.is(TreeFeatures.HUGE_RED_MUSHROOM)) {
            treeType = org.bukkit.TreeType.RED_MUSHROOM; // Folia - region threading
        } else if (feature.is(TreeFeatures.HUGE_BROWN_MUSHROOM)) {
            treeType = org.bukkit.TreeType.BROWN_MUSHROOM; // Folia - region threading
        } else if (feature.is(TreeFeatures.JUNGLE_TREE)) {
            treeType = org.bukkit.TreeType.COCOA_TREE; // Folia - region threading
        } else if (feature.is(TreeFeatures.JUNGLE_TREE_NO_VINE)) {
            treeType = org.bukkit.TreeType.SMALL_JUNGLE; // Folia - region threading
        } else if (feature.is(TreeFeatures.PINE)) {
            treeType = org.bukkit.TreeType.TALL_REDWOOD; // Folia - region threading
        } else if (feature.is(TreeFeatures.SPRUCE)) {
            treeType = org.bukkit.TreeType.REDWOOD; // Folia - region threading
        } else if (feature.is(TreeFeatures.ACACIA)) {
            treeType = org.bukkit.TreeType.ACACIA; // Folia - region threading
        } else if (feature.is(TreeFeatures.BIRCH) || feature.is(TreeFeatures.BIRCH_BEES_005)) {
            treeType = org.bukkit.TreeType.BIRCH; // Folia - region threading
        } else if (feature.is(TreeFeatures.SUPER_BIRCH_BEES_0002)) {
            treeType = org.bukkit.TreeType.TALL_BIRCH; // Folia - region threading
        } else if (feature.is(TreeFeatures.SWAMP_OAK)) {
            treeType = org.bukkit.TreeType.SWAMP; // Folia - region threading
        } else if (feature.is(TreeFeatures.FANCY_OAK) || feature.is(TreeFeatures.FANCY_OAK_BEES_005)) {
            treeType = org.bukkit.TreeType.BIG_TREE; // Folia - region threading
        } else if (feature.is(TreeFeatures.JUNGLE_BUSH)) {
            treeType = org.bukkit.TreeType.JUNGLE_BUSH; // Folia - region threading
        } else if (feature.is(TreeFeatures.DARK_OAK)) {
            treeType = org.bukkit.TreeType.DARK_OAK; // Folia - region threading
        } else if (feature.is(TreeFeatures.MEGA_SPRUCE)) {
            treeType = org.bukkit.TreeType.MEGA_REDWOOD; // Folia - region threading
        } else if (feature.is(TreeFeatures.MEGA_PINE)) {
            treeType = org.bukkit.TreeType.MEGA_PINE; // Folia - region threading
        } else if (feature.is(TreeFeatures.MEGA_JUNGLE_TREE)) {
            treeType = org.bukkit.TreeType.JUNGLE; // Folia - region threading
        } else if (feature.is(TreeFeatures.AZALEA_TREE)) {
            treeType = org.bukkit.TreeType.AZALEA; // Folia - region threading
        } else if (feature.is(TreeFeatures.MANGROVE)) {
            treeType = org.bukkit.TreeType.MANGROVE; // Folia - region threading
        } else if (feature.is(TreeFeatures.TALL_MANGROVE)) {
            treeType = org.bukkit.TreeType.TALL_MANGROVE; // Folia - region threading
        } else if (feature.is(TreeFeatures.CHERRY) || feature.is(TreeFeatures.CHERRY_BEES_005)) {
            treeType = org.bukkit.TreeType.CHERRY; // Folia - region threading
        } else if (feature.is(TreeFeatures.PALE_OAK) || feature.is(TreeFeatures.PALE_OAK_BONEMEAL)) {
            treeType = org.bukkit.TreeType.PALE_OAK; // Folia - region threading
        } else if (feature.is(TreeFeatures.PALE_OAK_CREAKING)) {
            treeType = org.bukkit.TreeType.PALE_OAK_CREAKING; // Folia - region threading
        } else {
            throw new IllegalArgumentException("Unknown tree generator " + feature);
        }
        net.minecraft.world.level.block.SaplingBlock.treeTypeRT.set(treeType); // Folia - region threading
    }
    // CraftBukkit end
}
