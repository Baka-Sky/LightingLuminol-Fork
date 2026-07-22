package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

public class PressurePlateBlock extends BasePressurePlateBlock {
    public static final MapCodec<PressurePlateBlock> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(BlockSetType.CODEC.fieldOf("block_set_type").forGetter(b -> b.type), propertiesCodec()).apply(i, PressurePlateBlock::new)
    );
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    @Override
    public MapCodec<PressurePlateBlock> codec() {
        return CODEC;
    }

    protected PressurePlateBlock(final BlockSetType type, final BlockBehaviour.Properties properties) {
        super(properties, type);
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected int getSignalForState(final BlockState state) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    protected BlockState setSignalForState(final BlockState state, final int signal) {
        return state.setValue(POWERED, signal > 0);
    }

    @Override
    protected int getSignalStrength(final Level level, final BlockPos pos) {
        Class<? extends Entity> entityClass = switch (this.type.pressurePlateSensitivity()) {
            case EVERYTHING -> Entity.class;
            case MOBS -> LivingEntity.class;
        };
        // CraftBukkit start - Call interact event when turning on a pressure plate
        for (Entity entity : getEntities(level, PressurePlateBlock.TOUCH_AABB.move(pos), entityClass)) {
            if (this.getSignalForState(level.getBlockState(pos)) == 0) {
                org.bukkit.event.Cancellable cancellable;

                if (entity instanceof net.minecraft.world.entity.player.Player player) {
                    cancellable = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerInteractEvent(player, org.bukkit.event.block.Action.PHYSICAL, pos, null, null, null);
                } else {
                    cancellable = new org.bukkit.event.entity.EntityInteractEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos));
                    level.getCraftServer().getPluginManager().callEvent((org.bukkit.event.entity.EntityInteractEvent) cancellable);
                }

                // We only want to block turning the plate on if all events are cancelled
                if (cancellable.isCancelled()) {
                    continue;
                }
            }

            return 15;
        }

        return 0;
        // CraftBukkit end
    }

    @Override
    protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }
}
