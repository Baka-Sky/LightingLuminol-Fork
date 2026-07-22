package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SaplingBlock extends VegetationBlock implements BonemealableBlock {
    public static final MapCodec<SaplingBlock> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(TreeGrower.CODEC.fieldOf("tree").forGetter(b -> b.treeGrower), propertiesCodec()).apply(i, SaplingBlock::new)
    );
    public static final IntegerProperty STAGE = BlockStateProperties.STAGE;
    private static final VoxelShape SHAPE = Block.column(12.0, 0.0, 12.0);
    protected final TreeGrower treeGrower;
    public static final ThreadLocal<org.bukkit.TreeType> treeTypeRT = new ThreadLocal<>(); // CraftBukkit // Folia - region threading

    @Override
    public MapCodec<? extends SaplingBlock> codec() {
        return CODEC;
    }

    protected SaplingBlock(final TreeGrower treeGrower, final BlockBehaviour.Properties properties) {
        super(properties);
        this.treeGrower = treeGrower;
        this.registerDefaultState(this.stateDefinition.any().setValue(STAGE, 0));
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void randomTick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        if (level.getMaxLocalRawBrightness(pos.above()) >= 9 && random.nextFloat() < (level.spigotConfig.saplingModifier / (100.0F * 7))) { // Spigot - SPIGOT-7159: Better modifier resolution
            this.advanceTree(level, pos, state, random);
        }
    }

    public void advanceTree(final ServerLevel level, final BlockPos pos, final BlockState state, final RandomSource random) {
        if (state.getValue(STAGE) == 0) {
            level.setBlock(pos, state.cycle(STAGE), Block.UPDATE_NONE);
        } else {
            // CraftBukkit start
            io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
            if (worldData.captureTreeGeneration) { // Folia - region threading
                this.treeGrower.growTree(level, level.getChunkSource().getGenerator(), pos, state, random);
            } else {
                worldData.captureTreeGeneration = true; // Folia - region threading
                java.util.List<org.bukkit.craftbukkit.block.CraftBlockState> capturedBlockStates;
                org.bukkit.TreeType treeType;
                try {
                    this.treeGrower.growTree(level, level.getChunkSource().getGenerator(), pos, state, random);
                } finally {
                    worldData.captureTreeGeneration = false; // Folia - region threading

                    capturedBlockStates = new java.util.ArrayList<>(worldData.capturedBlockStates.values()); // Folia - region threading
                    worldData.capturedBlockStates.clear(); // Folia - region threading

                    treeType = SaplingBlock.treeTypeRT.get(); // Folia - region threading
                    SaplingBlock.treeTypeRT.set(null); // Folia - region threading
                }
                if (!capturedBlockStates.isEmpty()) {
                    org.bukkit.Location location = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level);
                    org.bukkit.event.world.StructureGrowEvent event = null;
                    if (treeType != null) {
                        event = new org.bukkit.event.world.StructureGrowEvent(location, treeType, false, null, (java.util.List<org.bukkit.block.BlockState>) (java.util.List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
                        org.bukkit.Bukkit.getPluginManager().callEvent(event);
                    }
                    if (event == null || !event.isCancelled()) {
                        for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                            snapshot.place(snapshot.getFlags());
                            level.checkCapturedTreeStateForObserverNotify(pos, snapshot); // Paper - notify observers even if grow failed
                        }
                    }
                }
            }
            // CraftBukkit end
        }
    }

    @Override
    public boolean isValidBonemealTarget(final LevelReader level, final BlockPos pos, final BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            int heightOffset = this.treeGrower.getMinimumHeight(serverLevel).orElse(0);
            return level.isInsideBuildHeight(pos.above(heightOffset));
        } else {
            return false;
        }
    }

    @Override
    public boolean isBonemealSuccess(final Level level, final RandomSource random, final BlockPos pos, final BlockState state) {
        return level.getRandom().nextFloat() < 0.45;
    }

    @Override
    public void performBonemeal(final ServerLevel level, final RandomSource random, final BlockPos pos, final BlockState state) {
        this.advanceTree(level, pos, state, random);
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(STAGE);
    }
}
