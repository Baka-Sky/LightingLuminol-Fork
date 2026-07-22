package net.minecraft.world.item;

import java.util.Map;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

public class BlockItem extends Item {
    @Deprecated
    private final Block block;

    public BlockItem(final Block block, final Item.Properties properties) {
        super(properties);
        this.block = block;
    }

    @Override
    public InteractionResult useOn(final UseOnContext context) {
        InteractionResult placeResult = this.place(new BlockPlaceContext(context));
        return !placeResult.consumesAction() && context.getItemInHand().has(DataComponents.CONSUMABLE)
            ? super.use(context.getLevel(), context.getPlayer(), context.getHand())
            : placeResult;
    }

    public InteractionResult place(final BlockPlaceContext placeContext) {
        if (!this.getBlock().isEnabled(placeContext.getLevel().enabledFeatures())) {
            return InteractionResult.FAIL;
        }

        if (!placeContext.canPlace()) {
            return InteractionResult.FAIL;
        }

        BlockPlaceContext updatedPlaceContext = this.updatePlacementContext(placeContext);
        if (updatedPlaceContext == null) {
            return InteractionResult.FAIL;
        }

        BlockState placementState = this.getPlacementState(updatedPlaceContext);
        final org.bukkit.block.BlockState previousState = org.bukkit.craftbukkit.block.CraftBlockStates.getBlockState(updatedPlaceContext.getLevel(), updatedPlaceContext.getClickedPos()); // Paper - Reset placed block on exception
        if (placementState == null) {
            return InteractionResult.FAIL;
        }

        if (!this.placeBlock(updatedPlaceContext, placementState)) {
            return InteractionResult.FAIL;
        }

        BlockPos pos = updatedPlaceContext.getClickedPos();
        Level level = updatedPlaceContext.getLevel();
        Player player = updatedPlaceContext.getPlayer();
        ItemStack itemStack = updatedPlaceContext.getItemInHand();
        BlockState placedState = level.getBlockState(pos);
        java.util.function.UnaryOperator<InteractionResult.PaperSuccessContext> context = c -> c.placedBlockAt(pos.immutable()); // Paper - track placed block position from block item
        if (placedState.is(placementState.getBlock())) {
            placedState = this.updateBlockStateFromTag(pos, level, itemStack, placedState);
            // Paper start - Reset placed block on exception
            try {
                boolean updatedBlockEntity = this.updateCustomBlockEntityTag(pos, level, player, itemStack, placedState);
                context = context.andThen(c -> c.updatedBlockEntity(updatedBlockEntity))::apply; // track whether the block entity got updated or not
                updateBlockEntityComponents(level, pos, itemStack);
            } catch (Exception ex) {
                ((org.bukkit.craftbukkit.block.CraftBlockState) previousState).revertPlace(false);
                if (player instanceof ServerPlayer serverPlayer) {
                    net.minecraft.server.MinecraftServer.LOGGER.warn("Player {} tried placing invalid block", player.getScoreboardName(), ex);
                    serverPlayer.connection.disconnect(net.minecraft.network.chat.Component.literal("Packet processing error"), org.bukkit.event.player.PlayerKickEvent.Cause.ILLEGAL_ACTION);
                    return InteractionResult.FAIL;
                }
                throw ex; // Rethrow exception if not placed by a player
            }
            // Paper end - Reset placed block on exception
            placedState.getBlock().setPlacedBy(level, pos, placedState, player, itemStack);
            // CraftBukkit start - special case for handling block placement with water lilies, frog spawn and snow buckets
            if (player != null && (this instanceof PlaceOnWaterBlockItem || this instanceof SolidBucketItem)) {
                org.bukkit.event.block.BlockPlaceEvent placeEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockPlaceEvent((net.minecraft.server.level.ServerLevel) level, player, updatedPlaceContext.getHand(), previousState, pos);
                if (placeEvent.isCancelled() || !placeEvent.canBuild()) {
                    ((org.bukkit.craftbukkit.block.CraftBlockState) previousState).revertPlace(false);

                    player.containerMenu.forceHeldSlot(updatedPlaceContext.getHand());
                    return InteractionResult.FAIL;
                }
            }
            // CraftBukkit end
            if (player instanceof ServerPlayer serverPlayer) {
                CriteriaTriggers.PLACED_BLOCK.trigger(serverPlayer, pos, itemStack);
            }
        }

        SoundType soundType = placedState.getSoundType();
        if (player == null) // Paper - Fix block place logic; reintroduce this for the dispenser (i.e the shulker)
        level.playSound(player, pos, this.getPlaceSound(placedState), SoundSource.BLOCKS, (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
        level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(player, placedState));
        itemStack.consume(1, player);
        return InteractionResult.SUCCESS.configurePaper(context); // Paper
    }

    protected SoundEvent getPlaceSound(final BlockState blockState) {
        return blockState.getSoundType().getPlaceSound();
    }

    public @Nullable BlockPlaceContext updatePlacementContext(final BlockPlaceContext context) {
        return context;
    }

    private static void updateBlockEntityComponents(final Level level, final BlockPos pos, final ItemStack itemStack) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            entity.applyComponentsFromItemStack(itemStack);
            entity.setChanged();
        }
    }

    protected boolean updateCustomBlockEntityTag(
        final BlockPos pos, final Level level, final @Nullable Player player, final ItemStack itemStack, final BlockState placedState
    ) {
        return updateCustomBlockEntityTag(level, player, pos, itemStack);
    }

    protected @Nullable BlockState getPlacementState(final BlockPlaceContext context) {
        BlockState stateForPlacement = this.getBlock().getStateForPlacement(context);
        return stateForPlacement != null && this.canPlace(context, stateForPlacement) ? stateForPlacement : null;
    }

    private BlockState updateBlockStateFromTag(final BlockPos pos, final Level level, final ItemStack itemStack, final BlockState placedState) {
        BlockItemStateProperties blockState = itemStack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY);
        if (blockState.isEmpty()) {
            return placedState;
        }

        BlockState modifiedState = blockState.apply(placedState);
        if (modifiedState != placedState) {
            level.setBlock(pos, modifiedState, Block.UPDATE_CLIENTS);
        }

        return modifiedState;
    }

    protected boolean canPlace(final BlockPlaceContext context, final BlockState stateForPlacement) {
        Player player = context.getPlayer();
        // CraftBukkit start
        Level world = context.getLevel(); // Paper - Cancel hit for vanished players
        boolean canBuild = (!this.mustSurvive() || stateForPlacement.canSurvive(world, context.getClickedPos()))
            && world.checkEntityCollision(stateForPlacement, player, CollisionContext.placementContext(player), context.getClickedPos(), true); // Paper - Cancel hit for vanished players
        org.bukkit.entity.Player bukkitPlayer = (context.getPlayer() instanceof ServerPlayer) ? (org.bukkit.entity.Player) context.getPlayer().getBukkitEntity() : null;

        org.bukkit.event.block.BlockCanBuildEvent event = new org.bukkit.event.block.BlockCanBuildEvent(
            org.bukkit.craftbukkit.block.CraftBlock.at(world, context.getClickedPos()), bukkitPlayer,
            stateForPlacement.asBlockData(), canBuild, org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(context.getHand())
        );
        world.getCraftServer().getPluginManager().callEvent(event);

        return event.isBuildable();
        // CraftBukkit end
    }

    protected boolean mustSurvive() {
        return true;
    }

    protected boolean placeBlock(final BlockPlaceContext context, final BlockState placementState) {
        return context.getLevel().setBlock(context.getClickedPos(), placementState, Block.UPDATE_ALL_IMMEDIATE);
    }

    public static boolean updateCustomBlockEntityTag(final Level level, final @Nullable Player player, final BlockPos pos, final ItemStack itemStack) {
        if (level.isClientSide()) {
            return false;
        }

        TypedEntityData<BlockEntityType<?>> customData = itemStack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (customData != null) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                BlockEntityType<?> type = blockEntity.getType();
                if (type != customData.type()) {
                    return false;
                }

                if (!type.onlyOpCanSetNbt() || player != null && (player.canUseGameMasterBlocks() || (player.getAbilities().instabuild && player.getBukkitEntity().hasPermission("minecraft.nbt.place")))) { // Spigot - add permission
                    return customData.loadInto(blockEntity, level.registryAccess());
                }

                return false;
            }
        }

        return false;
    }

    @Override
    public boolean shouldPrintOpWarning(final ItemStack stack, final @Nullable Player player) {
        if (player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            TypedEntityData<BlockEntityType<?>> blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (blockEntityData != null) {
                return blockEntityData.type().onlyOpCanSetNbt();
            }
        }

        return false;
    }

    public Block getBlock() {
        return this.block;
    }

    public void registerBlocks(final Map<Block, Item> map, final Item item) {
        map.put(this.getBlock(), item);
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return !(this.getBlock() instanceof ShulkerBoxBlock);
    }

    @Override
    public void onDestroyed(final ItemEntity entity) {
        ItemContainerContents container = entity.getItem().set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        if (container != null) {
            ItemUtils.onContainerDestroyed(entity, container.nonEmptyItemCopyStream());
        }
    }

    public static void setBlockEntityData(final ItemStack stack, final BlockEntityType<?> type, final TagValueOutput output) {
        output.discard("id");
        if (output.isEmpty()) {
            stack.remove(DataComponents.BLOCK_ENTITY_DATA);
        } else {
            BlockEntity.addEntityType(output, type);
            stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(type, output.buildResult()));
        }
    }
}
