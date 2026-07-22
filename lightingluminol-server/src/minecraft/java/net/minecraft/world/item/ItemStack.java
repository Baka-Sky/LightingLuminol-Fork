package net.minecraft.world.item;

import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.DataResult.Error;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentHolder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.util.NullOps;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.DamageResistant;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.item.component.UseRemainder;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Repairable;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Spawner;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.gameevent.GameEvent;
import org.apache.commons.lang3.function.TriConsumer;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class ItemStack implements DataComponentHolder, ItemInstance {
    private static final List<Component> OP_NBT_WARNING = List.of(
        Component.translatable("item.op_warning.line1").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
        Component.translatable("item.op_warning.line2").withStyle(ChatFormatting.RED),
        Component.translatable("item.op_warning.line3").withStyle(ChatFormatting.RED)
    );
    private static final Component UNBREAKABLE_TOOLTIP = Component.translatable("item.unbreakable").withStyle(ChatFormatting.BLUE);
    private static final Component INTANGIBLE_TOOLTIP = Component.translatable("item.intangible").withStyle(ChatFormatting.GRAY);
    public static final MapCodec<ItemStack> MAP_CODEC = MapCodec.recursive(
        "ItemStack",
        subCodec -> RecordCodecBuilder.mapCodec(
            i -> i.group(
                    Item.CODEC_WITH_BOUND_COMPONENTS.fieldOf("id").forGetter(ItemStack::typeHolder),
                    ExtraCodecs.optionalAlwaysPresentFieldOf(ExtraCodecs.intRange(1, 99), "count", 1).forGetter(ItemStack::getCount),
                    DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(s -> s.components.asPatch())
                )
                .apply(i, ItemStack::new)
        )
    );
    public static final Codec<ItemStack> CODEC = Codec.lazyInitialized(MAP_CODEC::codec);
    public static final Codec<ItemStack> OPTIONAL_CODEC = ExtraCodecs.optionalEmptyMap(CODEC)
        .xmap(itemStack -> itemStack.orElse(ItemStack.EMPTY), itemStack -> itemStack.isEmpty() ? Optional.empty() : Optional.of(itemStack));
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemStack> OPTIONAL_STREAM_CODEC = createOptionalStreamCodec(DataComponentPatch.STREAM_CODEC);
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemStack> OPTIONAL_UNTRUSTED_STREAM_CODEC = createOptionalStreamCodec(
        DataComponentPatch.DELIMITED_STREAM_CODEC
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemStack> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ItemStack>() {
        @Override
        public ItemStack decode(final RegistryFriendlyByteBuf input) {
            ItemStack itemStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(input);
            if (itemStack.isEmpty()) {
                throw new DecoderException("Empty ItemStack not allowed");
            } else {
                return itemStack;
            }
        }

        @Override
        public void encode(final RegistryFriendlyByteBuf output, final ItemStack itemStack) {
            if (itemStack.isEmpty()) {
                throw new EncoderException("Empty ItemStack not allowed");
            }

            ItemStack.OPTIONAL_STREAM_CODEC.encode(output, itemStack);
        }
    };
    public static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStack>> OPTIONAL_LIST_STREAM_CODEC = OPTIONAL_STREAM_CODEC.apply(
        ByteBufCodecs.collection(NonNullList::createWithCapacity)
    );
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ItemStack EMPTY = new ItemStack((Void)null);
    private static final Component DISABLED_ITEM_TOOLTIP = Component.translatable("item.disabled").withStyle(ChatFormatting.RED);
    private int count;
    private int popTime;
    @Deprecated
    private @Nullable Holder<Item> item;
    private PatchedDataComponentMap components;

    public static DataResult<ItemStack> validateStrict(final ItemStack itemStack) {
        DataResult<?> result = validateComponents(itemStack.getComponents());
        if (result.isError()) {
            return result.map(unit -> itemStack);
        } else {
            return itemStack.getCount() > itemStack.getMaxStackSize()
                ? DataResult.error(() -> "Item stack with stack size of " + itemStack.getCount() + " was larger than maximum: " + itemStack.getMaxStackSize())
                : DataResult.success(itemStack);
        }
    }

    private static StreamCodec<RegistryFriendlyByteBuf, ItemStack> createOptionalStreamCodec(
        final StreamCodec<RegistryFriendlyByteBuf, DataComponentPatch> patchCodec
    ) {
        return new StreamCodec<RegistryFriendlyByteBuf, ItemStack>() {
            @Override
            public ItemStack decode(final RegistryFriendlyByteBuf input) {
                int count = input.readVarInt();
                if (count <= 0) {
                    return ItemStack.EMPTY;
                }

                Holder<Item> item = Item.STREAM_CODEC.decode(input);
                DataComponentPatch patch = patchCodec.decode(input);
                return new ItemStack(item, count, patch);
            }

            @Override
            public void encode(final RegistryFriendlyByteBuf output, final ItemStack itemStack) {
                if (itemStack.isEmpty()) {
                    output.writeVarInt(0);
                } else {
                    output.writeVarInt(io.papermc.paper.util.sanitizer.ItemComponentSanitizer.sanitizeCount(io.papermc.paper.util.sanitizer.ItemObfuscationSession.currentSession(), itemStack, itemStack.getCount())); // Paper - potentially sanitize count
                    Item.STREAM_CODEC.encode(output, itemStack.typeHolder());
                    // Paper start - adventure; conditionally render translatable components
                    boolean prev = net.minecraft.network.chat.ComponentSerialization.DONT_RENDER_TRANSLATABLES.get();
                    try (final io.papermc.paper.util.SafeAutoClosable ignored = io.papermc.paper.util.sanitizer.ItemObfuscationSession.withContext(c -> c.itemStack(itemStack))) { // pass the itemstack as context to the obfuscation session
                        net.minecraft.network.chat.ComponentSerialization.DONT_RENDER_TRANSLATABLES.set(true);
                    patchCodec.encode(output, itemStack.components.asPatch());
                    } finally {
                        net.minecraft.network.chat.ComponentSerialization.DONT_RENDER_TRANSLATABLES.set(prev);
                    }
                    // Paper end - adventure; conditionally render translatable components
                }
            }
        };
    }

    public static StreamCodec<RegistryFriendlyByteBuf, ItemStack> validatedStreamCodec(final StreamCodec<RegistryFriendlyByteBuf, ItemStack> codec) {
        return new StreamCodec<RegistryFriendlyByteBuf, ItemStack>() {
            @Override
            public ItemStack decode(final RegistryFriendlyByteBuf input) {
                ItemStack itemStack = codec.decode(input);
                if (!itemStack.isEmpty()) {
                    RegistryOps<io.papermc.paper.util.CountingOps.Value> ops = input.registryAccess().createSerializationContext(io.papermc.paper.util.CountingOps.INSTANCE); // Paper - Track codec depth
                    ItemStack.CODEC.encodeStart(ops, itemStack).getOrThrow(DecoderException::new);
                }

                return itemStack;
            }

            @Override
            public void encode(final RegistryFriendlyByteBuf output, final ItemStack value) {
                codec.encode(output, value);
            }
        };
    }

    public Optional<TooltipComponent> getTooltipImage() {
        return this.getItem().getTooltipImage(this);
    }

    @Override
    public DataComponentMap getComponents() {
        return !this.isEmpty() ? this.components : DataComponentMap.EMPTY;
    }

    public DataComponentMap getPrototype() {
        return !this.isEmpty() ? this.typeHolder().components() : DataComponentMap.EMPTY;
    }

    public DataComponentPatch getComponentsPatch() {
        return !this.isEmpty() ? this.components.asPatch() : DataComponentPatch.EMPTY;
    }

    public DataComponentMap immutableComponents() {
        return !this.isEmpty() ? this.components.toImmutableMap() : DataComponentMap.EMPTY;
    }

    public boolean hasNonDefault(final DataComponentType<?> type) {
        return !this.isEmpty() && this.components.hasNonDefault(type);
    }

    public ItemStack(final ItemLike item, final int count) {
        this(item.asItem().builtInRegistryHolder(), count);
    }

    public ItemStack(final ItemLike item) {
        this(item.asItem().builtInRegistryHolder(), 1);
    }

    public ItemStack(final Holder<Item> item, final int count) {
        this(item, count, new PatchedDataComponentMap(item.components()));
    }

    public ItemStack(final Holder<Item> item) {
        this(item, 1);
    }

    public ItemStack(final Holder<Item> item, final int count, final DataComponentPatch components) {
        this(item, count, PatchedDataComponentMap.fromPatch(item.components(), components));
    }

    private ItemStack(final Holder<Item> item, final int count, final PatchedDataComponentMap components) {
        this.item = item;
        this.count = count;
        this.components = components;
    }

    private ItemStack(final @Nullable Void nullMarker) {
        this.item = null;
        this.components = new PatchedDataComponentMap(DataComponentMap.EMPTY);
    }

    private static DataResult<?> validateComponents(final DataComponentMap components) {
        if (components.has(DataComponents.MAX_DAMAGE) && components.getOrDefault(DataComponents.MAX_STACK_SIZE, 1) > 1) {
            return DataResult.error(() -> "Item cannot be both damageable and stackable");
        }

        ItemContainerContents container = components.get(DataComponents.CONTAINER);
        if (container != null) {
            DataResult<?> validationContents = validateContainedItemSizes(container.nonEmptyItems());
            if (validationContents.isError()) {
                return validationContents;
            }
        }

        BundleContents bundle = components.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            DataResult<?> validationResult = validateContainedItemSizes(bundle.items());
            if (validationResult.isError()) {
                return validationResult;
            }

            validationResult = bundle.weight();
            if (validationResult.isError()) {
                return validationResult;
            }
        }

        ChargedProjectiles chargedProjectiles = components.get(DataComponents.CHARGED_PROJECTILES);
        if (chargedProjectiles != null) {
            DataResult<?> validationResult = validateContainedItemSizes(chargedProjectiles.items());
            if (validationResult.isError()) {
                return validationResult;
            }
        }

        return DataResult.success(Unit.INSTANCE);
    }

    private static DataResult<?> validateContainedItemSizes(final Iterable<? extends ItemInstance> items) {
        for (ItemInstance item : items) {
            int itemCount = item.count();
            int maxStackSize = item.getMaxStackSize();
            if (itemCount > maxStackSize) {
                return DataResult.error(() -> "Item stack with count of " + itemCount + " was larger than maximum: " + maxStackSize);
            }
        }

        return DataResult.success(Unit.INSTANCE);
    }

    public boolean isEmpty() {
        return this == EMPTY || this.item.value() == Items.AIR || this.count <= 0;
    }

    public boolean isItemEnabled(final FeatureFlagSet enabledFeatures) {
        return this.isEmpty() || this.getItem().isEnabled(enabledFeatures);
    }

    public ItemStack split(final int amount) {
        int realAmount = Math.min(amount, this.getCount());
        ItemStack result = this.copyWithCount(realAmount);
        this.shrink(realAmount);
        return result;
    }

    public ItemStack copyAndClear() {
        if (this.isEmpty()) {
            return EMPTY;
        }

        ItemStack result = this.copy();
        this.setCount(0);
        return result;
    }

    public Item getItem() {
        return this.typeHolder().value();
    }

    @Override
    public Holder<Item> typeHolder() {
        return this.isEmpty() ? Items.AIR.builtInRegistryHolder() : this.item;
    }

    public boolean is(final Predicate<Holder<Item>> item) {
        return item.test(this.typeHolder());
    }

    public InteractionResult useOn(final UseOnContext context) {
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();
        if (player != null && !player.getAbilities().mayBuild && !this.canPlaceOnBlockInAdventureMode(new BlockInWorld(context.getLevel(), pos, false))) {
            return InteractionResult.PASS;
        }

        Item usedItem = this.getItem();
        // CraftBukkit start - handle all block place event logic here
        DataComponentPatch previousPatch = this.components.asPatch();
        int previousCount = this.getCount();
        ServerLevel level = (ServerLevel) context.getLevel();
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
        boolean isBonemeal = usedItem == Items.BONE_MEAL;

        if (!(usedItem instanceof BucketItem)) {
            worldData.captureBlockStates = true; // Folia - region threading
            worldData.captureTreeGeneration = isBonemeal; // Folia - region threading
        }

        List<org.bukkit.craftbukkit.block.CraftBlockState> capturedBlockStates;
        List<net.minecraft.world.level.block.entity.BlockEntity> capturedBlockEntities;
        org.bukkit.TreeType treeType;
        InteractionResult result;
        try {
            result = usedItem.useOn(context);
        } finally {
            worldData.captureBlockStates = false; // Folia - region threading
            worldData.captureTreeGeneration = false; // Folia - region threading

            capturedBlockStates = new java.util.ArrayList<>(worldData.capturedBlockStates.values()); // Folia - region threading
            worldData.capturedBlockStates.clear(); // Folia - region threading

            capturedBlockEntities = new java.util.ArrayList<>(worldData.capturedTileEntities.values()); // Folia - region threading
            worldData.capturedTileEntities.clear(); // Folia - region threading

            treeType = net.minecraft.world.level.block.SaplingBlock.treeTypeRT.get(); // Folia - region threading
            net.minecraft.world.level.block.SaplingBlock.treeTypeRT.set(null); // Folia - region threading
        }

        DataComponentPatch newPatch = this.components.asPatch();
        int newCount = this.getCount();
        this.setCount(previousCount);
        this.restorePatch(previousPatch);

        if (result.consumesAction() && isBonemeal && !capturedBlockStates.isEmpty()) {
            org.bukkit.Location location = org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level);
            org.bukkit.event.world.StructureGrowEvent structureEvent = null;
            if (treeType != null) {
                structureEvent = new org.bukkit.event.world.StructureGrowEvent(location, treeType, true, (org.bukkit.entity.Player) player.getBukkitEntity(), (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
                org.bukkit.Bukkit.getPluginManager().callEvent(structureEvent);
            }

            org.bukkit.event.block.BlockFertilizeEvent fertilizeEvent = new org.bukkit.event.block.BlockFertilizeEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), (org.bukkit.entity.Player) player.getBukkitEntity(), (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates);
            fertilizeEvent.setCancelled(structureEvent != null && structureEvent.isCancelled());
            org.bukkit.Bukkit.getPluginManager().callEvent(fertilizeEvent);

            if (!fertilizeEvent.isCancelled()) {
                // Change the stack to its new contents if it hasn't been tampered with.
                if (this.getCount() == previousCount && Objects.equals(this.components.asPatch(), previousPatch)) {
                    this.restorePatch(newPatch);
                    this.setCount(newCount);
                }
                for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                    // SPIGOT-7572 - Move fix for SPIGOT-7248 to CapturedBlockState, to allow bees in bee nest
                    snapshot.place(snapshot.getFlags());
                    level.checkCapturedTreeStateForObserverNotify(pos, snapshot); // Paper - notify observers even if grow failed
                }
                player.awardStat(Stats.ITEM_USED.get(usedItem)); // SPIGOT-7236 - award stat
            }
            return result;
        }

        if (player != null && result instanceof InteractionResult.Success success && success.wasItemInteraction()) {
            InteractionHand hand = context.getHand();
            org.bukkit.event.block.BlockPlaceEvent placeEvent = null;
            if (capturedBlockStates.size() > 1) {
                placeEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockMultiPlaceEvent(level, player, hand, (List<org.bukkit.block.BlockState>) (List<? extends org.bukkit.block.BlockState>) capturedBlockStates, pos);
            } else if (capturedBlockStates.size() == 1 && usedItem != Items.POWDER_SNOW_BUCKET) { // Paper - Fix cancelled powdered snow bucket placement
                placeEvent = org.bukkit.craftbukkit.event.CraftEventFactory.callBlockPlaceEvent(level, player, hand, capturedBlockStates.getFirst(), pos);
            }

            if (placeEvent != null && (placeEvent.isCancelled() || !placeEvent.canBuild())) {
                result = InteractionResult.FAIL; // cancel placement
                // PAIL: Remove this when MC-99075 fixed
                player.containerMenu.forceHeldSlot(hand);
                // revert back all captured blocks
                for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                    snapshot.revertPlace(true); // poi updates needs to be skipped since the poi were never registered as part of the capture
                }
            } else {
                // Change the stack to its new contents if it hasn't been tampered with.
                if (this.getCount() == previousCount && Objects.equals(this.components.asPatch(), previousPatch)) {
                    this.restorePatch(newPatch);
                    this.setCount(newCount);
                }

                for (net.minecraft.world.level.block.entity.BlockEntity blockEntity : capturedBlockEntities) {
                    level.setBlockEntity(blockEntity);
                }

                for (org.bukkit.craftbukkit.block.CraftBlockState snapshot : capturedBlockStates) {
                    net.minecraft.world.level.block.state.BlockState oldBlock = snapshot.getHandle();
                    BlockPos newPos = snapshot.getPosition();
                    net.minecraft.world.level.block.state.BlockState block = level.getBlockState(newPos);

                    if (!(block.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock)) { // Containers get placed automatically
                        block.onPlace(level, newPos, oldBlock, true, context);
                    }

                    level.notifyAndUpdatePhysics(newPos, null, oldBlock, block, level.getBlockState(newPos), snapshot.getFlags(), net.minecraft.world.level.block.Block.UPDATE_LIMIT); // send null chunk as chunk.k() returns false by this point
                }

                BlockPos placedPos = success.paperSuccessContext().placedPos();
                if (usedItem == Items.WITHER_SKELETON_SKULL && placedPos != null) { // Special case skulls to allow wither spawns to be cancelled
                    net.minecraft.world.level.block.entity.BlockEntity blockEntity = level.getBlockEntity(placedPos);
                    if (blockEntity instanceof net.minecraft.world.level.block.entity.SkullBlockEntity placedSkull) {
                        net.minecraft.world.level.block.WitherSkullBlock.checkSpawn(level, placedPos, placedSkull);
                    }
                }

                // SPIGOT-4678
                if (usedItem instanceof SignItem && placedPos != null && !success.paperSuccessContext().updatedBlockEntity()) {
                    if (level.getBlockEntity(placedPos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity signEntity) {
                        if (level.getBlockState(placedPos).getBlock() instanceof net.minecraft.world.level.block.SignBlock sign) {
                            sign.openTextEdit(player, signEntity, true, io.papermc.paper.event.player.PlayerOpenSignEvent.Cause.PLACE); // CraftBukkit // Paper - Add PlayerOpenSignEvent
                        }
                    }
                }

                // SPIGOT-1288 - play sound stripped from BlockItem
                if (usedItem instanceof BlockItem blockItem && placedPos != null) {
                    BlockState placedState = level.getBlockState(placedPos);
                    net.minecraft.world.level.block.SoundType soundType = placedState.getSoundType();
                    level.playSound(player, pos, blockItem.getPlaceSound(placedState), net.minecraft.sounds.SoundSource.BLOCKS, (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
                }

                player.awardStat(Stats.ITEM_USED.get(usedItem));
            }
        }
        // CraftBukkit end

        return result;
    }

    public float getDestroySpeed(final BlockState state) {
        return this.getItem().getDestroySpeed(this, state);
    }

    public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
        ItemStack stackBeforeUse = this.copy();
        boolean isInstantlyUsed = this.getUseDuration(player) <= 0;
        InteractionResult result = this.getItem().use(level, player, hand);
        return isInstantlyUsed && result instanceof InteractionResult.Success success
            ? success.heldItemTransformedTo(
                success.heldItemTransformedTo() == null
                    ? this.applyAfterUseComponentSideEffects(player, stackBeforeUse)
                    : success.heldItemTransformedTo().applyAfterUseComponentSideEffects(player, stackBeforeUse)
            )
            : result;
    }

    public ItemStack finishUsingItem(final Level level, final LivingEntity livingEntity) {
        ItemStack stackBeforeUse = this.copy();
        ItemStack result = this.getItem().finishUsingItem(this, level, livingEntity);
        return result.applyAfterUseComponentSideEffects(livingEntity, stackBeforeUse);
    }

    private ItemStack applyAfterUseComponentSideEffects(final LivingEntity user, final ItemStack stackBeforeUsing) {
        UseRemainder useRemainder = stackBeforeUsing.get(DataComponents.USE_REMAINDER);
        UseCooldown useCooldown = stackBeforeUsing.get(DataComponents.USE_COOLDOWN);
        int stackCountBeforeUsing = stackBeforeUsing.getCount();
        ItemStack result = this;
        if (useRemainder != null) {
            result = useRemainder.convertIntoRemainder(result, stackCountBeforeUsing, user.hasInfiniteMaterials(), user::handleExtraItemsCreatedOnUse);
        }

        if (useCooldown != null) {
            useCooldown.apply(stackBeforeUsing, user);
        }

        return result;
    }

    public boolean isStackable() {
        return this.getMaxStackSize() > 1 && (!this.isDamageableItem() || !this.isDamaged());
    }

    public boolean isDamageableItem() {
        return this.has(DataComponents.MAX_DAMAGE) && !this.has(DataComponents.UNBREAKABLE) && this.has(DataComponents.DAMAGE);
    }

    public boolean isDamaged() {
        return this.isDamageableItem() && this.getDamageValue() > 0;
    }

    public int getDamageValue() {
        return Mth.clamp(this.getOrDefault(DataComponents.DAMAGE, 0), 0, this.getMaxDamage());
    }

    public void setDamageValue(final int value) {
        this.set(DataComponents.DAMAGE, Mth.clamp(value, 0, this.getMaxDamage()));
    }

    public int getMaxDamage() {
        return this.getOrDefault(DataComponents.MAX_DAMAGE, 0);
    }

    public boolean isBroken() {
        return this.isDamageableItem() && this.getDamageValue() >= this.getMaxDamage();
    }

    public boolean nextDamageWillBreak() {
        return this.isDamageableItem() && this.getDamageValue() >= this.getMaxDamage() - 1;
    }

    public void hurtAndBreak(final int amount, final ServerLevel level, final @Nullable LivingEntity player, final Consumer<Item> onBreak) { // Paper - Add EntityDamageItemEvent
        // Paper start - add force boolean overload
        this.hurtAndBreak(amount, level, player, onBreak, false);
    }

    public void hurtAndBreak(final int amount, final ServerLevel level, final @Nullable LivingEntity player, final Consumer<Item> onBreak, final boolean force) { // Paper - Add EntityDamageItemEvent
        // Paper end
        final int originalDamage = amount; // Paper - Expand PlayerItemDamageEvent
        int newAmount = this.processDurabilityChange(amount, level, player, force); // Paper
        // CraftBukkit start
        if (newAmount > 0 && player instanceof final ServerPlayer serverPlayer) { // Paper - Add EntityDamageItemEvent - limit to positive damage and run for player
            org.bukkit.event.player.PlayerItemDamageEvent event = new org.bukkit.event.player.PlayerItemDamageEvent(serverPlayer.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this), newAmount, originalDamage); // Paper - Add EntityDamageItemEvent
            event.getPlayer().getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }

            newAmount = event.getDamage();
            // Paper start - Add EntityDamageItemEvent
        } else if (newAmount > 0 && player != null) {
            io.papermc.paper.event.entity.EntityDamageItemEvent event = new io.papermc.paper.event.entity.EntityDamageItemEvent(player.getBukkitLivingEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this), newAmount);
            if (!event.callEvent()) {
                return;
            }
            newAmount = event.getDamage();
            // Paper end - Add EntityDamageItemEvent
        }
        // CraftBukkit end
        if (newAmount != 0) { // Paper - Add EntityDamageItemEvent - diff on change for above event ifs.
            this.applyDamage(this.getDamageValue() + newAmount, player, onBreak);
        }
    }

    private int processDurabilityChange(final int amount, final ServerLevel level, final @Nullable LivingEntity player) { // Paper - Add EntityDamageItemEvent
        // Paper start - itemstack damage api
        return this.processDurabilityChange(amount, level, player, false);
    }
    private int processDurabilityChange(final int amount, final ServerLevel level, final @Nullable LivingEntity player, final boolean force) {
        // Paper end - itemstack damage api
        if (!this.isDamageableItem()) {
            return 0;
        } else if (player instanceof ServerPlayer && player.hasInfiniteMaterials() && !force) { // Paper - Add EntityDamageItemEvent
            return 0;
        } else {
            return amount > 0 ? EnchantmentHelper.processDurabilityChange(level, this, amount) : amount;
        }
    }

    private void applyDamage(final int newDamage, final @Nullable LivingEntity player, final Consumer<Item> onBreak) { // Paper - Add EntityDamageItemEvent
        if (player instanceof final ServerPlayer serverPlayer) { // Paper - Add EntityDamageItemEvent
            CriteriaTriggers.ITEM_DURABILITY_CHANGED.trigger(serverPlayer, this, newDamage); // Paper - Add EntityDamageItemEvent
        }

        this.setDamageValue(newDamage);
        if (this.isBroken()) {
            Item item = this.getItem();
            // CraftBukkit start - Check for item breaking
            if (this.getCount() == 1 && player instanceof final ServerPlayer serverPlayer) { // Paper - Add EntityDamageItemEvent
                org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerItemBreakEvent(serverPlayer, this); // Paper - Add EntityDamageItemEvent
            }
            // CraftBukkit end
            this.shrink(1);
            onBreak.accept(item);
        }
    }

    public void hurtWithoutBreaking(final int amount, final Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            int newAmount = this.processDurabilityChange(amount, serverPlayer.level(), serverPlayer);
            if (newAmount == 0) {
                return;
            }

            int newDamage = Math.min(this.getDamageValue() + newAmount, this.getMaxDamage() - 1); // Paper - Expand PlayerItemDamageEvent - diff on change as min computation is copied post event.

            // Paper start - Expand PlayerItemDamageEvent
            if (newDamage - this.getDamageValue() > 0) {
                org.bukkit.event.player.PlayerItemDamageEvent event = new org.bukkit.event.player.PlayerItemDamageEvent(
                    serverPlayer.getBukkitEntity(),
                    org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this),
                    newDamage - this.getDamageValue(),
                    amount
                );
                if (!event.callEvent() || event.getDamage() == 0) {
                    return;
                }

                // Prevent breaking the item in this code path as callers may expect the item to survive
                // (given the method name)
                newDamage = Math.min(this.getDamageValue() + event.getDamage(), this.getMaxDamage() - 1);
            }
            // Paper end - Expand PlayerItemDamageEvent

            this.applyDamage(newDamage, serverPlayer, i -> {});
        }
    }

    public void hurtAndBreak(final int amount, final LivingEntity owner, final InteractionHand hand) {
        this.hurtAndBreak(amount, owner, hand.asEquipmentSlot());
    }

    public void hurtAndBreak(final int amount, final LivingEntity owner, final EquipmentSlot slot) {
        // Paper start - add param to skip infinite mats check
        this.hurtAndBreak(amount, owner, slot, false);
    }
    public void hurtAndBreak(final int amount, final LivingEntity owner, final EquipmentSlot slot, final boolean force) {
        // Paper end - add param to skip infinite mats check
        if (owner.level() instanceof ServerLevel serverLevel) {
            this.hurtAndBreak(
                amount, serverLevel, owner, brokenItem -> {if (slot != null) owner.onEquippedItemBroken(brokenItem, slot); }, force // Paper - Add EntityDamageItemEvent & itemstack damage API - do not process entity related callbacks when damaging from API
            );
        }
    }

    public ItemStack hurtAndConvertOnBreak(final int amount, final ItemLike newItem, final LivingEntity owner, final EquipmentSlot slot) {
        this.hurtAndBreak(amount, owner, slot);
        if (this.isEmpty()) {
            ItemStack replacement = this.transmuteCopyIgnoreEmpty(newItem, 1);
            if (replacement.isDamageableItem()) {
                replacement.setDamageValue(0);
            }

            return replacement;
        } else {
            return this;
        }
    }

    public boolean isBarVisible() {
        return this.getItem().isBarVisible(this);
    }

    public int getBarWidth() {
        return this.getItem().getBarWidth(this);
    }

    public int getBarColor() {
        return this.getItem().getBarColor(this);
    }

    public boolean overrideStackedOnOther(final Slot slot, final ClickAction clickAction, final Player player) {
        return this.getItem().overrideStackedOnOther(this, slot, clickAction, player);
    }

    public boolean overrideOtherStackedOnMe(
        final ItemStack other, final Slot slot, final ClickAction clickAction, final Player player, final SlotAccess carriedItem
    ) {
        return this.getItem().overrideOtherStackedOnMe(this, other, slot, clickAction, player, carriedItem);
    }

    public boolean hurtEnemy(final LivingEntity mob, final LivingEntity attacker) {
        Item usedItem = this.getItem();
        usedItem.hurtEnemy(this, mob, attacker);
        if (this.has(DataComponents.WEAPON)) {
            if (attacker instanceof Player player) {
                player.awardStat(Stats.ITEM_USED.get(usedItem));
            }

            return true;
        } else {
            return false;
        }
    }

    public void postHurtEnemy(final LivingEntity mob, final LivingEntity attacker) {
        this.getItem().postHurtEnemy(this, mob, attacker);
        Weapon weapon = this.get(DataComponents.WEAPON);
        if (weapon != null) {
            this.hurtAndBreak(weapon.itemDamagePerAttack(), attacker, EquipmentSlot.MAINHAND);
        }
    }

    public void mineBlock(final Level level, final BlockState state, final BlockPos pos, final Player owner) {
        Item usedItem = this.getItem();
        if (usedItem.mineBlock(this, level, state, pos, owner)) {
            owner.awardStat(Stats.ITEM_USED.get(usedItem));
        }
    }

    public boolean isCorrectToolForDrops(final BlockState state) {
        return this.getItem().isCorrectToolForDrops(this, state);
    }

    public InteractionResult interactLivingEntity(final Player player, final LivingEntity target, final InteractionHand hand) {
        Equippable equippable = this.get(DataComponents.EQUIPPABLE);
        if (equippable != null && equippable.equipOnInteract()) {
            InteractionResult result = equippable.equipOnTarget(player, target, this);
            if (result != InteractionResult.PASS) {
                return result;
            }
        }

        return this.getItem().interactLivingEntity(this, player, target, hand);
    }

    public ItemStack copy() {
        // Paper start - Perf: Optimize Hoppers
        return this.copy(false);
    }

    public ItemStack copy(final boolean originalItem) {
        if (!originalItem && this.isEmpty()) {
            // Paper end - Perf: Optimize Hoppers
            return EMPTY;
        }

        ItemStack copy = new ItemStack(originalItem ? this.item : this.typeHolder(), this.count, this.components.copy()); // Paper - Perf: Optimize Hoppers
        copy.setPopTime(this.getPopTime());
        return copy;
    }

    public ItemStack copyWithCount(final int count) {
        if (this.isEmpty()) {
            return EMPTY;
        }

        ItemStack copy = this.copy();
        copy.setCount(count);
        return copy;
    }

    public ItemStack transmuteCopy(final ItemLike newItem) {
        return this.transmuteCopy(newItem, this.getCount());
    }

    public ItemStack transmuteCopy(final ItemLike newItem, final int newCount) {
        return this.isEmpty() ? EMPTY : this.transmuteCopyIgnoreEmpty(newItem, newCount);
    }

    private ItemStack transmuteCopyIgnoreEmpty(final ItemLike newItem, final int newCount) {
        return new ItemStack(newItem.asItem().builtInRegistryHolder(), newCount, this.components.asPatch());
    }

    public static boolean matches(final ItemStack a, final ItemStack b) {
        return a == b || a.getCount() == b.getCount() && isSameItemSameComponents(a, b);
    }

    @Deprecated
    public static boolean listMatches(final List<ItemStack> left, final List<ItemStack> right) {
        if (left.size() != right.size()) {
            return false;
        }

        for (int i = 0; i < left.size(); i++) {
            if (!matches(left.get(i), right.get(i))) {
                return false;
            }
        }

        return true;
    }

    public static boolean isSameItem(final ItemStack a, final ItemStack b) {
        return a.is(b.getItem());
    }

    public static boolean isSameItemSameComponents(final ItemStack a, final ItemStack b) {
        return a.is(b.getItem()) && (a.isEmpty() && b.isEmpty() || Objects.equals(a.components, b.components));
    }

    public static boolean matchesIgnoringComponents(final ItemStack a, final ItemStack b, final Predicate<DataComponentType<?>> ignoredPredicate) {
        if (a == b) {
            return true;
        }

        if (a.getCount() != b.getCount()) {
            return false;
        }

        if (!a.is(b.getItem())) {
            return false;
        }

        if (a.isEmpty() && b.isEmpty()) {
            return true;
        }

        if (a.components.size() != b.components.size()) {
            return false;
        }

        for (DataComponentType<?> type : a.components.keySet()) {
            Object componentA = a.components.get(type);
            Object componentB = b.components.get(type);
            if (componentA == null || componentB == null) {
                return false;
            }

            if (!Objects.equals(componentA, componentB) && !ignoredPredicate.test(type)) {
                return false;
            }
        }

        return true;
    }

    public static MapCodec<ItemStack> lenientOptionalFieldOf(final String name) {
        return CODEC.lenientOptionalFieldOf(name)
            .xmap(itemStack -> itemStack.orElse(EMPTY), itemStack -> itemStack.isEmpty() ? Optional.empty() : Optional.of(itemStack));
    }

    public static int hashItemAndComponents(final @Nullable ItemStack item) {
        if (item != null) {
            int result = 31 + item.getItem().hashCode();
            return 31 * result + item.getComponents().hashCode();
        } else {
            return 0;
        }
    }

    @Deprecated
    public static int hashStackList(final List<ItemStack> items) {
        int result = 0;

        for (ItemStack item : items) {
            result = result * 31 + hashItemAndComponents(item);
        }

        return result;
    }

    @Override
    public String toString() {
        return this.getCount() + " " + this.getItem();
    }

    public void inventoryTick(final Level level, final Entity owner, final @Nullable EquipmentSlot slot) {
        if (this.popTime > 0) {
            this.popTime--;
        }

        if (level instanceof ServerLevel serverLevel) {
            this.getItem().inventoryTick(this, serverLevel, owner, slot);
        }
    }

    public void onCraftedBy(final Player player, final int craftCount) {
        player.awardStat(Stats.ITEM_CRAFTED.get(this.getItem()), craftCount);
        this.getItem().onCraftedBy(this, player);
    }

    public void onCraftedBySystem(final Level level) {
        this.getItem().onCraftedPostProcess(this, level);
    }

    public int getUseDuration(final LivingEntity user) {
        return this.getItem().getUseDuration(this, user);
    }

    public ItemUseAnimation getUseAnimation() {
        return this.getItem().getUseAnimation(this);
    }

    public void releaseUsing(final Level level, final LivingEntity entity, final int remainingTime) {
        ItemStack stackBeforeUsing = this.copy();
        if (this.getItem().releaseUsing(this, level, entity, remainingTime)) {
            ItemStack withSideEffects = this.applyAfterUseComponentSideEffects(entity, stackBeforeUsing);
            if (withSideEffects != this) {
                entity.setItemInHand(entity.getUsedItemHand(), withSideEffects);
            }
        }
    }

    public void causeUseVibration(final Entity causer, final Holder.Reference<GameEvent> event) {
        UseEffects useEffects = this.get(DataComponents.USE_EFFECTS);
        if (useEffects != null && useEffects.interactVibrations()) {
            causer.gameEvent(event);
        }
    }

    public boolean useOnRelease() {
        return this.getItem().useOnRelease(this);
    }

    // CraftBukkit start
    public void restorePatch(DataComponentPatch datacomponentpatch) {
        this.components.restorePatch(datacomponentpatch);
    }
    // CraftBukkit end

    public <T> @Nullable T set(final DataComponentType<T> type, final @Nullable T value) {
        return this.components.set(type, value);
    }

    public <T> @Nullable T set(final TypedDataComponent<T> value) {
        return this.components.set(value);
    }

    public <T> void copyFrom(final DataComponentType<T> type, final DataComponentGetter source) {
        this.set(type, source.get(type));
    }

    public <T, U> @Nullable T update(final DataComponentType<T> type, final T defaultValue, final U value, final BiFunction<T, U, T> combiner) {
        return this.set(type, combiner.apply(this.getOrDefault(type, defaultValue), value));
    }

    public <T> @Nullable T update(final DataComponentType<T> type, final T defaultValue, final UnaryOperator<T> function) {
        T value = this.getOrDefault(type, defaultValue);
        return this.set(type, function.apply(value));
    }

    public <T> @Nullable T remove(final DataComponentType<? extends T> type) {
        return this.components.remove(type);
    }

    public void applyComponentsAndValidate(final DataComponentPatch patch) {
        DataComponentPatch oldPatch = this.components.asPatch();
        this.components.applyPatch(patch);
        Optional<Error<ItemStack>> validationError = validateStrict(this).error();
        if (validationError.isPresent()) {
            LOGGER.error("Failed to apply component patch '{}' to item: '{}'", patch, validationError.get().message());
            this.components.restorePatch(oldPatch);
        }
    }

    public void applyComponents(final DataComponentPatch patch) {
        this.components.applyPatch(patch);
    }

    public void applyComponents(final DataComponentMap components) {
        this.components.setAll(components);
    }

    // Paper start - (this is just a good no conflict location)
    public org.bukkit.inventory.ItemStack asBukkitMirror() {
        return org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this);
    }

    public org.bukkit.inventory.ItemStack asBukkitCopy() {
        return org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this.copy());
    }

    public static ItemStack fromBukkitCopy(org.bukkit.inventory.ItemStack itemstack) {
        return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(itemstack);
    }

    private org.bukkit.craftbukkit.inventory.@Nullable CraftItemStack bukkitStack;
    public org.bukkit.inventory.ItemStack getBukkitStack() {
        if (this.bukkitStack == null || this.bukkitStack.handle != this) {
            this.bukkitStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this);
        }
        return this.bukkitStack;
    }
    // Paper end

    public Component getHoverName() {
        Component customName = this.getCustomName();
        return customName != null ? customName : this.getItemName();
    }

    public @Nullable Component getCustomName() {
        Component customName = this.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            return customName;
        }

        WrittenBookContent content = this.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (content != null) {
            String title = content.title().raw();
            if (!StringUtil.isBlank(title)) {
                return Component.literal(title);
            }
        }

        return null;
    }

    public Component getItemName() {
        return this.getItem().getName(this);
    }

    public Component getStyledHoverName() {
        MutableComponent hoverName = Component.empty().append(this.getHoverName()).withStyle(this.getRarity().color());
        if (this.has(DataComponents.CUSTOM_NAME)) {
            hoverName.withStyle(ChatFormatting.ITALIC);
        }

        return hoverName;
    }

    public <T extends TooltipProvider> void addToTooltip(
        final DataComponentType<T> type,
        final Item.TooltipContext context,
        final TooltipDisplay display,
        final Consumer<Component> consumer,
        final TooltipFlag flag
    ) {
        T component = (T)this.get(type);
        if (component != null && display.shows(type)) {
            component.addToTooltip(context, consumer, flag, this.components);
        }
    }

    public List<Component> getTooltipLines(final Item.TooltipContext context, final @Nullable Player player, final TooltipFlag tooltipFlag) {
        TooltipDisplay display = this.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        if (!tooltipFlag.isCreative() && display.hideTooltip()) {
            boolean shouldPrintOpWarning = this.getItem().shouldPrintOpWarning(this, player);
            return shouldPrintOpWarning ? OP_NBT_WARNING : List.of();
        } else {
            List<Component> lines = Lists.newArrayList();
            lines.add(this.getStyledHoverName());
            this.addDetailsToTooltip(context, display, player, tooltipFlag, lines::add);
            return lines;
        }
    }

    public void addDetailsToTooltip(
        final Item.TooltipContext context,
        final TooltipDisplay display,
        final @Nullable Player player,
        final TooltipFlag tooltipFlag,
        final Consumer<Component> builder
    ) {
        this.getItem().appendHoverText(this, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.TROPICAL_FISH_PATTERN, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.INSTRUMENT, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.MAP_ID, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.BEES, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.CONTAINER_LOOT, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.CONTAINER, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.BANNER_PATTERNS, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.POT_DECORATIONS, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.WRITTEN_BOOK_CONTENT, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.CHARGED_PROJECTILES, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.FIREWORKS, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.FIREWORK_EXPLOSION, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.POTION_CONTENTS, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.JUKEBOX_PLAYABLE, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.TRIM, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.STORED_ENCHANTMENTS, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.ENCHANTMENTS, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.DYED_COLOR, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.PROFILE, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.LORE, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.SULFUR_CUBE_CONTENT, context, display, builder, tooltipFlag);
        this.addAttributeTooltips(builder, display, player);
        this.addUnitComponentToTooltip(DataComponents.INTANGIBLE_PROJECTILE, INTANGIBLE_TOOLTIP, display, builder);
        this.addUnitComponentToTooltip(DataComponents.UNBREAKABLE, UNBREAKABLE_TOOLTIP, display, builder);
        this.addToTooltip(DataComponents.OMINOUS_BOTTLE_AMPLIFIER, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.SUSPICIOUS_STEW_EFFECTS, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.BLOCK_STATE, context, display, builder, tooltipFlag);
        this.addToTooltip(DataComponents.ENTITY_DATA, context, display, builder, tooltipFlag);
        if ((this.is(Items.SPAWNER) || this.is(Items.TRIAL_SPAWNER)) && display.shows(DataComponents.BLOCK_ENTITY_DATA)) {
            TypedEntityData<BlockEntityType<?>> blockEntityData = this.get(DataComponents.BLOCK_ENTITY_DATA);
            Spawner.appendHoverText(blockEntityData, builder, "SpawnData");
        }

        AdventureModePredicate canBreak = this.get(DataComponents.CAN_BREAK);
        if (canBreak != null && display.shows(DataComponents.CAN_BREAK)) {
            builder.accept(CommonComponents.EMPTY);
            builder.accept(AdventureModePredicate.CAN_BREAK_HEADER);
            canBreak.addToTooltip(builder);
        }

        AdventureModePredicate canPlaceOn = this.get(DataComponents.CAN_PLACE_ON);
        if (canPlaceOn != null && display.shows(DataComponents.CAN_PLACE_ON)) {
            builder.accept(CommonComponents.EMPTY);
            builder.accept(AdventureModePredicate.CAN_PLACE_HEADER);
            canPlaceOn.addToTooltip(builder);
        }

        if (tooltipFlag.isAdvanced()) {
            if (this.isDamaged() && display.shows(DataComponents.DAMAGE)) {
                builder.accept(Component.translatable("item.durability", this.getMaxDamage() - this.getDamageValue(), this.getMaxDamage()));
            }

            builder.accept(Component.literal(BuiltInRegistries.ITEM.getKey(this.getItem()).toString()).withStyle(ChatFormatting.DARK_GRAY));
            int count = this.components.size();
            if (count > 0) {
                builder.accept(Component.translatable("item.components", count).withStyle(ChatFormatting.DARK_GRAY));
            }
        }

        if (player != null && !this.getItem().isEnabled(player.level().enabledFeatures())) {
            builder.accept(DISABLED_ITEM_TOOLTIP);
        }

        boolean shouldPrintOpWarning = this.getItem().shouldPrintOpWarning(this, player);
        if (shouldPrintOpWarning) {
            OP_NBT_WARNING.forEach(builder);
        }
    }

    private void addUnitComponentToTooltip(
        final DataComponentType<?> dataComponentType, final Component component, final TooltipDisplay display, final Consumer<Component> builder
    ) {
        if (this.has(dataComponentType) && display.shows(dataComponentType)) {
            builder.accept(component);
        }
    }

    private void addAttributeTooltips(final Consumer<Component> consumer, final TooltipDisplay display, final @Nullable Player player) {
        if (display.shows(DataComponents.ATTRIBUTE_MODIFIERS)) {
            for (EquipmentSlotGroup slot : EquipmentSlotGroup.values()) {
                MutableBoolean first = new MutableBoolean(true);
                this.forEachModifier(slot, (attribute, modifier, tooltip) -> {
                    if (tooltip != ItemAttributeModifiers.Display.hidden()) {
                        if (first.isTrue()) {
                            consumer.accept(CommonComponents.EMPTY);
                            consumer.accept(Component.translatable("item.modifiers." + slot.getSerializedName()).withStyle(ChatFormatting.GRAY));
                            first.setFalse();
                        }

                        tooltip.apply(consumer, player, attribute, modifier);
                    }
                });
            }
        }
    }

    public boolean hasFoil() {
        Boolean enchantmentGlintOverride = this.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        return enchantmentGlintOverride != null ? enchantmentGlintOverride : this.getItem().isFoil(this);
    }

    public Rarity getRarity() {
        Rarity baseRarity = this.getOrDefault(DataComponents.RARITY, Rarity.COMMON);
        if (!this.isEnchanted()) {
            return baseRarity;
        }

        return switch (baseRarity) {
            case COMMON, UNCOMMON -> Rarity.RARE;
            case RARE -> Rarity.EPIC;
            default -> baseRarity;
        };
    }

    public boolean isEnchantable() {
        if (!this.has(DataComponents.ENCHANTABLE)) {
            return false;
        }

        ItemEnchantments enchantments = this.get(DataComponents.ENCHANTMENTS);
        return enchantments != null && enchantments.isEmpty();
    }

    public void enchant(final Holder<Enchantment> enchantment, final int level) {
        EnchantmentHelper.updateEnchantments(this, enchantments -> enchantments.upgrade(enchantment, level));
    }

    public boolean isEnchanted() {
        return !this.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty();
    }

    public ItemEnchantments getEnchantments() {
        return this.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    }

    public void forEachModifier(final EquipmentSlotGroup slot, final TriConsumer<Holder<Attribute>, AttributeModifier, ItemAttributeModifiers.Display> consumer) {
        ItemAttributeModifiers modifiers = this.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        modifiers.forEach(slot, consumer);
        EnchantmentHelper.forEachModifier(this, slot, (a, b) -> consumer.accept(a, b, ItemAttributeModifiers.Display.attributeModifiers()));
    }

    public void forEachModifier(final EquipmentSlot slot, final BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        ItemAttributeModifiers modifiers = this.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        modifiers.forEach(slot, consumer);
        EnchantmentHelper.forEachModifier(this, slot, consumer);
    }

    // CraftBukkit start
    @Deprecated
    public void setItem(Item item) {
        this.bukkitStack = null;
        this.item = item.builtInRegistryHolder();
        // Paper start - change base component prototype
        final DataComponentPatch patch = this.getComponentsPatch();
        this.components = new PatchedDataComponentMap(this.item.components());
        this.applyComponents(patch);
        // Paper end - change base component prototype
    }
    // CraftBukkit end

    public Component getDisplayName() {
        MutableComponent hoverName = Component.empty().append(this.getHoverName());
        if (this.has(DataComponents.CUSTOM_NAME)) {
            hoverName.withStyle(ChatFormatting.ITALIC);
        }

        MutableComponent result = ComponentUtils.wrapInSquareBrackets(hoverName);
        if (!this.isEmpty()) {
            result.withStyle(this.getRarity().color()).withStyle(s -> s.withHoverEvent(new HoverEvent.ShowItem(ItemStackTemplate.fromNonEmptyStack(this))));
        }

        return result;
    }

    public SwingAnimation getSwingAnimation() {
        return this.getOrDefault(DataComponents.SWING_ANIMATION, SwingAnimation.DEFAULT);
    }

    public boolean canPlaceOnBlockInAdventureMode(final BlockInWorld blockInWorld) {
        AdventureModePredicate canPlaceOn = this.get(DataComponents.CAN_PLACE_ON);
        return canPlaceOn != null && canPlaceOn.test(blockInWorld);
    }

    public boolean canBreakBlockInAdventureMode(final BlockInWorld blockInWorld) {
        AdventureModePredicate canBreak = this.get(DataComponents.CAN_BREAK);
        return canBreak != null && canBreak.test(blockInWorld);
    }

    public int getPopTime() {
        return this.popTime;
    }

    public void setPopTime(final int popTime) {
        this.popTime = popTime;
    }

    public int getCount() {
        return this.isEmpty() ? 0 : this.count;
    }

    @Override
    public int count() {
        return this.getCount();
    }

    public void setCount(final int count) {
        this.count = count;
    }

    public void limitSize(final int maxStackSize) {
        if (!this.isEmpty() && this.getCount() > maxStackSize) {
            this.setCount(maxStackSize);
        }
    }

    public void grow(final int amount) {
        this.setCount(this.getCount() + amount);
    }

    public void shrink(final int amount) {
        this.grow(-amount);
    }

    public void consume(final int amount, final @Nullable LivingEntity owner) {
        if ((owner == null || !owner.hasInfiniteMaterials())) {
            this.shrink(amount);
        }
    }

    public ItemStack consumeAndReturn(final int amount, final @Nullable LivingEntity owner) {
        ItemStack split = this.copyWithCount(amount);
        this.consume(amount, owner);
        return split;
    }

    public void onUseTick(final Level level, final LivingEntity livingEntity, final int ticksRemaining) {
        Consumable consumable = this.get(DataComponents.CONSUMABLE);
        if (consumable != null && consumable.shouldEmitParticlesAndSounds(ticksRemaining)) {
            consumable.emitParticlesAndSounds(livingEntity.getRandom(), livingEntity, this, 5);
        }

        KineticWeapon kineticWeapon = this.get(DataComponents.KINETIC_WEAPON);
        if (kineticWeapon != null && !level.isClientSide()) {
            kineticWeapon.damageEntities(this, ticksRemaining, livingEntity, livingEntity.getUsedItemHand().asEquipmentSlot());
        } else {
            this.getItem().onUseTick(level, livingEntity, this, ticksRemaining);
        }
    }

    public void onDestroyed(final ItemEntity itemEntity) {
        this.getItem().onDestroyed(itemEntity);
    }

    public boolean canBeHurtBy(final DamageSource source) {
        DamageResistant damageResistant = this.get(DataComponents.DAMAGE_RESISTANT);
        return damageResistant == null || !damageResistant.isResistantTo(source);
    }

    public boolean isValidRepairItem(final ItemStack repairItem) {
        Repairable repairable = this.get(DataComponents.REPAIRABLE);
        return repairable != null && repairable.isValidRepairItem(repairItem);
    }

    public boolean canDestroyBlock(final BlockState state, final Level level, final BlockPos pos, final Player player) {
        return this.getItem().canDestroyBlock(this, state, level, pos, player);
    }

    public DamageSource getDamageSource(final LivingEntity attacker) {
        return Optional.ofNullable(this.get(DataComponents.DAMAGE_TYPE))
            .map(type -> new DamageSource((Holder<DamageType>)type, attacker))
            .or(() -> Optional.ofNullable(this.getItem().getItemDamageSource(attacker)))
            .orElseGet(attacker::createDamageSource);
    }
}
