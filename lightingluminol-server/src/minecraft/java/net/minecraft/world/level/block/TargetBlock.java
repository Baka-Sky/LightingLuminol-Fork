package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class TargetBlock extends Block {
    public static final MapCodec<TargetBlock> CODEC = simpleCodec(TargetBlock::new);
    private static final IntegerProperty OUTPUT_POWER = BlockStateProperties.POWER;
    private static final int ACTIVATION_TICKS_ARROWS = 20;
    private static final int ACTIVATION_TICKS_OTHER = 8;

    @Override
    public MapCodec<TargetBlock> codec() {
        return CODEC;
    }

    public TargetBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(OUTPUT_POWER, 0));
    }

    @Override
    protected void onProjectileHit(final Level level, final BlockState state, final BlockHitResult hitResult, final Projectile projectile) {
        int outputStrength = updateRedstoneOutput(level, state, hitResult, projectile);
        // Paper start - Add TargetHitEvent
    }
    private static void awardTargetHitCriteria(Projectile projectile, BlockHitResult hitResult, int outputStrength) {
        // Paper end - Add TargetHitEvent
        if (projectile.getOwner() instanceof ServerPlayer playerOwner) {
            playerOwner.awardStat(Stats.TARGET_HIT);
            CriteriaTriggers.TARGET_BLOCK_HIT.trigger(playerOwner, projectile, hitResult.getLocation(), outputStrength);
        }
    }

    private static int updateRedstoneOutput(final LevelAccessor level, final BlockState state, final BlockHitResult hitResult, final Entity entity) {
        int redstoneStrength = getRedstoneStrength(hitResult, hitResult.getLocation());
        int duration = entity instanceof AbstractArrow ? 20 : 8;
        // Paper start - Add TargetHitEvent
        boolean shouldAward = false;
        if (entity instanceof Projectile) {
            final org.bukkit.craftbukkit.block.CraftBlock craftBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, hitResult.getBlockPos());
            final org.bukkit.block.BlockFace blockFace = org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(hitResult.getDirection());
            final io.papermc.paper.event.block.TargetHitEvent targetHitEvent = new io.papermc.paper.event.block.TargetHitEvent((org.bukkit.entity.Projectile) entity.getBukkitEntity(), craftBlock, blockFace, redstoneStrength);
            if (targetHitEvent.callEvent()) {
                redstoneStrength = targetHitEvent.getSignalStrength();
                shouldAward = true;
            } else {
                return redstoneStrength;
            }
        }
        // Paper end - Add TargetHitEvent
        if (!level.getBlockTicks().hasScheduledTick(hitResult.getBlockPos(), state.getBlock())) {
            setOutputPower(level, state, redstoneStrength, hitResult.getBlockPos(), duration);
        }

        // Paper start - Award Hit Criteria after Block Update
        if (shouldAward) {
            awardTargetHitCriteria((Projectile) entity, hitResult, redstoneStrength);
        }
        // Paper end - Award Hit Criteria after Block Update
        return redstoneStrength;
    }

    private static int getRedstoneStrength(final BlockHitResult hitResult, final Vec3 hitLocation) {
        Direction hitDirection = hitResult.getDirection();
        double distX = Math.abs(Mth.frac(hitLocation.x) - 0.5);
        double distY = Math.abs(Mth.frac(hitLocation.y) - 0.5);
        double distZ = Math.abs(Mth.frac(hitLocation.z) - 0.5);
        Direction.Axis axis = hitDirection.getAxis();
        double distance;
        if (axis == Direction.Axis.Y) {
            distance = Math.max(distX, distZ);
        } else if (axis == Direction.Axis.Z) {
            distance = Math.max(distX, distY);
        } else {
            distance = Math.max(distY, distZ);
        }

        return Math.max(1, Mth.ceil(15.0 * Mth.clamp((0.5 - distance) / 0.5, 0.0, 1.0)));
    }

    private static void setOutputPower(final LevelAccessor level, final BlockState state, int outputStrength, final BlockPos pos, final int duration) { // Paper - remove final
        // Paper start - Call BlockRedstoneEvent
        if (state.getValue(OUTPUT_POWER) != outputStrength) {
            outputStrength = org.bukkit.craftbukkit.event.CraftEventFactory.callRedstoneChange(level, pos, state.getValue(OUTPUT_POWER), outputStrength).getNewCurrent();
        }
        // Paper end - Call BlockRedstoneEvent
        level.setBlock(pos, state.setValue(OUTPUT_POWER, outputStrength), Block.UPDATE_ALL);
        level.scheduleTick(pos, state.getBlock(), duration);
    }

    @Override
    protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        if (state.getValue(OUTPUT_POWER) != 0) {
            // Paper start - Call BlockRedstoneEvent
            org.bukkit.event.block.BlockRedstoneEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callRedstoneChange(level, pos, state.getValue(OUTPUT_POWER), 0);
            if (event.getNewCurrent() > 0) {
                return;
            }
            // Paper end - Call BlockRedstoneEvent
            level.setBlock(pos, state.setValue(OUTPUT_POWER, 0), Block.UPDATE_ALL);
        }
    }

    @Override
    protected int ownSignal(final BlockState state, final BlockGetter level, final BlockPos pos) {
        return state.getValue(OUTPUT_POWER);
    }

    @Override
    protected boolean isSignalSource(final BlockState state) {
        return true;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OUTPUT_POWER);
    }

    @Override
    protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(oldState.getBlock())) {
            if (state.getValue(OUTPUT_POWER) > 0 && !level.getBlockTicks().hasScheduledTick(pos, this)) {
                level.setBlock(pos, state.setValue(OUTPUT_POWER, 0), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }
}
