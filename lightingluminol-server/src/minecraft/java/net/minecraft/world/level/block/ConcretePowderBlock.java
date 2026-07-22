package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public class ConcretePowderBlock extends FallingBlock {
    public static final MapCodec<ConcretePowderBlock> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(BuiltInRegistries.BLOCK.byNameCodec().fieldOf("concrete").forGetter(b -> b.concrete), propertiesCodec())
            .apply(i, ConcretePowderBlock::new)
    );
    private final Block concrete;

    @Override
    public MapCodec<ConcretePowderBlock> codec() {
        return CODEC;
    }

    public ConcretePowderBlock(final Block concrete, final BlockBehaviour.Properties properties) {
        super(properties);
        this.concrete = concrete;
    }

    @Override
    public void onLand(final Level level, final BlockPos pos, final BlockState state, final BlockState replacedBlock, final FallingBlockEntity entity) {
        if (shouldSolidify(level, pos, replacedBlock)) {
            org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(level, pos, this.concrete.defaultBlockState(), Block.UPDATE_ALL); // CraftBukkit
        }
    }

    @Override
    public BlockState getStateForPlacement(final BlockPlaceContext context) {
        Level level = context.getLevel(); // Paper
        BlockPos pos = context.getClickedPos();
        BlockState replacedBlock = level.getBlockState(pos);
        // CraftBukkit start
        if (!ConcretePowderBlock.shouldSolidify(level, pos, replacedBlock)) {
            return super.getStateForPlacement(context);
        }

        // TODO: An event factory call for methods like this
        org.bukkit.craftbukkit.block.CraftBlockState snapshot = org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(level, pos);
        snapshot.setBlock(this.concrete.defaultBlockState());

        org.bukkit.event.block.BlockFormEvent event = new org.bukkit.event.block.BlockFormEvent(snapshot.getBlock(), snapshot);
        event.callEvent();

        if (!event.isCancelled()) {
            return snapshot.getHandle();
        }

        return super.getStateForPlacement(context);
        // CraftBukkit end
    }

    private static boolean shouldSolidify(final BlockGetter level, final BlockPos pos, final BlockState replacedBlock) {
        return canSolidify(replacedBlock) || touchesLiquid(level, pos);
    }

    private static boolean touchesLiquid(final BlockGetter level, final BlockPos pos) {
        boolean touchesLiquid = false;
        BlockPos.MutableBlockPos testPos = pos.mutable();

        for (Direction direction : Direction.values()) {
            BlockState blockState = level.getBlockState(testPos);
            if (direction != Direction.DOWN || canSolidify(blockState)) {
                testPos.setWithOffset(pos, direction);
                blockState = level.getBlockState(testPos);
                if (canSolidify(blockState) && !blockState.isFaceSturdy(level, pos, direction.getOpposite())) {
                    touchesLiquid = true;
                    break;
                }
            }
        }

        return touchesLiquid;
    }

    private static boolean canSolidify(final BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
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
        // CraftBukkit start
        if (ConcretePowderBlock.touchesLiquid(level, pos)) {
            // Suppress during worldgen
            if (!(level instanceof Level world1)) {
                return this.concrete.defaultBlockState();
            }
            org.bukkit.craftbukkit.block.CraftBlockState snapshot = org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(world1, pos);
            snapshot.setBlock(this.concrete.defaultBlockState());

            org.bukkit.event.block.BlockFormEvent event = new org.bukkit.event.block.BlockFormEvent(snapshot.getBlock(), snapshot);
            world1.getCraftServer().getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                return snapshot.getHandle();
            }
        }

        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
        // CraftBukkit end
    }

    @Override
    public int getDustColor(final BlockState blockState, final BlockGetter level, final BlockPos pos) {
        return blockState.getMapColor(level, pos).col;
    }
}
