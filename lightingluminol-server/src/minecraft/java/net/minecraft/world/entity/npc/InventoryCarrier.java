package net.minecraft.world.entity.npc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public interface InventoryCarrier {
    String TAG_INVENTORY = "Inventory";

    SimpleContainer getInventory();

    static void pickUpItem(final ServerLevel level, final Mob mob, final InventoryCarrier inventoryCarrier, final ItemEntity itemEntity) {
        ItemStack itemStack = itemEntity.getItem();
        if (mob.wantsToPickUp(level, itemStack)) {
            SimpleContainer inventory = inventoryCarrier.getInventory();
            boolean hasSpace = inventory.canAddItem(itemStack);
            if (!hasSpace) {
                return;
            }

            // CraftBukkit start
            ItemStack remaining = new SimpleContainer(inventory).addItem(itemStack);
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPickupItemEvent(mob, itemEntity, remaining.getCount()).isCancelled()) {
                return;
            }
            // CraftBukkit end

            mob.onItemPickup(itemEntity);
            int count = itemStack.getCount();
            ItemStack remainder = inventory.addItem(itemStack);
            mob.take(itemEntity, count - remainder.getCount());
            if (remainder.isEmpty()) {
                itemEntity.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
            } else {
                itemStack.setCount(remainder.getCount());
            }
        }
    }

    default void readInventoryFromTag(final ValueInput input) {
        input.list("Inventory", ItemStack.CODEC).ifPresent(list -> this.getInventory().fromItemList((ValueInput.TypedInputList<ItemStack>)list));
    }

    default void writeInventoryToTag(final ValueOutput output) {
        this.getInventory().storeAsItemList(output.list("Inventory", ItemStack.CODEC));
    }
}
