package net.minecraft.core.cauldron;

import com.mojang.serialization.Codec;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;

public class CauldronInteractions {
    private static final ExtraCodecs.LateBoundIdMapper<String, CauldronInteraction.Dispatcher> ID_MAPPER = new ExtraCodecs.LateBoundIdMapper<>();
    public static final Codec<CauldronInteraction.Dispatcher> CODEC = ID_MAPPER.codec(Codec.STRING);
    public static final CauldronInteraction.Dispatcher EMPTY = newDispatcher("empty");
    public static final CauldronInteraction.Dispatcher WATER = newDispatcher("water");
    public static final CauldronInteraction.Dispatcher LAVA = newDispatcher("lava");
    public static final CauldronInteraction.Dispatcher POWDER_SNOW = newDispatcher("powder_snow");

    private static CauldronInteraction.Dispatcher newDispatcher(final String name) {
        CauldronInteraction.Dispatcher result = new CauldronInteraction.Dispatcher();
        ID_MAPPER.put(name, result);
        return result;
    }

    public static void bootStrap() {
        addDefaultInteractions(EMPTY);
        EMPTY.put(Items.POTION, (var0, level, pos, player, hand, itemInHand, hitDirection) -> { // Paper - add hitDirection
            PotionContents potion = itemInHand.get(DataComponents.POTION_CONTENTS);
            if (potion != null && potion.is(Potions.WATER)) {
                if (!level.isClientSide()) {
                    // CraftBukkit start
                    if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, Blocks.WATER_CAULDRON.defaultBlockState(), player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.BOTTLE_EMPTY)) { // Paper - Call CauldronLevelChangeEvent
                        return InteractionResult.SUCCESS;
                    }
                    // CraftBukkit end
                    Item usedItem = itemInHand.getItem();
                    player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, new ItemStack(Items.GLASS_BOTTLE)));
                    player.awardStat(Stats.USE_CAULDRON);
                    player.awardStat(Stats.ITEM_USED.get(usedItem));
                    // level.setBlockAndUpdate(pos, Blocks.WATER_CAULDRON.defaultBlockState()); // CraftBukkit
                    level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                    level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
                }

