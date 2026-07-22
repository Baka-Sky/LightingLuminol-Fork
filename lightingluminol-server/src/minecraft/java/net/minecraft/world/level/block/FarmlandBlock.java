package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class FarmlandBlock extends Block {
    public static final MapCodec<FarmlandBlock> CODEC = simpleCodec(FarmlandBlock::new);
    public static final IntegerProperty MOISTURE = BlockStateProperties.MOISTURE;
    private static final VoxelShape SHAPE = Block.column(16.0, 0.0, 15.0);
    public static final int MAX_MOISTURE = 7;

    @Override
    public MapCodec<FarmlandBlock> codec() {
        return CODEC;
    }

    protected FarmlandBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(MOISTURE, 0));
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
        if (directionToNeighbour == Direction.UP && !state.canSurvive(level, pos)) {
            ticks.scheduleTick(pos, this, 1);
        }

        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
        BlockState aboveState = level.getBlockState(pos.above());
        return !aboveState.isSolid() || shouldMaintainFarmland(level, pos);
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        return !this.defaultBlockState().canSurvive(context.getLevel(), context.getClickedPos())
            ? Blocks.DIRT.defaultBlockState()
            : super.getStateForPlacement(context);
    }

    @Override
    protected boolean useShapeForLightOcclusion(final BlockState state) {
        return true;
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        if (!state.canSurvive(level, pos)) {
            turnToDirt(null, state, level, pos);
        }
    }

    @Override
    protected void randomTick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        int moisture = state.getValue(MOISTURE);
        if (moisture > 0 && level.paperConfig().tickRates.wetFarmland != 1 && (level.paperConfig().tickRates.wetFarmland < 1 || (level.getRedstoneGameTime() + pos.hashCode()) % level.paperConfig().tickRates.wetFarmland != 0)) { return; } // Paper - Configurable random tick rates for blocks // Folia - region threading
        if (moisture == 0 && level.paperConfig().tickRates.dryFarmland != 1 && (level.paperConfig().tickRates.dryFarmland < 1 || (level.getRedstoneGameTime() + pos.hashCode()) % level.paperConfig().tickRates.dryFarmland != 0)) { return; } // Paper - Configurable random tick rates for blocks // Folia - region threading
        if (!isNearWater(level, pos) && !level.isRainingAt(pos.above())) {
            if (moisture > 0) {
                org.bukkit.craftbukkit.event.CraftEventFactory.handleMoistureChangeEvent(level, pos, state.setValue(MOISTURE, moisture - 1), Block.UPDATE_CLIENTS); // CraftBukkit
            } else if (!shouldMaintainFarmland(level, pos)) {
                turnToDirt(null, state, level, pos);
            }
        } else if (moisture < 7) {
            org.bukkit.craftbukkit.event.CraftEventFactory.handleMoistureChangeEvent(level, pos, state.setValue(MOISTURE, 7), Block.UPDATE_CLIENTS); // CraftBukkit
        }
    }

    @Override
    public void fallOn(final Level level, final BlockState state, final BlockPos pos, final Entity entity, final double fallDistance) {
        super.fallOn(level, state, pos, entity, fallDistance); // CraftBukkit - moved here as game rules / events shouldn't affect fall damage.
        if (level instanceof ServerLevel serverLevel
            && level.getRandom().nextFloat() < fallDistance - 0.5
            && entity instanceof LivingEntity
            && (entity instanceof Player || serverLevel.getGameRules().get(GameRules.MOB_GRIEFING))
            && entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight() > 0.512F) {
            // CraftBukkit start - Interact soil
            org.bukkit.event.Cancellable cancellable;
            if (entity instanceof Player) {
                cancellable = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerInteractEvent((Player) entity, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null);
            } else {
                cancellable = new org.bukkit.event.entity.EntityInteractEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos));
                level.getCraftServer().getPluginManager().callEvent((org.bukkit.event.entity.EntityInteractEvent) cancellable);
            }

            if (cancellable.isCancelled()) {
                return;
            }

            if (!org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(entity, pos, Blocks.DIRT.defaultBlockState())) {
                return;
            }
            // CraftBukkit end
            turnToDirt(entity, state, level, pos);
        }

        // super.fallOn(level, state, pos, entity, fallDistance); // CraftBukkit - moved up
    }

    public static void turnToDirt(final @Nullable Entity sourceEntity, final BlockState state, final Level level, final BlockPos pos) {
        // CraftBukkit start
        if (sourceEntity == null) {
            if (org.bukkit.craftbukkit.event.CraftEventFactory
                .callBlockFadeEvent(level, pos, Blocks.DIRT.defaultBlockState()).isCancelled()) {
                return;
            }
        }
        // CraftBukkit end
        BlockState newState = pushEntitiesUp(state, Blocks.DIRT.defaultBlockState(), level, pos);
        level.setBlockAndUpdate(pos, newState);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(sourceEntity, newState));
    }

    private static boolean shouldMaintainFarmland(final BlockGetter level, final BlockPos pos) {
        return level.getBlockState(pos.above()).is(BlockTags.MAINTAINS_FARMLAND);
    }

    private static boolean isNearWater(final LevelReader level, final BlockPos pos) {
        // Paper start - Perf: remove abstract block iteration
        int xOff = pos.getX();
        int yOff = pos.getY();
        int zOff = pos.getZ();
        for (int dz = -4; dz <= 4; ++dz) {
            int z = dz + zOff;
            for (int dx = -4; dx <= 4; ++dx) {
                int x = xOff + dx;
                for (int dy = 0; dy <= 1; ++dy) {
                    int y = dy + yOff;
                    net.minecraft.world.level.chunk.LevelChunk chunk = (net.minecraft.world.level.chunk.LevelChunk)level.getChunk(x >> 4, z >> 4);
                    net.minecraft.world.level.material.FluidState fluid = chunk.getBlockStateFinal(x, y, z).getFluidState();
                    if (fluid.is(FluidTags.WATER)) {
                        return true;
                    }
                }
            }
        }

        return false;
        // Paper end - Perf: remove abstract block iteration
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(MOISTURE);
    }

    @Override
    protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
        return false;
    }
}
