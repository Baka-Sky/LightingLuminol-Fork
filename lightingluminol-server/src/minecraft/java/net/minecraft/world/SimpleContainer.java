package net.minecraft.world;

import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.StackedContentsCompatible;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class SimpleContainer implements Container, StackedContentsCompatible {
    private final int size;
    private final NonNullList<ItemStack> items;

    // Paper start - add fields and methods
    public List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = MAX_STACK;
    protected org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder bukkitOwner; // Paper - annotation

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
    public List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    @Override
    public org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder getOwner() {
        // Paper start - Add missing InventoryHolders
        if (this.bukkitOwner == null && this.bukkitOwnerCreator != null) {
            this.bukkitOwner = this.bukkitOwnerCreator.get();
        }
        // Paper end - Add missing InventoryHolders
        return this.bukkitOwner;
    }

    @Override
    public org.bukkit.@org.jspecify.annotations.Nullable Location getLocation() {
        // Paper start - Fix inventories returning null Locations
        // When the block inventory does not have a tile state that implements getLocation, e. g. composters
        if (this.bukkitOwner instanceof org.bukkit.inventory.BlockInventoryHolder blockInventoryHolder) {
            return blockInventoryHolder.getBlock().getLocation();
        }
        // When the bukkit owner is a bukkit entity, but does not implement Container itself, e. g. horses
        if (this.bukkitOwner instanceof org.bukkit.entity.Entity entity) {
            return entity.getLocation();
        }
        // Paper end - Fix inventories returning null Locations
        return null;
    }

    public SimpleContainer(SimpleContainer original) {
        this(original.size);
        for (int slot = 0; slot < original.size; slot++) {
            this.items.set(slot, original.items.get(slot).copy());
        }
    }
    // Paper end

    public SimpleContainer(final int size) {
        this(size, null);
    }

    // Paper start - Add missing InventoryHolders
    private java.util.function.@org.jspecify.annotations.Nullable Supplier<? extends org.bukkit.inventory.InventoryHolder> bukkitOwnerCreator;

    public SimpleContainer(java.util.function.Supplier<? extends org.bukkit.inventory.InventoryHolder> bukkitOwnerCreator, int size) {
        this(size);
        this.bukkitOwnerCreator = bukkitOwnerCreator;
    }
    // Paper end - Add missing InventoryHolders

    public SimpleContainer(final int size, final org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder owner) {
        this.bukkitOwner = owner;
        // Paper end
        this.size = size;
        this.items = NonNullList.withSize(size, ItemStack.EMPTY);
    }

    public SimpleContainer(final ItemStack... itemstacks) {
        this.size = itemstacks.length;
        this.items = NonNullList.of(ItemStack.EMPTY, itemstacks);
    }

    @Override
    public ItemStack getItem(final int slot) {
        return slot >= 0 && slot < this.items.size() ? this.items.get(slot) : ItemStack.EMPTY;
    }

    public List<ItemStack> removeAllItems() {
        List<ItemStack> itemsRemoved = this.items.stream().filter(item -> !item.isEmpty()).collect(Collectors.toList());
        this.clearContent();
        return itemsRemoved;
    }

    @Override
    public ItemStack removeItem(final int slot, final int count) {
        ItemStack result = ContainerHelper.removeItem(this.items, slot, count);
        if (!result.isEmpty()) {
            this.setChanged();
        }

        return result;
    }

    public ItemStack removeItemType(final Item itemType, final int count) {
        ItemStack removed = new ItemStack(itemType, 0);

        for (int slot = this.size - 1; slot >= 0; slot--) {
            ItemStack current = this.getItem(slot);
            if (current.getItem().equals(itemType)) {
                int stillNeeded = count - removed.getCount();
                ItemStack removedFromThisSlot = current.split(stillNeeded);
                removed.grow(removedFromThisSlot.getCount());
                if (removed.getCount() == count) {
                    break;
                }
            }
        }

        if (!removed.isEmpty()) {
            this.setChanged();
        }

        return removed;
    }

    public ItemStack addItem(final ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remainingItems = itemStack.copy();
        this.moveItemToOccupiedSlotsWithSameType(remainingItems);
        if (remainingItems.isEmpty()) {
            return ItemStack.EMPTY;
        }

        this.moveItemToEmptySlots(remainingItems);
        return remainingItems.isEmpty() ? ItemStack.EMPTY : remainingItems;
    }

    public boolean canAddItem(final ItemStack itemStack) {
        boolean hasSpace = false;

        for (ItemStack targetStack : this.items) {
            if (targetStack.isEmpty() || ItemStack.isSameItemSameComponents(targetStack, itemStack) && targetStack.getCount() < targetStack.getMaxStackSize()) {
                hasSpace = true;
                break;
            }
        }

        return hasSpace;
    }

    @Override
    public ItemStack removeItemNoUpdate(final int slot) {
        ItemStack itemStack = this.items.get(slot);
        if (itemStack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        this.items.set(slot, ItemStack.EMPTY);
        return itemStack;
    }

    @Override
    public void setItem(final int slot, final ItemStack itemStack) {
        this.items.set(slot, itemStack);
        itemStack.limitSize(this.getMaxStackSize(itemStack));
        this.setChanged();
    }

    @Override
    public void setChanged() {
    }

    @Override
    public int getContainerSize() {
        return this.size;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack itemStack : this.items) {
            if (!itemStack.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean stillValid(final Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.items.clear();
        this.setChanged();
    }

    @Override
    public void fillStackedContents(final StackedItemContents contents) {
        for (ItemStack itemStack : this.items) {
            contents.accountStack(itemStack);
        }
    }

    @Override
    public String toString() {
        return this.items.stream().filter(item -> !item.isEmpty()).toList().toString();
    }

    private void moveItemToEmptySlots(final ItemStack sourceStack) {
        for (int slot = 0; slot < this.size; slot++) {
            ItemStack targetStack = this.getItem(slot);
            if (targetStack.isEmpty()) {
                this.setItem(slot, sourceStack.copyAndClear());
                return;
            }
        }
    }

    private void moveItemToOccupiedSlotsWithSameType(final ItemStack sourceStack) {
        for (int slot = 0; slot < this.size; slot++) {
            ItemStack targetStack = this.getItem(slot);
            if (ItemStack.isSameItemSameComponents(targetStack, sourceStack)) {
                this.moveItemsBetweenStacks(sourceStack, targetStack);
                if (sourceStack.isEmpty()) {
                    return;
                }
            }
        }
    }

    private void moveItemsBetweenStacks(final ItemStack sourceStack, final ItemStack targetStack) {
        int maxCount = this.getMaxStackSize(targetStack);
        int diff = Math.min(sourceStack.getCount(), maxCount - targetStack.getCount());
        if (diff > 0) {
            targetStack.grow(diff);
            sourceStack.shrink(diff);
            this.setChanged();
        }
    }

    public void fromItemList(final ValueInput.TypedInputList<ItemStack> items) {
        this.clearContent();

        for (ItemStack stack : items) {
            this.addItem(stack);
        }
    }

    public void storeAsItemList(final ValueOutput.TypedOutputList<ItemStack> output) {
        for (int i = 0; i < this.getContainerSize(); i++) {
            ItemStack itemStack = this.getItem(i);
            if (!itemStack.isEmpty()) {
                output.add(itemStack);
            }
        }
    }

    public NonNullList<ItemStack> getItems() {
        return this.items;
    }
}
