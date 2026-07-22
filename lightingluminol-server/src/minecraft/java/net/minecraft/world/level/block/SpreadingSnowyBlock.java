package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;

public abstract class SpreadingSnowyBlock extends SnowyBlock {
    private final ResourceKey<Block> baseBlock;

    protected SpreadingSnowyBlock(final BlockBehaviour.Properties properties, final ResourceKey<Block> baseBlock) {
        super(properties);
        this.baseBlock = baseBlock;
    }

    @Override
    protected abstract MapCodec<? extends SpreadingSnowyBlock> codec();

    private static boolean canStayAlive(final BlockState state, final LevelReader level, final BlockPos pos) {
        // Paper start - Perf: optimize dirt and snow spreading
        return canStayAlive(level.getChunk(pos), state, pos);
    }
    private static boolean canStayAlive(final net.minecraft.world.level.chunk.ChunkAccess chunk, final BlockState state, final BlockPos pos) {
        // Paper end - Perf: optimize dirt and snow spreading
        BlockPos above = pos.above();
        BlockState aboveState = chunk.getBlockState(above); // Paper - Perf: optimize dirt and snow spreading
        if (aboveState.is(Blocks.SNOW) && aboveState.getValue(SnowLayerBlock.LAYERS) == 1) {
            return true;
        }

        if (aboveState.getFluidState().isFull()) {
            return false;
        }

        int lightDampeningTopFace = LightEngine.getLightDampeningInto(state, aboveState, Direction.UP, aboveState.getLightDampening());
        return lightDampeningTopFace < 15;
    }

    private static boolean canPropagate(final BlockState state, final LevelReader level, final BlockPos pos) {
        // Paper start - Perf: optimize dirt and snow spreading
        return canPropagate(level.getChunk(pos), state, pos);
    }

    private static boolean canPropagate(final net.minecraft.world.level.chunk.ChunkAccess chunk, final BlockState state, final BlockPos pos) {
        // Paper end - Perf: optimize dirt and snow spreading
        BlockPos above = pos.above();
        return canStayAlive(chunk, state, pos) && !chunk.getFluidState(above).is(FluidTags.WATER); // Paper - Perf: optimize dirt and snow spreading
    }

    @Override
    protected void randomTick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        if (this instanceof GrassBlock && level.paperConfig().tickRates.grassSpread != 1 && (level.paperConfig().tickRates.grassSpread < 1 || (io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() + pos.hashCode()) % level.paperConfig().tickRates.grassSpread != 0)) { return; } // Paper - Configurable random tick rates for blocks // Folia - regionised ticking
        Registry<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        Optional<Block> baseBlock = blocks.getOptional(this.baseBlock);
        if (!baseBlock.isEmpty()) {
            // Paper start - Perf: optimize dirt and snow spreading
            final net.minecraft.world.level.chunk.ChunkAccess cachedChunk = level.getChunkIfLoaded(pos);
            if (cachedChunk == null) { // Is this needed?
                return;
            }

            if (!canStayAlive(cachedChunk, state, pos)) {
                // Paper end - Perf: optimize dirt and snow spreading
                // CraftBukkit start
                if (org.bukkit.craftbukkit.event.CraftEventFactory.callBlockFadeEvent(level, pos, baseBlock.get().defaultBlockState()).isCancelled()) {
                    return;
                }
                // CraftBukkit end
                level.setBlockAndUpdate(pos, baseBlock.get().defaultBlockState());
            } else {
                if (level.getMaxLocalRawBrightness(pos.above()) >= 9) {
                    BlockState defaultBlockState = this.defaultBlockState();

                    for (int i = 0; i < 4; i++) {
                        BlockPos testPos = pos.offset(random.nextInt(3) - 1, random.nextInt(5) - 3, random.nextInt(3) - 1);
                        // Paper start - Perf: optimize dirt and snow spreading
                        if (pos.getX() == testPos.getX() && pos.getY() == testPos.getY() && pos.getZ() == testPos.getZ()) {
                            continue;
                        }

                        final net.minecraft.world.level.chunk.ChunkAccess access;
                        if (cachedChunk.locX == testPos.getX() >> 4 && cachedChunk.locZ == testPos.getZ() >> 4) {
                            access = cachedChunk;
                        } else {
                            access = level.getChunkAt(testPos);
                        }
                        if (access.getBlockState(testPos).is(baseBlock.get()) && SpreadingSnowyBlock.canPropagate(access, defaultBlockState, testPos)) {
                            org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockSpreadEvent(level, pos, testPos, defaultBlockState.setValue(SNOWY, isSnowySetting(access.getBlockState(testPos.above()))), Block.UPDATE_ALL); // CraftBukkit
                            // Paper end - Perf: optimize dirt and snow spreading
                        }
                    }
                }
            }
        }
    }
}
