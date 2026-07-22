package net.minecraft.world.effect;

import com.google.common.collect.Sets;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.gamerules.GameRules;

class WeavingMobEffect extends MobEffect {
    private final ToIntFunction<RandomSource> maxCobwebs;

    protected WeavingMobEffect(final MobEffectCategory category, final int color, final ToIntFunction<RandomSource> maxCobwebs) {
        super(category, color, ParticleTypes.ITEM_COBWEB);
        this.maxCobwebs = maxCobwebs;
    }

    @Override
    public void onMobRemoved(final ServerLevel level, final LivingEntity mob, final int amplifier, final Entity.RemovalReason reason) {
        if (reason == Entity.RemovalReason.KILLED && (mob instanceof Player || level.getGameRules().get(GameRules.MOB_GRIEFING))) {
            this.spawnCobwebsRandomlyAround(level, mob.getRandom(), mob.blockPosition(), mob); // Paper - Fire EntityChangeBlockEvent in more places
        }
    }

    private void spawnCobwebsRandomlyAround(final ServerLevel level, final RandomSource random, final BlockPos pos, LivingEntity entity) { // Paper - Fire EntityChangeBlockEvent in more places
        Set<BlockPos> positionsToTransform = Sets.newHashSet();
        int cobwebCount = this.maxCobwebs.applyAsInt(random);

        for (BlockPos blockPos : BlockPos.randomInCube(random, 15, pos, 1)) {
            BlockPos below = blockPos.below();
            if (!positionsToTransform.contains(blockPos)
                && level.getBlockState(blockPos).canBeReplaced()
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                positionsToTransform.add(blockPos.immutable());
                if (positionsToTransform.size() >= cobwebCount) {
                    break;
                }
            }
        }

        for (BlockPos blockPos : positionsToTransform) {
            if (!org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(entity, blockPos, Blocks.COBWEB.defaultBlockState())) continue; // Paper - Fire EntityChangeBlockEvent in more places
            level.setBlock(blockPos, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
            level.levelEvent(LevelEvent.ANIMATION_SPAWN_COBWEB, blockPos, 0);
        }
    }
}
