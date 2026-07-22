package net.minecraft.world.inventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jspecify.annotations.Nullable;

public class ResultContainer implements Container, RecipeCraftingHolder {
    private final NonNullList<ItemStack> itemStacks = NonNullList.withSize(1, ItemStack.EMPTY);
    private @Nullable RecipeHolder<?> recipeUsed;
    // CraftBukkit start
    private int maxStack = MAX_STACK;

    @Override
    public java.util.List<ItemStack> getContents() {
        return this.itemStacks;
    }

    @Override
    public org.bukkit.inventory.@Nullable InventoryHolder getOwner() {
        // Paper start - Add missing InventoryHolders
        if (this.holder == null && this.holderCreator != null) {
            this.holder = this.holderCreator.get();
        }
        return this.holder; // Result slots don't get an owner
        // Paper end - Add missing InventoryHolders
    }

    // Don't need a transaction; the CraftingContainer keeps track of it for us
    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {}
    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {}
    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return new java.util.ArrayList<>();
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    @Override
    public org.bukkit.@Nullable Location getLocation() {
        return null;
    }
    // CraftBukkit end
    // Paper start - Add missing InventoryHolders
    private java.util.function.@Nullable Supplier<? extends org.bukkit.inventory.InventoryHolder> holderCreator;
    private org.bukkit.inventory.@Nullable InventoryHolder holder;
    public ResultContainer(java.util.function.Supplier<? extends org.bukkit.inventory.InventoryHolder> holderCreator) {
        this.holderCreator = holderCreator;
    }

    public ResultContainer() {
    }
    // Paper end - Add missing InventoryHolders

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack itemStack : this.itemStacks) {
            if (!itemStack.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public ItemStack getItem(final int slot) {
        return this.itemStacks.get(0);
    }

    @Override
    public ItemStack removeItem(final int slot, final int count) {
        return ContainerHelper.takeItem(this.itemStacks, 0);
    }

    @Override
    public ItemStack removeItemNoUpdate(final int slot) {
        return ContainerHelper.takeItem(this.itemStacks, 0);
    }

    @Override
    public void setItem(final int slot, final ItemStack itemStack) {
        this.itemStacks.set(0, itemStack);
    }

    @Override
    public void setChanged() {
    }

    @Override
    public boolean stillValid(final Player player) {
        return true;
    }

    @Override
    public void clearContent() {
        this.itemStacks.clear();
    }

    @Override
    public void setRecipeUsed(final @Nullable RecipeHolder<?> recipeUsed) {
        this.recipeUsed = recipeUsed;
    }

    @Override
    public @Nullable RecipeHolder<?> getRecipeUsed() {
        return this.recipeUsed;
    }
}
