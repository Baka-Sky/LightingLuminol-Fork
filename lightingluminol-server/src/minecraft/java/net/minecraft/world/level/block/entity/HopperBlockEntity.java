package net.minecraft.world.level.block.entity;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

public class HopperBlockEntity extends RandomizableContainerBlockEntity implements Hopper {
    public static final int MOVE_ITEM_SPEED = 8;
    public static final int HOPPER_CONTAINER_SIZE = 5;
    private static final int[][] CACHED_SLOTS = new int[54][];
    private static final int NO_COOLDOWN_TIME = -1;
    private static final Component DEFAULT_NAME = Component.translatable("container.hopper");
    private NonNullList<ItemStack> items = NonNullList.withSize(5, ItemStack.EMPTY);
    public int cooldownTime = -1;
    private long tickedGameTime = Long.MIN_VALUE; // Folia - region threading
    private Direction facing;

    // CraftBukkit start - add fields and methods
    public List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = MAX_STACK;

    @Override
    public List<ItemStack> getContents() {
        return this.items;
    }

    @Override
    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.add(player);
    }

    @Override
    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.transaction.remove(player);
    }

    @Override
    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    // CraftBukkit end

    // Folia start - region threading
    @Override
    public void updateTicks(final long fromTickOffset, final long fromRedstoneTimeOffset) {
        super.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
        if (this.tickedGameTime != Long.MIN_VALUE) {
            this.tickedGameTime += fromRedstoneTimeOffset;
        }
    }
    // Folia end - region threading

    public HopperBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        super(BlockEntityTypes.HOPPER, worldPosition, blockState);
        this.facing = blockState.getValue(HopperBlock.FACING);
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        if (!this.tryLoadLootTable(input)) {
            ContainerHelper.loadAllItems(input, this.items);
        }

        this.cooldownTime = input.getIntOr("TransferCooldown", -1);
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        if (!this.trySaveLootTable(output)) {
            ContainerHelper.saveAllItems(output, this.items);
        }

        output.putInt("TransferCooldown", this.cooldownTime);
    }

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    public ItemStack removeItem(final int slot, final int count) {
        this.unpackLootTable(null);
        return ContainerHelper.removeItem(this.getItems(), slot, count);
    }

    @Override
    public void setItem(final int slot, final ItemStack itemStack) {
        this.unpackLootTable(null);
        this.getItems().set(slot, itemStack);
        itemStack.limitSize(this.getMaxStackSize(itemStack));
    }

    @Override
    public void setBlockState(final BlockState blockState) {
        super.setBlockState(blockState);
        this.facing = blockState.getValue(HopperBlock.FACING);
    }

    @Override
    protected Component getDefaultName() {
        return DEFAULT_NAME;
    }

    public static void pushItemsTick(final Level level, final BlockPos pos, final BlockState state, final HopperBlockEntity entity) {
        entity.cooldownTime--;
        entity.tickedGameTime = level.getRedstoneGameTime(); // Folia - region threading
        if (!entity.isOnCooldown()) {
            entity.setCooldown(0);
            // Spigot start
            boolean result = tryMoveItems(level, pos, state, entity, () -> suckInItems(level, entity));
            if (!result && entity.level.spigotConfig.hopperCheck > 1) {
                entity.setCooldown(entity.level.spigotConfig.hopperCheck);
            }
            // Spigot end
        }
    }

    // Paper start - Perf: Optimize Hoppers
    private static final int HOPPER_EMPTY = 0;
    private static final int HOPPER_HAS_ITEMS = 1;
    private static final int HOPPER_IS_FULL = 2;

    private static int getFullState(final HopperBlockEntity hopper) {
        hopper.unpackLootTable(null);

        final List<ItemStack> hopperItems = hopper.items;

        boolean empty = true;
        boolean full = true;

        for (int i = 0, len = hopperItems.size(); i < len; ++i) {
            final ItemStack stack = hopperItems.get(i);
            if (stack.isEmpty()) {
                full = false;
                continue;
            }

            if (!full) {
                // can't be full
                return HOPPER_HAS_ITEMS;
            }

            empty = false;

            if (stack.getCount() != stack.getMaxStackSize()) {
                // can't be full or empty
                return HOPPER_HAS_ITEMS;
            }
        }

        return empty ? HOPPER_EMPTY : (full ? HOPPER_IS_FULL : HOPPER_HAS_ITEMS);
    }
    // Paper end - Perf: Optimize Hoppers

    private static boolean tryMoveItems(
        final Level level, final BlockPos pos, final BlockState state, final HopperBlockEntity entity, final BooleanSupplier action
    ) {
        if (level.isClientSide()) {
            return false;
        }

        if (!entity.isOnCooldown() && state.getValue(HopperBlock.ENABLED)) {
            boolean changed = false;
            final int fullState = getFullState(entity); // Paper - Perf: Optimize Hoppers
            if (fullState != HOPPER_EMPTY) { // Paper - Perf: Optimize Hoppers
                changed = ejectItems(level, pos, entity);
            }

            if (changed || fullState != HOPPER_IS_FULL) { // Paper - Perf: Optimize Hoppers
                changed |= action.getAsBoolean();
            }

            if (changed) {
                entity.setCooldown(level.spigotConfig.hopperTransfer); // Spigot
                setChanged(level, pos, state);
                return true;
            }
        }

        return false;
    }

    private boolean inventoryFull() {
        for (ItemStack itemStack : this.items) {
            if (itemStack.isEmpty() || itemStack.getCount() != itemStack.getMaxStackSize()) {
                return false;
            }
        }

        return true;
    }

    // Paper start - Perf: Optimize Hoppers
    // Folia - region threading - moved to RegionizedWorldData

    private static boolean hopperPush(final Level level, final Container destination, final Direction direction, final HopperBlockEntity hopper) {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
        worldData.skipPushModeEventFire = worldData.skipHopperEvents; // Folia - region threading
        boolean foundItem = false;
        for (int i = 0; i < hopper.getContainerSize(); ++i) {
            final ItemStack item = hopper.getItem(i);
            if (!item.isEmpty()) {
                foundItem = true;
                ItemStack origItemStack = item;
                ItemStack movedItem = origItemStack;

                final int originalItemCount = origItemStack.getCount();
                final int movedItemCount = Math.min(level.spigotConfig.hopperAmount, originalItemCount);
                origItemStack.setCount(movedItemCount);

                // We only need to fire the event once to give protection plugins a chance to cancel this event
                // Because nothing uses getItem, every event call should end up the same result.
                if (!worldData.skipPushModeEventFire) { // Folia - region threading
                    movedItem = callPushMoveEvent(destination, movedItem, hopper);
                    if (movedItem == null) { // cancelled
                        origItemStack.setCount(originalItemCount);
                        return false;
                    }
                }

                final ItemStack remainingItem = addItem(hopper, destination, movedItem, direction);
                final int remainingItemCount = remainingItem.getCount();
                if (remainingItemCount != movedItemCount) {
                    origItemStack = origItemStack.copy(true);
                    origItemStack.setCount(originalItemCount);
                    if (!origItemStack.isEmpty()) {
                        origItemStack.setCount(originalItemCount - movedItemCount + remainingItemCount);
                    }
                    hopper.setItem(i, origItemStack);
                    destination.setChanged();
                    return true;
                }
                origItemStack.setCount(originalItemCount);
            }
        }
        if (foundItem && level.paperConfig().hopper.cooldownWhenFull) { // Inventory was full - cooldown
            hopper.setCooldown(level.spigotConfig.hopperTransfer);
        }
        return false;
    }

    private static boolean hopperPull(final Level level, final Hopper hopper, final Container container, ItemStack origItemStack, final int i) {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = level.getCurrentWorldData(); // Folia - region threading
        ItemStack movedItem = origItemStack;
        final int originalItemCount = origItemStack.getCount();
        final int movedItemCount = Math.min(level.spigotConfig.hopperAmount, originalItemCount);
        container.setChanged(); // original logic always marks source inv as changed even if no move happens.
        movedItem.setCount(movedItemCount);

        if (!worldData.skipPullModeEventFire) { // Folia - region threading
            movedItem = callPullMoveEvent(hopper, container, movedItem);
            if (movedItem == null) { // cancelled
                origItemStack.setCount(originalItemCount);
                // Drastically improve performance by returning true.
                // No plugin could have relied on the behavior of false as the other call
                // site for IMIE did not exhibit the same behavior
                return true;
            }
        }

        final ItemStack remainingItem = addItem(container, hopper, movedItem, null);
        final int remainingItemCount = remainingItem.getCount();
        if (remainingItemCount != movedItemCount) {
            origItemStack = origItemStack.copy(true);
            origItemStack.setCount(originalItemCount);
            if (!origItemStack.isEmpty()) {
                origItemStack.setCount(originalItemCount - movedItemCount + remainingItemCount);
            }

            IGNORE_TILE_UPDATES.set(true); // Folia - region threading
            container.setItem(i, origItemStack);
            IGNORE_TILE_UPDATES.set(false); // Folia - region threading
            container.setChanged();
            return true;
        }
        origItemStack.setCount(originalItemCount);

        if (level.paperConfig().hopper.cooldownWhenFull) {
            applyCooldown(hopper);
        }

        return false;
    }

    @Nullable
    private static ItemStack callPushMoveEvent(Container destination, ItemStack itemStack, HopperBlockEntity hopper) {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData(); // Folia - region threading
        final org.bukkit.inventory.Inventory destinationInventory = getInventory(destination);
        final io.papermc.paper.event.inventory.PaperInventoryMoveItemEvent event = new io.papermc.paper.event.inventory.PaperInventoryMoveItemEvent(
            hopper.getOwner(false).getInventory(),
            org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack),
            destinationInventory,
            true
        );
        final boolean result = event.callEvent();
        if (!event.calledGetItem && !event.calledSetItem) {
            worldData.skipPushModeEventFire = true; // Folia - region threading
        }
        if (!result) {
            applyCooldown(hopper);
            return null;
        }

        if (event.calledSetItem) {
            return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
        } else {
            return itemStack;
        }
    }

    @Nullable
    private static ItemStack callPullMoveEvent(final Hopper hopper, final Container container, final ItemStack itemstack) {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData(); // Folia - region threading
        final org.bukkit.inventory.Inventory sourceInventory = getInventory(container);
        final org.bukkit.inventory.Inventory destination = getInventory(hopper);

        // Mirror is safe as no plugins ever use this item
        final io.papermc.paper.event.inventory.PaperInventoryMoveItemEvent event = new io.papermc.paper.event.inventory.PaperInventoryMoveItemEvent(sourceInventory, org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemstack), destination, false);
        final boolean result = event.callEvent();
        if (!event.calledGetItem && !event.calledSetItem) {
            worldData.skipPullModeEventFire = true; // Folia - region threading
        }
        if (!result) {
            applyCooldown(hopper);
            return null;
        }

        if (event.calledSetItem) {
            return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItem());
        } else {
            return itemstack;
        }
    }

    private static org.bukkit.inventory.Inventory getInventory(final Container container) {
        final org.bukkit.inventory.Inventory sourceInventory;
        if (container instanceof net.minecraft.world.CompoundContainer compoundContainer) {
            // Have to special-case large chests as they work oddly
            sourceInventory = new org.bukkit.craftbukkit.inventory.CraftInventoryDoubleChest(compoundContainer);
        } else if (container instanceof BlockEntity blockEntity) {
            sourceInventory = blockEntity.getOwner(false).getInventory();
        } else if (container.getOwner() != null) {
            sourceInventory = container.getOwner().getInventory();
        } else {
            sourceInventory = new org.bukkit.craftbukkit.inventory.CraftInventory(container);
        }
        return sourceInventory;
    }

    private static void applyCooldown(final Hopper hopper) {
        if (hopper instanceof HopperBlockEntity blockEntity && blockEntity.getLevel() != null) {
            blockEntity.setCooldown(blockEntity.getLevel().spigotConfig.hopperTransfer);
        }
    }

    private static boolean allMatch(Container container, Direction direction, java.util.function.BiPredicate<ItemStack, Integer> test) {
        if (container instanceof WorldlyContainer worldlyContainer) {
            for (int slot : worldlyContainer.getSlotsForFace(direction)) {
                if (!test.test(container.getItem(slot), slot)) {
                    return false;
                }
            }
        } else {
            int size = container.getContainerSize();
            for (int slot = 0; slot < size; slot++) {
                if (!test.test(container.getItem(slot), slot)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean anyMatch(Container container, Direction direction, java.util.function.BiPredicate<ItemStack, Integer> test) {
        if (container instanceof WorldlyContainer worldlyContainer) {
            for (int slot : worldlyContainer.getSlotsForFace(direction)) {
                if (test.test(container.getItem(slot), slot)) {
                    return true;
                }
            }
        } else {
            int size = container.getContainerSize();
            for (int slot = 0; slot < size; slot++) {
                if (test.test(container.getItem(slot), slot)) {
                    return true;
                }
            }
        }
        return true;
    }
    private static final java.util.function.BiPredicate<ItemStack, Integer> STACK_SIZE_TEST = (itemStack, _) -> itemStack.getCount() >= itemStack.getMaxStackSize();
    private static final java.util.function.BiPredicate<ItemStack, Integer> IS_EMPTY_TEST = (itemStack, _) -> itemStack.isEmpty();
    // Paper end - Perf: Optimize Hoppers

    private static boolean ejectItems(final Level level, final BlockPos blockPos, final HopperBlockEntity self) {
        Container container = getAttachedContainer(level, blockPos, self);
        if (container == null) {
            return false;
        }

        Direction direction = self.facing.getOpposite();
        if (isFullContainer(container, direction)) {
            return false;
        }

        return hopperPush(level, container, direction, self); // Paper - Perf: Optimize Hoppers
    }

    private static int[] getSlots(final Container container, final Direction direction) {
        if (container instanceof WorldlyContainer worldlyContainer) {
            return worldlyContainer.getSlotsForFace(direction);
        } else {
            int containerSize = container.getContainerSize();
            if (containerSize < CACHED_SLOTS.length) {
                int[] cachedSlots = CACHED_SLOTS[containerSize];
                if (cachedSlots != null) {
                    return cachedSlots;
                }

                int[] slots = createFlatSlots(containerSize);
                CACHED_SLOTS[containerSize] = slots;
                return slots;
            } else {
                return createFlatSlots(containerSize);
            }
        }
    }

    private static int[] createFlatSlots(final int containerSize) {
        int[] slots = new int[containerSize];
        int i = 0;

        while (i < slots.length) {
            slots[i] = i++;
        }

        return slots;
    }

    private static boolean isFullContainer(final Container container, final Direction direction) {
        int[] slots = getSlots(container, direction);

        for (int slot : slots) {
            ItemStack itemStack = container.getItem(slot);
            if (itemStack.getCount() < itemStack.getMaxStackSize()) {
                return false;
            }
        }

        return true;
    }

    public static boolean suckInItems(final Level level, final Hopper hopper) {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData(); // Folia - region threading
        BlockPos blockPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        BlockState blockState = level.getBlockState(blockPos);
        Container container = getSourceContainer(level, hopper, blockPos, blockState);
        if (container != null) {
            Direction direction = Direction.DOWN;
            worldData.skipPullModeEventFire = worldData.skipHopperEvents; // Paper - Perf: Optimize Hoppers // Folia - region threading

            for (int slot : getSlots(container, direction)) {
                if (tryTakeInItemFromSlot(hopper, container, slot, direction, level)) { // Spigot
                    return true;
                }
            }

            return false;
        } else {
            boolean isBlocked = hopper.isGridAligned()
                && blockState.isCollisionShapeFullBlock(level, blockPos)
                && !blockState.is(BlockTags.DOES_NOT_BLOCK_HOPPERS);
            if (!isBlocked) {
                for (ItemEntity entity : getItemsAtAndAbove(level, hopper)) {
                    if (addItem(hopper, entity)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    private static boolean tryTakeInItemFromSlot(final Hopper hopper, final Container container, final int slot, final Direction direction, final Level level) { // Spigot
        ItemStack itemStack = container.getItem(slot);
        if (!itemStack.isEmpty() && canTakeItemFromContainer(hopper, container, itemStack, slot, direction)) {
            return hopperPull(level, hopper, container, itemStack, slot); // Paper - Perf: Optimize Hoppers
        }

        return false;
    }

    public static boolean addItem(final Container container, final ItemEntity entity) {
        boolean changed = false;
        // CraftBukkit start
        if (org.bukkit.event.inventory.InventoryPickupItemEvent.getHandlerList().getRegisteredListeners().length > 0) { // Paper - optimize hoppers
        org.bukkit.event.inventory.InventoryPickupItemEvent event = new org.bukkit.event.inventory.InventoryPickupItemEvent(
            getInventory(container), (org.bukkit.entity.Item) entity.getBukkitEntity() // Paper - Perf: Optimize Hoppers; use getInventory() to avoid snapshot creation
        );
        if (!event.callEvent()) {
            return false;
        }
        // CraftBukkit end
        } // Paper - Perf: Optimize Hoppers
        ItemStack copy = entity.getItem().copy();
        ItemStack result = addItem(null, container, copy, null);
        if (result.isEmpty()) {
            changed = true;
            entity.setItem(ItemStack.EMPTY);
            entity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
        } else {
            entity.setItem(result);
        }

        return changed;
    }

    public static ItemStack addItem(final @Nullable Container from, final Container container, ItemStack itemStack, final @Nullable Direction direction) {
        if (container instanceof WorldlyContainer worldly && direction != null) {
            int[] slots = worldly.getSlotsForFace(direction);

            for (int i = 0; i < slots.length && !itemStack.isEmpty(); i++) {
                itemStack = tryMoveInItem(from, container, itemStack, slots[i], direction);
            }
        } else {
            int size = container.getContainerSize();

            for (int i = 0; i < size && !itemStack.isEmpty(); i++) {
                itemStack = tryMoveInItem(from, container, itemStack, i, direction);
            }
        }

        return itemStack;
    }

    private static boolean canPlaceItemInContainer(final Container container, final ItemStack itemStack, final int slot, final @Nullable Direction direction) {
        return container.canPlaceItem(slot, itemStack)
            && !(container instanceof WorldlyContainer worldly && !worldly.canPlaceItemThroughFace(slot, itemStack, direction));
    }

    private static boolean canTakeItemFromContainer(
        final Container into, final Container from, final ItemStack itemStack, final int slot, final Direction direction
    ) {
        return from.canTakeItem(into, slot, itemStack)
            && !(from instanceof WorldlyContainer worldly && !worldly.canTakeItemThroughFace(slot, itemStack, direction));
    }

    private static ItemStack tryMoveInItem(
        final @Nullable Container from, final Container container, ItemStack itemStack, final int slot, final @Nullable Direction direction
    ) {
        ItemStack current = container.getItem(slot);
        if (canPlaceItemInContainer(container, itemStack, slot, direction)) {
            boolean success = false;
            boolean wasEmpty = container.isEmpty();
            if (current.isEmpty()) {
                // Spigot start - SPIGOT-6693, SimpleContainer#setItem
                ItemStack leftover = ItemStack.EMPTY; // Paper - Make hoppers respect inventory max stack size
                if (!itemStack.isEmpty() && itemStack.getCount() > container.getMaxStackSize()) {
                    leftover = itemStack; // Paper - Make hoppers respect inventory max stack size
                    itemStack = itemStack.split(container.getMaxStackSize());
                }
                // Spigot end
                IGNORE_TILE_UPDATES.set(Boolean.TRUE); // Paper - Perf: Optimize Hoppers // Folia - region threading
                container.setItem(slot, itemStack);
                IGNORE_TILE_UPDATES.set(Boolean.FALSE); // Paper - Perf: Optimize Hoppers // Folia - region threading
                itemStack = leftover; // Paper - Make hoppers respect inventory max stack size
                success = true;
            } else if (canMergeItems(current, itemStack)) {
                int space = Math.min(itemStack.getMaxStackSize(), container.getMaxStackSize()) - current.getCount(); // Paper - Make hoppers respect inventory max stack size
                int count = Math.min(itemStack.getCount(), space);
                itemStack.shrink(count);
                current.grow(count);
                success = count > 0;
            }

            if (success) {
                if (wasEmpty && container instanceof HopperBlockEntity hopperBlockEntity && !hopperBlockEntity.isOnCustomCooldown()) {
                    int skipTickCount = 0;
                    if (from instanceof HopperBlockEntity fromHopper && hopperBlockEntity.tickedGameTime >= fromHopper.tickedGameTime) {
                        skipTickCount = 1;
                    }

                    hopperBlockEntity.setCooldown(hopperBlockEntity.level.spigotConfig.hopperTransfer - skipTickCount); // Spigot
                }

                container.setChanged();
            }
        }

        return itemStack;
    }

    // CraftBukkit start
    private static @Nullable Container runHopperInventorySearchEvent(
        Container container,
        org.bukkit.craftbukkit.block.CraftBlock hopper,
        org.bukkit.craftbukkit.block.CraftBlock searchLocation,
        org.bukkit.event.inventory.HopperInventorySearchEvent.ContainerType containerType
    ) {
        org.bukkit.event.inventory.HopperInventorySearchEvent event = new org.bukkit.event.inventory.HopperInventorySearchEvent(
            (container != null) ? new org.bukkit.craftbukkit.inventory.CraftInventory(container) : null,
            containerType,
            hopper,
            searchLocation
        );
        event.callEvent();
        return (event.getInventory() != null) ? ((org.bukkit.craftbukkit.inventory.CraftInventory) event.getInventory()).getInventory() : null;
    }
    // CraftBukkit end

    private static @Nullable Container getAttachedContainer(final Level level, final BlockPos blockPos, final HopperBlockEntity self) {
        // Paper start
        BlockPos searchPosition = blockPos.relative(self.facing);
        Container inventory = getContainerAt(level, searchPosition);
        if (org.bukkit.event.inventory.HopperInventorySearchEvent.getHandlerList().getRegisteredListeners().length == 0) return inventory;

        org.bukkit.craftbukkit.block.CraftBlock hopper = org.bukkit.craftbukkit.block.CraftBlock.at(level, blockPos);
        org.bukkit.craftbukkit.block.CraftBlock searchBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, searchPosition);
        return HopperBlockEntity.runHopperInventorySearchEvent(
            inventory,
            hopper,
            searchBlock,
            org.bukkit.event.inventory.HopperInventorySearchEvent.ContainerType.DESTINATION
        );
        // Paper end
    }

    private static @Nullable Container getSourceContainer(final Level level, final Hopper hopper, final BlockPos pos, final BlockState state) {
        // Paper start
        final Container inventory = HopperBlockEntity.getContainerAt(level, pos, state, hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        if (org.bukkit.event.inventory.HopperInventorySearchEvent.getHandlerList().getRegisteredListeners().length == 0) return inventory;

        final BlockPos hopperPos = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY(), hopper.getLevelZ());
        org.bukkit.craftbukkit.block.CraftBlock hopperBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, hopperPos);
        org.bukkit.craftbukkit.block.CraftBlock containerBlock = org.bukkit.craftbukkit.block.CraftBlock.at(level, hopperPos.above());
        return HopperBlockEntity.runHopperInventorySearchEvent(
            inventory,
            hopperBlock,
            containerBlock,
            org.bukkit.event.inventory.HopperInventorySearchEvent.ContainerType.SOURCE
        );
        // Paper end
    }

    public static List<ItemEntity> getItemsAtAndAbove(final Level level, final Hopper hopper) {
        AABB aabb = hopper.getSuckAabb().move(hopper.getLevelX() - 0.5, hopper.getLevelY() - 0.5, hopper.getLevelZ() - 0.5);
        return level.getEntitiesOfClass(ItemEntity.class, aabb, EntitySelector.ENTITY_STILL_ALIVE);
    }

    public static @Nullable Container getContainerAt(final Level level, final BlockPos pos) {
        return getContainerAt(level, pos, level.getBlockState(pos), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, true); // Paper - Optimize hoppers
    }

    private static @Nullable Container getContainerAt(
        final Level level, final BlockPos pos, final BlockState state, final double x, final double y, final double z
    ) {
        // Paper start - Perf: Optimize Hoppers
        return getContainerAt(level, pos, state, x, y, z, false);
    }
    private static @Nullable Container getContainerAt(
        final Level level, final BlockPos pos, final BlockState state, final double x, final double y, final double z,
        final boolean optimizeEntities
    ) {
        // Paper end - Perf: Optimize Hoppers
        Container result = getBlockContainer(level, pos, state);
        if (result == null && (!optimizeEntities || !level.paperConfig().hopper.ignoreOccludingBlocks || !state.getBukkitMaterial().isOccluding())) { // Paper - Perf: Optimize Hoppers
            result = getEntityContainer(level, x, y, z);
        }

        return result;
    }

    private static @Nullable Container getBlockContainer(final Level level, final BlockPos pos, final BlockState state) {
        if (!level.spigotConfig.hopperCanLoadChunks && !level.hasChunkAt(pos)) return null; // Spigot
        Block block = state.getBlock();
        if (block instanceof WorldlyContainerHolder worldlyContainerHolder) {
            return worldlyContainerHolder.getContainer(state, level, pos);
        } else if (state.hasBlockEntity() && level.getBlockEntity(pos) instanceof Container container) {
            if (container instanceof ChestBlockEntity && block instanceof ChestBlock chestBlock) {
                container = ChestBlock.getContainer(chestBlock, state, level, pos, true);
            }

            return container;
        } else {
            return null;
        }
    }

    private static @Nullable Container getEntityContainer(final Level level, final double x, final double y, final double z) {
        List<Entity> entities = level.getEntitiesOfClass( // Paper - Perf: Optimize hoppers
            (Class) Container.class, new AABB(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5), EntitySelector.CONTAINER_ENTITY_SELECTOR // Paper - Perf: Optimize hoppers
        );
        return !entities.isEmpty() ? (Container)entities.get(level.getRandom().nextInt(entities.size())) : null;
    }

    private static boolean canMergeItems(final ItemStack a, final ItemStack b) {
        return a.getCount() < a.getMaxStackSize() && ItemStack.isSameItemSameComponents(a, b); // Paper - Perf: Optimize Hoppers; used to return true for full itemstacks?!
    }

    @Override
    public double getLevelX() {
        return this.worldPosition.getX() + 0.5;
    }

    @Override
    public double getLevelY() {
        return this.worldPosition.getY() + 0.5;
    }

    @Override
    public double getLevelZ() {
        return this.worldPosition.getZ() + 0.5;
    }

    @Override
    public boolean isGridAligned() {
        return true;
    }

    public void setCooldown(final int time) {
        this.cooldownTime = time;
    }

    private boolean isOnCooldown() {
        return this.cooldownTime > 0;
    }

    private boolean isOnCustomCooldown() {
        return this.cooldownTime > 8;
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(final NonNullList<ItemStack> items) {
        this.items = items;
    }

    public static void entityInside(final Level level, final BlockPos pos, final BlockState blockState, final Entity entity, final HopperBlockEntity hopper) {
        if (entity instanceof ItemEntity itemEntity
            && !itemEntity.getItem().isEmpty()
            && entity.getBoundingBox().move(-pos.getX(), -pos.getY(), -pos.getZ()).intersects(hopper.getSuckAabb())) {
            tryMoveItems(level, pos, blockState, hopper, () -> addItem(hopper, itemEntity));
        }
    }

    @Override
    protected AbstractContainerMenu createMenu(final int containerId, final Inventory inventory) {
        return new HopperMenu(containerId, inventory, this);
    }
}
