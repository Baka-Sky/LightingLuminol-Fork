package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SugarCaneBlock extends Block {
    public static final MapCodec<SugarCaneBlock> CODEC = simpleCodec(SugarCaneBlock::new);
    public static final IntegerProperty AGE = BlockStateProperties.AGE_15;
    private static final VoxelShape SHAPE = Block.column(12.0, 0.0, 16.0);

    @Override
    public MapCodec<SugarCaneBlock> codec() {
        return CODEC;
    }

    protected SugarCaneBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(AGE, 0));
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    protected void randomTick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        if (level.isEmptyBlock(pos.above())) {
            int height = 1;

            while (level.getBlockState(pos.below(height)).is(this)) {
                height++;
            }

            if (height < level.paperConfig().maxGrowthHeight.reeds) { // Paper - Configurable cactus/bamboo/reed growth height
                int age = state.getValue(AGE);
                int modifier = level.spigotConfig.caneModifier; // Spigot - SPIGOT-7159: Better modifier resolution
                if (age >= 15 || (modifier != 100 && random.nextFloat() < (modifier / (100.0F * 16)))) { // Spigot - SPIGOT-7159: Better modifier resolution
                    org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockGrowEvent(level, pos.above(), this.defaultBlockState(), Block.UPDATE_ALL); // CraftBukkit
                    level.setBlock(pos, state.setValue(AGE, 0), Block.UPDATE_NONE);
                } else if (modifier == 100 || random.nextFloat() < (modifier / (100.0F * 16))) { // Spigot - SPIGOT-7159: Better modifier resolution
                    level.setBlock(pos, state.setValue(AGE, age + 1), Block.UPDATE_NONE);
                }
            }
        }
    }

    @Override
    protected BlockState updateShape(
        final BlockState state,
        final LevelReader level,
        final ScheduledTickAccess ticks,
        final BlockPos pos,
        final Direction directionToNeighbour,
        final BlockPos neighbourPos,
        final BlockState neighbourState,
        final RandomSource random
    ) {
        if (!state.canSurvive(level, pos)) {
            ticks.scheduleTick(pos, this, 1);
        }

        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        BlockState stateBelow = level.getBlockState(pos.below());
        if (stateBelow.is(this)) {
            return true;
        }

        if (stateBelow.is(BlockTags.SUPPORTS_SUGAR_CANE)) {
            BlockPos below = pos.below();

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockState blockState = level.getBlockState(below.relative(direction));
                FluidState fluidState = level.getFluidState(below.relative(direction));
                if (fluidState.is(FluidTags.SUPPORTS_SUGAR_CANE_ADJACENTLY) || blockState.is(BlockTags.SUPPORTS_SUGAR_CANE_ADJACENTLY)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE);
    }
}