                return InteractionResult.SUCCESS;
            } else {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            }
        });
        addDefaultInteractions(WATER);
        WATER.put(
            Items.BUCKET,
            (state, level, pos, player, hand, itemInHand, hitDirection) -> fillBucket( // Paper - add hitDirection
                state,
                level,
                pos,
                player,
                hand,
                itemInHand,
                new ItemStack(Items.WATER_BUCKET),
                s -> s.getValue(LayeredCauldronBlock.LEVEL) == 3,
                SoundEvents.BUCKET_FILL, hitDirection // Paper - add hitDirection
            )
        );
        WATER.put(Items.GLASS_BOTTLE, (state, level, pos, player, hand, itemInHand, hitDirection) -> { // Paper - add hitDirection
            if (!level.isClientSide()) {
                // CraftBukkit start
                if (!LayeredCauldronBlock.lowerFillLevel(state, level, pos, player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL)) {
                    return InteractionResult.SUCCESS;
                }
                // CraftBukkit end
                Item usedItem = itemInHand.getItem();
                player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, PotionContents.createItemStack(Items.POTION, Potions.WATER)));
                player.awardStat(Stats.USE_CAULDRON);
                player.awardStat(Stats.ITEM_USED.get(usedItem));
                // LayeredCauldronBlock.lowerFillLevel(state, level, pos); // CraftBukkit
                level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
            }

            return InteractionResult.SUCCESS;
        });
        WATER.put(Items.POTION, (state, level, pos, player, hand, itemInHand, hitDirection) -> { // Paper - add hitDirection
            if (state.getValue(LayeredCauldronBlock.LEVEL) == 3) {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            }

            PotionContents potion = itemInHand.get(DataComponents.POTION_CONTENTS);
            if (potion != null && potion.is(Potions.WATER)) {
                if (!level.isClientSide()) {
                    // CraftBukkit start
                    if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, state.cycle(LayeredCauldronBlock.LEVEL), player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.BOTTLE_EMPTY)) { // Paper - Call CauldronLevelChangeEvent
                        return InteractionResult.SUCCESS;
                    }
                    // CraftBukkit end
                    player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, new ItemStack(Items.GLASS_BOTTLE)));
                    player.awardStat(Stats.USE_CAULDRON);
                    player.awardStat(Stats.ITEM_USED.get(itemInHand.getItem()));
                    // level.setBlockAndUpdate(pos, state.cycle(LayeredCauldronBlock.LEVEL)); // CraftBukkit
                    level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                    level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
                }

                return InteractionResult.SUCCESS;
            } else {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            }
        });
        WATER.put(ItemTags.CAULDRON_CAN_REMOVE_DYE, CauldronInteractions::dyedItemIteration);
        Items.BANNER.forEach(banner -> WATER.put(banner, CauldronInteractions::bannerInteraction));
        Items.DYED_SHULKER_BOX.forEach(dyedShulkerBox -> WATER.put(dyedShulkerBox, CauldronInteractions::shulkerBoxInteraction));
        LAVA.put(
            Items.BUCKET,
            (state, level, pos, player, hand, itemInHand, hitDirection) -> fillBucket( // Paper - add hitDirection
                state, level, pos, player, hand, itemInHand, new ItemStack(Items.LAVA_BUCKET), var0x -> true, SoundEvents.BUCKET_FILL_LAVA, hitDirection // Paper - add hitDirection
            )
        );
        addDefaultInteractions(LAVA);
        POWDER_SNOW.put(
            Items.BUCKET,
            (state, level, pos, player, hand, itemInHand, hitDirection) -> fillBucket( // Paper - add hitDirection
                state,
                level,
                pos,
                player,
                hand,
                itemInHand,
                new ItemStack(Items.POWDER_SNOW_BUCKET),
                s -> s.getValue(LayeredCauldronBlock.LEVEL) == 3,
                SoundEvents.BUCKET_FILL_POWDER_SNOW, hitDirection // Paper - add hitDirection
            )
        );
        addDefaultInteractions(POWDER_SNOW);
    }

    public static void addDefaultInteractions(final CauldronInteraction.Dispatcher interactionMap) {
        interactionMap.put(Items.LAVA_BUCKET, CauldronInteractions::fillLavaInteraction);
        interactionMap.put(Items.WATER_BUCKET, CauldronInteractions::fillWaterInteraction);
        interactionMap.put(Items.POWDER_SNOW_BUCKET, CauldronInteractions::fillPowderSnowInteraction);
    }

    public static InteractionResult fillBucket(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Player player,
        final InteractionHand hand,
        final ItemStack itemInHand,
        final ItemStack newItem,
        final Predicate<BlockState> canFill,
        final SoundEvent soundEvent
    ) {
        // Paper start - add hitDirection
        return fillBucket(state, level, pos, player, hand, itemInHand, newItem, canFill, soundEvent, null);
    }

    static InteractionResult fillBucket(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Player player,
        final InteractionHand hand,
        final ItemStack itemInHand,
        ItemStack newItem,
        final Predicate<BlockState> canFill,
        final SoundEvent soundEvent,
        final net.minecraft.core.@org.jspecify.annotations.Nullable Direction hitDirection
    ) {
        // Paper end - add hitDirection
        if (!canFill.test(state)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        if (!level.isClientSide()) {
            // Paper start - fire PlayerBucketFillEvent
            if (hitDirection != null) {
                org.bukkit.event.player.PlayerBucketEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerBucketFillEvent(level, player, pos, pos, hitDirection, itemInHand, newItem.getItem(), hand);
                if (event.isCancelled()) {
                    return InteractionResult.PASS;
                }
                newItem = event.getItemStack() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItemStack()) : ItemStack.EMPTY;
            }
            // Paper end - fire PlayerBucketFillEvent
            // CraftBukkit start
            if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, Blocks.CAULDRON.defaultBlockState(), player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.BUCKET_FILL)) { // Paper - Call CauldronLevelChangeEvent
                return InteractionResult.SUCCESS;
            }
            // CraftBukkit end
            Item itemUsed = itemInHand.getItem();
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, newItem));
            player.awardStat(Stats.USE_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(itemUsed));
            // level.setBlockAndUpdate(pos, Blocks.CAULDRON.defaultBlockState()); // CraftBukkit
            level.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
        }

        return InteractionResult.SUCCESS;
    }

    public static InteractionResult emptyBucket(
        final Level level,
        final BlockPos pos,
        final Player player,
        final InteractionHand hand,
        final ItemStack itemInHand,
        final BlockState newState,
        final SoundEvent soundEvent
    ) {
        // Paper start - add hitDirection
        return emptyBucket(level, pos, player, hand, itemInHand, newState, soundEvent, null);
    }

    static InteractionResult emptyBucket(
        final Level level,
        final BlockPos pos,
        final Player player,
        final InteractionHand hand,
        final ItemStack itemInHand,
        final BlockState newState,
        final SoundEvent soundEvent,
        final net.minecraft.core.@org.jspecify.annotations.Nullable Direction hitDirection
    ) {
        // Paper end - add hitDirection
        if (!level.isClientSide()) {
            // Paper start - fire PlayerBucketEmptyEvent
            ItemStack output = new ItemStack(Items.BUCKET);
            if (hitDirection != null) {
                org.bukkit.event.player.PlayerBucketEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerBucketEmptyEvent(level, player, pos, pos, hitDirection, itemInHand, hand);
                if (event.isCancelled()) {
                    return InteractionResult.PASS;
                }
                output = event.getItemStack() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItemStack()) : ItemStack.EMPTY;
            }
            // Paper end - fire PlayerBucketEmptyEvent
            // CraftBukkit start
            if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleCauldronLevelChangeEvent(level, pos, newState, player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.BUCKET_EMPTY)) { // Paper - Call CauldronLevelChangeEvent
                return InteractionResult.SUCCESS;
            }
            // CraftBukkit end
            Item itemUsed = itemInHand.getItem();
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, output)); // Paper
            player.awardStat(Stats.FILL_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(itemUsed));
            // level.setBlockAndUpdate(pos, newState); // CraftBukkit
            level.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
        }

        return InteractionResult.SUCCESS;
    }

    private static InteractionResult fillWaterInteraction(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand, final net.minecraft.core.Direction hitDirection // Paper - add hitDirection
    ) {
        return emptyBucket(
            level, pos, player, hand, itemInHand, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), SoundEvents.BUCKET_EMPTY, hitDirection // Paper - add hitDirection
        );
    }

    private static InteractionResult fillLavaInteraction(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand, final net.minecraft.core.Direction hitDirection // Paper - add hitDirection
    ) {
        return isUnderWater(level, pos)
            ? InteractionResult.CONSUME
            : emptyBucket(level, pos, player, hand, itemInHand, Blocks.LAVA_CAULDRON.defaultBlockState(), SoundEvents.BUCKET_EMPTY_LAVA, hitDirection); // Paper - add hitDirection
    }

    private static InteractionResult fillPowderSnowInteraction(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand, final net.minecraft.core.Direction hitDirection // Paper - add hitDirection
    ) {
        return isUnderWater(level, pos)
            ? InteractionResult.CONSUME
            : emptyBucket(
                level,
                pos,
                player,
                hand,
                itemInHand,
                Blocks.POWDER_SNOW_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3),
                SoundEvents.BUCKET_EMPTY_POWDER_SNOW, hitDirection // Paper - add hitDirection
            );
    }

    private static InteractionResult shulkerBoxInteraction(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand, final net.minecraft.core.Direction hitDirection // Paper - add hitDirection
    ) {
        Block block = Block.byItem(itemInHand.getItem());
        if (!(block instanceof ShulkerBoxBlock)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        if (!level.isClientSide()) {
            // CraftBukkit start
            if (!LayeredCauldronBlock.lowerFillLevel(state, level, pos, player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.SHULKER_WASH)) {
                return InteractionResult.SUCCESS;
            }
            // CraftBukkit end
            ItemStack cleanedShulkerBox = itemInHand.transmuteCopy(Blocks.SHULKER_BOX, 1);
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, cleanedShulkerBox, false));
            player.awardStat(Stats.CLEAN_SHULKER_BOX);
            // LayeredCauldronBlock.lowerFillLevel(state, level, pos); // CraftBukkit
        }

        return InteractionResult.SUCCESS;
    }

    private static InteractionResult bannerInteraction(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand, final net.minecraft.core.Direction hitDirection // Paper - add hitDirection
    ) {
        BannerPatternLayers patterns = itemInHand.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        if (patterns.layers().isEmpty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        if (!level.isClientSide()) {
            // CraftBukkit start
            if (!LayeredCauldronBlock.lowerFillLevel(state, level, pos, player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.BANNER_WASH)) {
                return InteractionResult.SUCCESS;
            }
            // CraftBukkit end
            ItemStack cleanedBanner = itemInHand.copyWithCount(1);
            cleanedBanner.set(DataComponents.BANNER_PATTERNS, patterns.removeLast());
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, cleanedBanner, false));
            player.awardStat(Stats.CLEAN_BANNER);
            // LayeredCauldronBlock.lowerFillLevel(state, level, pos); // CraftBukkit
        }

        return InteractionResult.SUCCESS;
    }

    private static InteractionResult dyedItemIteration(
        final BlockState state, final Level level, final BlockPos pos, final Player player, final InteractionHand hand, final ItemStack itemInHand, final net.minecraft.core.Direction hitDirection // Paper - add hitDirection
    ) {
        if (!itemInHand.has(DataComponents.DYED_COLOR)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }

        if (!level.isClientSide()) {
            // CraftBukkit start
            if (!LayeredCauldronBlock.lowerFillLevel(state, level, pos, player, org.bukkit.event.block.CauldronLevelChangeEvent.ChangeReason.ARMOR_WASH)) {
                return InteractionResult.SUCCESS;
            }
            // CraftBukkit end
            itemInHand.remove(DataComponents.DYED_COLOR);
            player.awardStat(Stats.CLEAN_ARMOR);
            // LayeredCauldronBlock.lowerFillLevel(state, level, pos); // CraftBukkit
        }

        return InteractionResult.SUCCESS;
    }

    private static boolean isUnderWater(final Level level, final BlockPos pos) {
        FluidState fluidState = level.getFluidState(pos.above());
        return fluidState.is(FluidTags.WATER);
    }
}
