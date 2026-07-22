package net.minecraft.world.entity.player;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class Inventory implements Container, Nameable {
    public static final int POP_TIME_DURATION = 5;
    public static final int INVENTORY_SIZE = 36;
    public static final int SELECTION_SIZE = 9;
    public static final int SLOT_OFFHAND = 40;
    public static final int SLOT_BODY_ARMOR = 41;
    public static final int SLOT_SADDLE = 42;
    public static final int NOT_FOUND_INDEX = -1;
    public static final Int2ObjectMap<EquipmentSlot> EQUIPMENT_SLOT_MAPPING = new Int2ObjectArrayMap<>(
        Map.of(
            EquipmentSlot.FEET.getIndex(INVENTORY_SIZE),
            EquipmentSlot.FEET,
            EquipmentSlot.LEGS.getIndex(INVENTORY_SIZE),
            EquipmentSlot.LEGS,
            EquipmentSlot.CHEST.getIndex(INVENTORY_SIZE),
            EquipmentSlot.CHEST,
            EquipmentSlot.HEAD.getIndex(INVENTORY_SIZE),
            EquipmentSlot.HEAD,
            40,
            EquipmentSlot.OFFHAND,
            41,
            EquipmentSlot.BODY,
            42,
            EquipmentSlot.SADDLE
        )
    );
    private static final Component DEFAULT_NAME = Component.translatable("container.inventory");
    private final NonNullList<ItemStack> items = NonNullList.withSize(36, ItemStack.EMPTY);
    private int selected;
    public final Player player;
    public final EntityEquipment equipment;
    private int timesChanged;
    // Paper start - add fields and methods
    public static final EquipmentSlot[] EQUIPMENT_SLOTS_SORTED_BY_INDEX = EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()
        .stream()
        .sorted(java.util.Comparator.comparingInt(it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry::getIntKey))
        .map(java.util.Map.Entry::getValue).toArray(EquipmentSlot[]::new);
    public java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = MAX_STACK;

    @Override
    public java.util.List<ItemStack> getContents() {
        java.util.List<ItemStack> combined = new java.util.ArrayList<>(this.items.size() + EQUIPMENT_SLOT_MAPPING.size());
        combined.addAll(this.items);
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS_SORTED_BY_INDEX) {
            ItemStack itemStack = this.equipment.get(equipmentSlot);
            combined.add(itemStack); // Include empty items
        };
        return combined;
    }

    public java.util.List<ItemStack> getArmorContents() {
        java.util.List<ItemStack> items = new java.util.ArrayList<>(4);
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS_SORTED_BY_INDEX) {
            if (equipmentSlot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                items.add(this.equipment.get(equipmentSlot));
            }
        }
        return items;
    }

    public java.util.List<ItemStack> getExtraContent() {
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOTS_SORTED_BY_INDEX) {
            if (equipmentSlot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) { // Non humanoid armor is considered extra
                items.add(this.equipment.get(equipmentSlot));
            }
        }
        return items;
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
    public org.bukkit.inventory.InventoryHolder getOwner() {
        return this.player.getBukkitEntity();
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
    public org.bukkit.Location getLocation() {
        return this.player.getBukkitEntity().getLocation();
    }
    // Paper end - add fields and methods

    public Inventory(final Player player, final EntityEquipment equipment) {
        this.player = player;
        this.equipment = equipment;
    }

    public int getSelectedSlot() {
        return this.selected;
    }

    public void setSelectedSlot(final int selected) {
        if (!isHotbarSlot(selected)) {
            throw new IllegalArgumentException("Invalid selected slot");
        }

        this.selected = selected;
    }

    public ItemStack getSelectedItem() {
        return this.items.get(this.selected);
    }

    public ItemStack setSelectedItem(final ItemStack itemStack) {
        return this.items.set(this.selected, itemStack);
    }

    public static int getSelectionSize() {
        return 9;
    }

    public NonNullList<ItemStack> getNonEquipmentItems() {
        return this.items;
    }

    private boolean hasRemainingSpaceForItem(final ItemStack slotItemStack, final ItemStack newItemStack) {
        return !slotItemStack.isEmpty()
            && slotItemStack.isStackable()
            && slotItemStack.getCount() < this.getMaxStackSize(slotItemStack)
            && ItemStack.isSameItemSameComponents(slotItemStack, newItemStack); // Paper - check if itemstack is stackable first
    }

    // CraftBukkit start - Watch method above! :D
    public int canHold(ItemStack itemStack) {
        int remains = itemStack.getCount();
        for (int slot = 0; slot < this.items.size(); ++slot) {
            ItemStack itemInSlot = this.getItem(slot);
            if (itemInSlot.isEmpty()) {
                return itemStack.getCount();
            }

            if (this.hasRemainingSpaceForItem(itemInSlot, itemStack)) {
                remains -= (itemInSlot.getMaxStackSize() < this.getMaxStackSize() ? itemInSlot.getMaxStackSize() : this.getMaxStackSize()) - itemInSlot.getCount();
            }
            if (remains <= 0) {
                return itemStack.getCount();
            }
        }

        ItemStack itemInOffhand = this.equipment.get(EquipmentSlot.OFFHAND);
        if (this.hasRemainingSpaceForItem(itemInOffhand, itemStack)) {
            remains -= (itemInOffhand.getMaxStackSize() < this.getMaxStackSize() ? itemInOffhand.getMaxStackSize() : this.getMaxStackSize()) - itemInOffhand.getCount();
        }
        if (remains <= 0) {
            return itemStack.getCount();
        }

        return itemStack.getCount() - remains;
    }
    // CraftBukkit end

    public int getFreeSlot() {
        for (int i = 0; i < this.items.size(); i++) {
            if (this.items.get(i).isEmpty()) {
                return i;
            }
        }

        return NOT_FOUND_INDEX;
    }

    // Paper start - Add PlayerPickItemEvent
    public void addAndPickItem(final ItemStack itemStack, final int targetSlot) {
        this.setSelectedSlot(targetSlot);
        // Paper end - Add PlayerPickItemEvent
        if (!this.items.get(this.selected).isEmpty()) {
            int freeSlot = this.getFreeSlot();
            if (freeSlot != NOT_FOUND_INDEX) {
                this.items.set(freeSlot, this.items.get(this.selected));
            }
        }

        this.items.set(this.selected, itemStack);
    }

    // Paper start - Add PlayerPickItemEvent
    public void pickSlot(final int slot, final int targetSlot) {
        this.setSelectedSlot(targetSlot);
    // Paper end - Add PlayerPickItemEvent
        ItemStack tmp = this.items.get(this.selected);
        this.items.set(this.selected, this.items.get(slot));
        this.items.set(slot, tmp);
    }

    public static boolean isHotbarSlot(final int slot) {
        return slot >= 0 && slot < 9;
    }

    public int findSlotMatchingItem(final ItemStack itemStack) {
        for (int i = 0; i < this.items.size(); i++) {
            if (!this.items.get(i).isEmpty() && ItemStack.isSameItemSameComponents(itemStack, this.items.get(i))) {
                return i;
            }
        }

        return NOT_FOUND_INDEX;
    }

    public static boolean isUsableForCrafting(final ItemStack item) {
        return !item.isDamaged() && !item.isEnchanted() && !item.has(DataComponents.CUSTOM_NAME);
    }

    public int findSlotMatchingCraftingIngredient(final io.papermc.paper.inventory.recipe.ItemOrExact item, final ItemStack existingItem) { // Paper - Improve exact choice recipe ingredients
        for (int i = 0; i < this.items.size(); i++) {
            ItemStack inventoryItemStack = this.items.get(i);
            if (!inventoryItemStack.isEmpty()
                && item.matches(inventoryItemStack) // Paper - Improve exact choice recipe ingredients
                && (!(item instanceof io.papermc.paper.inventory.recipe.ItemOrExact.Item) || Inventory.isUsableForCrafting(inventoryItemStack)) // Paper - Improve exact choice recipe ingredients
                && (existingItem.isEmpty() || ItemStack.isSameItemSameComponents(existingItem, inventoryItemStack))) {
                return i;
            }
        }

        return NOT_FOUND_INDEX;
    }

    public int getSuitableHotbarSlot() {
        for (int slot = 0; slot < 9; slot++) {
            int index = (this.selected + slot) % 9;
            if (this.items.get(index).isEmpty()) {
                return index;
            }
        }

        for (int slot = 0; slot < 9; slot++) {
            int index = (this.selected + slot) % 9;
            if (!this.items.get(index).isEnchanted()) {
                return index;
            }
        }

        return this.selected;
    }

    public int clearOrCountMatchingItems(final Predicate<ItemStack> predicate, final int amountToRemove, final Container craftSlots) {
        int count = 0;
        boolean countingOnly = amountToRemove == 0;
        count += ContainerHelper.clearOrCountMatchingItems(this, predicate, amountToRemove - count, countingOnly);
        count += ContainerHelper.clearOrCountMatchingItems(craftSlots, predicate, amountToRemove - count, countingOnly);
        ItemStack carried = this.player.containerMenu.getCarried();
        count += ContainerHelper.clearOrCountMatchingItems(carried, predicate, amountToRemove - count, countingOnly);
        if (carried.isEmpty()) {
            this.player.containerMenu.setCarried(ItemStack.EMPTY);
        }

        return count;
    }

    private int addResource(final ItemStack itemStack) {
        int slot = this.getSlotWithRemainingSpace(itemStack);
        if (slot == NOT_FOUND_INDEX) {
            slot = this.getFreeSlot();
        }

        return slot == NOT_FOUND_INDEX ? itemStack.getCount() : this.addResource(slot, itemStack);
    }

    private int addResource(final int slot, final ItemStack itemStack) {
        int count = itemStack.getCount();
        ItemStack itemStackInSlot = this.getItem(slot);
        if (itemStackInSlot.isEmpty()) {
            itemStackInSlot = itemStack.copyWithCount(0);
            this.setItem(slot, itemStackInSlot);
        }

        int maxToAdd = this.getMaxStackSize(itemStackInSlot) - itemStackInSlot.getCount();
        int toAdd = Math.min(count, maxToAdd);
        if (toAdd == 0) {
            return count;
        }

        count -= toAdd;
        itemStackInSlot.grow(toAdd);
        itemStackInSlot.setPopTime(POP_TIME_DURATION);
        return count;
    }

    public int getSlotWithRemainingSpace(final ItemStack newItemStack) {
        if (this.hasRemainingSpaceForItem(this.getItem(this.selected), newItemStack)) {
            return this.selected;
        }

        if (this.hasRemainingSpaceForItem(this.getItem(SLOT_OFFHAND), newItemStack)) {
            return SLOT_OFFHAND;
        }

        for (int i = 0; i < this.items.size(); i++) {
            if (this.hasRemainingSpaceForItem(this.items.get(i), newItemStack)) {
                return i;
            }
        }

        return NOT_FOUND_INDEX;
    }

    public void tick() {
        for (int i = 0; i < this.items.size(); i++) {
            ItemStack itemStack = this.getItem(i);
            if (!itemStack.isEmpty()) {
                itemStack.inventoryTick(this.player.level(), this.player, i == this.selected ? EquipmentSlot.MAINHAND : null);
            }
        }
    }

    public boolean add(final ItemStack itemStack) {
        return this.add(NOT_FOUND_INDEX, itemStack);
    }

    public boolean add(int slot, final ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return false;
        }

        try {
            if (itemStack.isDamaged()) {
                if (slot == NOT_FOUND_INDEX) {
                    slot = this.getFreeSlot();
                }

                if (slot >= 0) {
                    this.items.set(slot, itemStack.copyAndClear());
                    this.items.get(slot).setPopTime(POP_TIME_DURATION);
                    return true;
                } else if (this.player.hasInfiniteMaterials()) {
                    itemStack.setCount(0);
                    return true;
                } else {
                    return false;
                }
            } else {
                int lastSize;
                do {
                    lastSize = itemStack.getCount();
                    if (slot == NOT_FOUND_INDEX) {
                        itemStack.setCount(this.addResource(itemStack));
                    } else {
                        itemStack.setCount(this.addResource(slot, itemStack));
                    }
                } while (!itemStack.isEmpty() && itemStack.getCount() < lastSize);

                if (itemStack.getCount() == lastSize && this.player.hasInfiniteMaterials()) {
                    itemStack.setCount(0);
                    return true;
                } else {
                    return itemStack.getCount() < lastSize;
                }
            }
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Adding item to inventory");
            CrashReportCategory category = report.addCategory("Item being added");
            category.setDetail("Item ID", Item.getId(itemStack.getItem()));
            category.setDetail("Item data", itemStack.getDamageValue());
            category.setDetail("Item name", () -> itemStack.getHoverName().getString());
            throw new ReportedException(report);
        }
    }

    public void placeItemBackInInventory(final ItemStack itemStack) {
        this.placeItemBackInInventory(itemStack, true);
    }

    public void placeItemBackInInventory(final ItemStack itemStack, final boolean shouldSendSetSlotPacket) {
        while (!itemStack.isEmpty()) {
            int slot = this.getSlotWithRemainingSpace(itemStack);
            if (slot == NOT_FOUND_INDEX) {
                slot = this.getFreeSlot();
            }

            if (slot == NOT_FOUND_INDEX) {
                this.player.drop(itemStack, false);
                break;
            }

            int slotHasSpaceFor = itemStack.getMaxStackSize() - this.getItem(slot).getCount();
            if (this.add(slot, itemStack.split(slotHasSpaceFor)) && shouldSendSetSlotPacket && this.player instanceof ServerPlayer serverPlayer) {
                serverPlayer.connection.send(this.createInventoryUpdatePacket(slot));
            }
        }
    }

    public ClientboundSetPlayerInventoryPacket createInventoryUpdatePacket(final int slot) {
        return new ClientboundSetPlayerInventoryPacket(slot, this.getItem(slot).copy());
    }

    @Override
    public ItemStack removeItem(final int slot, final int count) {
        if (slot < this.items.size()) {
            return ContainerHelper.removeItem(this.items, slot, count);
        }

        EquipmentSlot equipmentSlot = EQUIPMENT_SLOT_MAPPING.get(slot);
        if (equipmentSlot != null) {
            ItemStack itemStack = this.equipment.get(equipmentSlot);
            if (!itemStack.isEmpty()) {
                return itemStack.split(count);
            }
        }

        return ItemStack.EMPTY;
    }

    public void removeItem(final ItemStack itemStack) {
        for (int slot = 0; slot < this.items.size(); slot++) {
            if (this.items.get(slot) == itemStack) {
                this.items.set(slot, ItemStack.EMPTY);
                return;
            }
        }

        for (EquipmentSlot equipmentSlot : EQUIPMENT_SLOT_MAPPING.values()) {
            ItemStack stackInSlot = this.equipment.get(equipmentSlot);
            if (stackInSlot == itemStack) {
                this.equipment.set(equipmentSlot, ItemStack.EMPTY);
                return;
            }
        }
    }

    @Override
    public ItemStack removeItemNoUpdate(final int slot) {
        if (slot < this.items.size()) {
            ItemStack itemStack = this.items.get(slot);
            this.items.set(slot, ItemStack.EMPTY);
            return itemStack;
        } else {
            EquipmentSlot equipmentSlot = EQUIPMENT_SLOT_MAPPING.get(slot);
            return equipmentSlot != null ? this.equipment.set(equipmentSlot, ItemStack.EMPTY) : ItemStack.EMPTY;
        }
    }

    @Override
    public void setItem(final int slot, final ItemStack itemStack) {
        if (slot < this.items.size()) {
            this.items.set(slot, itemStack);
        }

        EquipmentSlot equipmentSlot = EQUIPMENT_SLOT_MAPPING.get(slot);
        if (equipmentSlot != null) {
            this.equipment.set(equipmentSlot, itemStack);
        }
    }

    public void save(final ValueOutput.TypedOutputList<ItemStackWithSlot> output) {
        for (int i = 0; i < this.items.size(); i++) {
            ItemStack item = this.items.get(i);
            if (!item.isEmpty()) {
                output.add(new ItemStackWithSlot(i, item));
            }
        }
    }

    public void load(final ValueInput.TypedInputList<ItemStackWithSlot> input) {
        this.items.clear();

        for (ItemStackWithSlot item : input) {
            if (item.isValidInContainer(this.items.size())) {
                this.setItem(item.slot(), item.stack());
            }
        }
    }

    @Override
    public int getContainerSize() {
        return this.items.size() + EQUIPMENT_SLOT_MAPPING.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack itemStack : this.items) {
            if (!itemStack.isEmpty()) {
                return false;
            }
        }

        for (EquipmentSlot slot : EQUIPMENT_SLOT_MAPPING.values()) {
            if (!this.equipment.get(slot).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public ItemStack getItem(final int slot) {
        if (slot < this.items.size()) {
            return this.items.get(slot);
        }

        EquipmentSlot equipmentSlot = EQUIPMENT_SLOT_MAPPING.get(slot);
        return equipmentSlot != null ? this.equipment.get(equipmentSlot) : ItemStack.EMPTY;
    }

    @Override
    public Component getName() {
        return DEFAULT_NAME;
    }

    public void dropAll() {
        for (int i = 0; i < this.items.size(); i++) {
            ItemStack itemStack = this.items.get(i);
            if (!itemStack.isEmpty()) {
                this.player.drop(itemStack, true, false);
                this.items.set(i, ItemStack.EMPTY);
            }
        }

        this.equipment.dropAll(this.player);
    }

    @Override
    public void setChanged() {
        this.timesChanged++;
    }

    public int getTimesChanged() {
        return this.timesChanged;
    }

    @Override
    public boolean stillValid(final Player player) {
        return true;
    }

    public boolean contains(final ItemStack searchStack) {
        for (ItemStack itemStack : this) {
            if (!itemStack.isEmpty() && ItemStack.isSameItemSameComponents(itemStack, searchStack)) {
                return true;
            }
        }

        return false;
    }

    public boolean contains(final TagKey<Item> tag) {
        for (ItemStack itemStack : this) {
            if (!itemStack.isEmpty() && itemStack.is(tag)) {
                return true;
            }
        }

        return false;
    }

    public boolean contains(final Predicate<ItemStack> predicate) {
        for (ItemStack stack : this) {
            if (predicate.test(stack)) {
                return true;
            }
        }

        return false;
    }

    public void replaceWith(final Inventory other) {
        for (int i = 0; i < this.getContainerSize(); i++) {
            this.setItem(i, other.getItem(i));
        }

        this.setSelectedSlot(other.getSelectedSlot());
    }

    @Override
    public void clearContent() {
        this.items.clear();
        this.equipment.clear();
    }

    public void fillStackedContents(final StackedItemContents contents) {
        for (ItemStack itemStack : this.items) {
            contents.accountSimpleStack(itemStack);
        }
    }

    public ItemStack removeFromSelected(final boolean all) {
        ItemStack selectedItem = this.getSelectedItem();
        return selectedItem.isEmpty() ? ItemStack.EMPTY : this.removeItem(this.selected, all ? selectedItem.getCount() : 1);
    }
}
