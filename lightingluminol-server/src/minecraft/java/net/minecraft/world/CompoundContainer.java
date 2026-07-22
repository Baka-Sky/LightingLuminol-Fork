package net.minecraft.world;

import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class CompoundContainer implements Container {
    public final Container container1;
    public final Container container2;

    // CraftBukkit start - add fields and methods
    public java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();

    @Override
    public java.util.List<ItemStack> getContents() {
        java.util.List<ItemStack> result = new java.util.ArrayList<>(this.getContainerSize());
        for (int i = 0; i < this.getContainerSize(); i++) {
            result.add(this.getItem(i));
        }
        return result;
    }

    @Override
    public void onOpen(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.container1.onOpen(player);
        this.container2.onOpen(player);
        this.transaction.add(player);
    }

    @Override
    public void onClose(org.bukkit.craftbukkit.entity.CraftHumanEntity player) {
        this.container1.onClose(player);
        this.container2.onClose(player);
        this.transaction.remove(player);
    }

    @Override
    public java.util.List<org.bukkit.entity.HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public org.bukkit.inventory.@org.jspecify.annotations.Nullable InventoryHolder getOwner() {
        return null; // This method won't be called since CraftInventoryDoubleChest doesn't defer to here
    }

    public void setMaxStackSize(int size) {
        this.container1.setMaxStackSize(size);
        this.container2.setMaxStackSize(size);
    }

    @Override
    public org.bukkit.Location getLocation() {
        return this.container1.getLocation(); // TODO: right?
    }
    // CraftBukkit end

    public CompoundContainer(final Container container1, final Container container2) {
        this.container1 = container1;
        this.container2 = container2;
    }

    @Override
    public int getContainerSize() {
        return this.container1.getContainerSize() + this.container2.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return this.container1.isEmpty() && this.container2.isEmpty();
    }

    public boolean contains(final Container container) {
        return this.container1 == container || this.container2 == container;
    }

    @Override
    public ItemStack getItem(final int slot) {
        return slot >= this.container1.getContainerSize() ? this.container2.getItem(slot - this.container1.getContainerSize()) : this.container1.getItem(slot);
    }

    @Override
    public ItemStack removeItem(final int slot, final int count) {
        return slot >= this.container1.getContainerSize()
            ? this.container2.removeItem(slot - this.container1.getContainerSize(), count)
            : this.container1.removeItem(slot, count);
    }

    @Override
    public ItemStack removeItemNoUpdate(final int slot) {
        return slot >= this.container1.getContainerSize()
            ? this.container2.removeItemNoUpdate(slot - this.container1.getContainerSize())
            : this.container1.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(final int slot, final ItemStack itemStack) {
        if (slot >= this.container1.getContainerSize()) {
            this.container2.setItem(slot - this.container1.getContainerSize(), itemStack);
        } else {
            this.container1.setItem(slot, itemStack);
        }
    }

    @Override
    public int getMaxStackSize() {
        return Math.min(this.container1.getMaxStackSize(), this.container2.getMaxStackSize()); // CraftBukkit - check both sides
    }

    @Override
    public void setChanged() {
        this.container1.setChanged();
        this.container2.setChanged();
    }

    @Override
    public boolean stillValid(final Player player) {
        return this.container1.stillValid(player) && this.container2.stillValid(player);
    }

    @Override
    public void startOpen(final ContainerUser containerUser) {
        this.container1.startOpen(containerUser);
        this.container2.startOpen(containerUser);
    }

    @Override
    public void stopOpen(final ContainerUser containerUser) {
        this.container1.stopOpen(containerUser);
        this.container2.stopOpen(containerUser);
    }

    @Override
    public boolean canPlaceItem(final int slot, final ItemStack itemStack) {
        return slot >= this.container1.getContainerSize()
            ? this.container2.canPlaceItem(slot - this.container1.getContainerSize(), itemStack)
            : this.container1.canPlaceItem(slot, itemStack);
    }

    @Override
    public void clearContent() {
        this.container1.clearContent();
        this.container2.clearContent();
    }
}
