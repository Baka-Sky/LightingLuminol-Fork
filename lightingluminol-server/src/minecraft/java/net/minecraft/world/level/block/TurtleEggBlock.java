package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.turtle.Turtle;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class TurtleEggBlock extends Block {
    public static final MapCodec<TurtleEggBlock> CODEC = simpleCodec(TurtleEggBlock::new);
    public static final IntegerProperty HATCH = BlockStateProperties.HATCH;
    public static final IntegerProperty EGGS = BlockStateProperties.EGGS;
    public static final int MAX_HATCH_LEVEL = 2;
    public static final int MIN_EGGS = 1;
    public static final int MAX_EGGS = 4;
    private static final VoxelShape SHAPE_SINGLE = Block.box(3.0, 0.0, 3.0, 12.0, 7.0, 12.0);
    private static final VoxelShape SHAPE_MULTIPLE = Block.column(14.0, 0.0, 7.0);

    @Override
    public MapCodec<TurtleEggBlock> codec() {
        return CODEC;
    }

    public TurtleEggBlock(final BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(HATCH, 0).setValue(EGGS, 1));
    }

    @Override
    public void stepOn(final Level level, final BlockPos pos, final BlockState onState, final Entity entity) {
        if (!entity.isSteppingCarefully()) {
            this.destroyEgg(level, onState, pos, entity, 100);
        }

        super.stepOn(level, pos, onState, entity);
    }

    @Override
    public void fallOn(final Level level, final BlockState state, final BlockPos pos, final Entity entity, final double fallDistance) {
        if (!(entity instanceof Zombie)) {
            this.destroyEgg(level, state, pos, entity, 3);
        }

        super.fallOn(level, state, pos, entity, fallDistance);
    }

    private void destroyEgg(final Level level, final BlockState state, final BlockPos pos, final Entity entity, final int randomness) {
        if (state.is(Blocks.TURTLE_EGG)
            && level instanceof ServerLevel serverLevel
            && this.canDestroyEgg(serverLevel, entity)
            && level.getRandom().nextInt(randomness) == 0) {
            // CraftBukkit start - Step on eggs
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
            // CraftBukkit end
            this.decreaseEggs(serverLevel, pos, state);
        }
    }

    public void decreaseEggs(final Level level, final BlockPos pos, final BlockState state) {
        level.playSound(null, pos, SoundEvents.TURTLE_EGG_BREAK, SoundSource.BLOCKS, 0.7F, 0.9F + level.getRandom().nextFloat() * 0.2F);
        int numberOfEggs = state.getValue(EGGS);
        if (numberOfEggs <= 1) {
            level.destroyBlock(pos, false);
        } else {
            level.setBlock(pos, state.setValue(EGGS, numberOfEggs - 1), Block.UPDATE_CLIENTS);
            level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state));
            level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        }
    }

    @Override
    protected void randomTick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
        if (this.shouldUpdateHatchLevel(level, pos) && onSand(level, pos)) {
            int hatch = state.getValue(HATCH);
            if (hatch < 2) {
                // CraftBukkit start - Call BlockGrowEvent
                if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockGrowEvent(level, pos, state.setValue(HATCH, hatch + 1), Block.UPDATE_CLIENTS)) {
                    return;
                }
                // CraftBukkit end
                level.playSound(null, pos, SoundEvents.TURTLE_EGG_CRACK, SoundSource.BLOCKS, 0.7F, 0.9F + random.nextFloat() * 0.2F);
                level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(state));
            } else {
                // CraftBukkit start - Call BlockFadeEvent
                if (org.bukkit.craftbukkit.event.CraftEventFactory.callBlockFadeEvent(level, pos, Blocks.AIR.defaultBlockState()).isCancelled()) {
                    return;
                }
                // CraftBukkit end
                level.playSound(null, pos, SoundEvents.TURTLE_EGG_HATCH, SoundSource.BLOCKS, 0.7F, 0.9F + random.nextFloat() * 0.2F);
                level.removeBlock(pos, false);
                level.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(state));

                for (int i = 0; i < state.getValue(EGGS); i++) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
                    Turtle turtle = EntityTypes.TURTLE.create(level, EntitySpawnReason.BREEDING);
                    if (turtle != null) {
                        turtle.setAge(-24000);
                        turtle.setHomePos(pos);
                        turtle.snapTo(pos.getX() + 0.3 + i * 0.2, pos.getY(), pos.getZ() + 0.3, 0.0F, 0.0F);
                        level.addFreshEntity(turtle, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.EGG); // CraftBukkit
                    }
                }
            }
        }
    }

    public static boolean onSand(final BlockGetter level, final BlockPos pos) {
        return isSand(level, pos.below());
    }

    public static boolean isSand(final BlockGetter level, final BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.SAND);
    }

    @Override
    protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
        if (onSand(level, pos) && !level.isClientSide()) {
            level.levelEvent(LevelEvent.PARTICLES_TURTLE_EGG_PLACEMENT, pos, 15);
        }
    }

    private boolean shouldUpdateHatchLevel(final Level level, final BlockPos pos) {
        float chance = level.environmentAttributes().getValue(EnvironmentAttributes.TURTLE_EGG_HATCH_CHANCE, pos);
        return chance > 0.0F && level.getRandom().nextFloat() < chance;
    }

    @Override
    public void playerDestroy(
        final Level level,
        final Player player,
        final BlockPos pos,
        final BlockState state,
        final @Nullable BlockEntity blockEntity,
        final ItemStack destroyedWith
        , boolean includeDrops, boolean dropExp // Paper - fix drops not preventing stats/food exhaustion
    ) {
        super.playerDestroy(level, player, pos, state, blockEntity, destroyedWith, includeDrops, dropExp); // Paper - fix drops not preventing stats/food exhaustion
        this.decreaseEggs(level, pos, state);
    }

    @Override
    protected boolean canBeReplaced(final BlockState state, final BlockPlaceContext context) {
        return !context.isSecondaryUseActive() && context.getItemInHand().is(this.asItem()) && state.getValue(EGGS) < 4 || super.canBeReplaced(state, context);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
        BlockState state = context.getLevel().getBlockState(context.getClickedPos());
        return state.is(this) ? state.setValue(EGGS, Math.min(4, state.getValue(EGGS) + 1)) : super.getStateForPlacement(context);
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return state.getValue(EGGS) == 1 ? SHAPE_SINGLE : SHAPE_MULTIPLE;
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HATCH, EGGS);
    }

    private boolean canDestroyEgg(final ServerLevel level, final Entity entity) {
        return !(entity instanceof Turtle)
            && !(entity instanceof Bat)
            && entity instanceof LivingEntity
            && (entity instanceof Player || level.getGameRules().get(GameRules.MOB_GRIEFING));
    }
}
