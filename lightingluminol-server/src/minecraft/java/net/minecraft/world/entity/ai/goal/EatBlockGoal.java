package net.minecraft.world.entity.ai.goal;

import java.util.EnumSet;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;

public class EatBlockGoal extends Goal {
    private static final int EAT_ANIMATION_TICKS = 40;
    private static final Predicate<BlockState> IS_EDIBLE = state -> state.is(BlockTags.EDIBLE_FOR_SHEEP);
    private final Mob mob;
    private final Level level;
    private int eatAnimationTick;

    public EatBlockGoal(final Mob mob) {
        this.mob = mob;
        this.level = mob.level();
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (this.mob.getRandom().nextInt(this.adjustedTickDelay(this.mob.isBaby() ? 50 : 1000)) != 0) {
            return false;
        }

        BlockPos pos = this.mob.blockPosition();
        return IS_EDIBLE.test(this.level.getBlockState(pos)) || this.level.getBlockState(pos.below()).is(Blocks.GRASS_BLOCK);
    }

    @Override
    public void start() {
        this.eatAnimationTick = this.adjustedTickDelay(40);
        this.level.broadcastEntityEvent(this.mob, EntityEvent.EAT_GRASS);
        this.mob.getNavigation().stop();
    }

    @Override
    public void stop() {
        this.eatAnimationTick = 0;
    }

    @Override
    public boolean canContinueToUse() {
        return this.eatAnimationTick > 0;
    }

    public int getEatAnimationTick() {
        return this.eatAnimationTick;
    }

    @Override
    public void tick() {
        this.eatAnimationTick = Math.max(0, this.eatAnimationTick - 1);
        if (this.eatAnimationTick == this.adjustedTickDelay(4)) {
            BlockPos pos = this.mob.blockPosition();
            final BlockState blockState = this.level.getBlockState(pos); // Paper - fix wrong block state
            if (IS_EDIBLE.test(blockState)) { // Paper - fix wrong block state
                if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this.mob, pos, blockState.getFluidState().createLegacyBlock(), !getServerLevel(this.level).getGameRules().get(GameRules.MOB_GRIEFING))) { // CraftBukkit // Paper - fix wrong block state
                    this.level.destroyBlock(pos, false);
                }

                this.mob.ate();
            } else {
                BlockPos below = pos.below();
                if (this.level.getBlockState(below).is(Blocks.GRASS_BLOCK)) {
                    if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(this.mob, below, Blocks.DIRT.defaultBlockState(), !getServerLevel(this.level).getGameRules().get(GameRules.MOB_GRIEFING))) { // CraftBukkit // Paper - Fix wrong block state
                        this.level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, below, Block.getId(Blocks.GRASS_BLOCK.defaultBlockState()));
                        this.level.setBlock(below, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }

                    this.mob.ate();
                }
            }
        }
    }
}
