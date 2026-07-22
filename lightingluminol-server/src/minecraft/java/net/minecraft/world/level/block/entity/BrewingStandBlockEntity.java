package net.minecraft.world.level.block.entity;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public class BrewingStandBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;
    private static final int[] SLOTS_FOR_UP = new int[]{3};
    private static final int[] SLOTS_FOR_DOWN = new int[]{0, 1, 2, 3};
    private static final int[] SLOTS_FOR_SIDES = new int[]{0, 1, 2, 4};
    public static final int FUEL_USES = 20;
    public static final int DATA_BREW_TIME = 0;
    public static final int DATA_FUEL_USES = 1;
    public static final int NUM_DATA_VALUES = 2;
    private static final short DEFAULT_BREW_TIME = 0;
    private static final byte DEFAULT_FUEL = 0;
    private static final Component DEFAULT_NAME = Component.translatable("container.brewing");
    private NonNullList<ItemStack> items = NonNullList.withSize(5, ItemStack.EMPTY);
    public int brewTime;
    public int recipeBrewTime = 400; // Paper - Add recipeBrewTime
    private boolean[] lastPotionCount;
    private Item ingredient;
    public int fuel;
    protected final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(final int dataId) {
            return switch (dataId) {
                case 0 -> BrewingStandBlockEntity.this.brewTime;
                case 1 -> BrewingStandBlockEntity.this.fuel;
                case 2 -> BrewingStandBlockEntity.this.recipeBrewTime; // Paper - Add recipeBrewTime
                default -> 0;
            };
        }

        @Override
        public void set(final int dataId, final int value) {
            switch (dataId) {
                case 0:
                    BrewingStandBlockEntity.this.brewTime = value;
                    break;
                case 1:
                    BrewingStandBlockEntity.this.fuel = value;
                    // Paper start - Add recipeBrewTime
                    break;
                case 2:
                    BrewingStandBlockEntity.this.recipeBrewTime = value;
                    break;
                    // Paper end - Add recipeBrewTime
            }
        }

        @Override
        public int getCount() {
            return 3; // Paper - Add recipeBrewTime
        }
    };
    // CraftBukkit start - add fields and methods
    public java.util.List<org.bukkit.entity.HumanEntity> transaction = new java.util.ArrayList<>();
    private int maxStack = MAX_STACK;

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
    public java.util.List<net.minecraft.world.item.ItemStack> getContents() {
        return this.items;
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    // CraftBukkit end

    public BrewingStandBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        super(BlockEntityTypes.BREWING_STAND, worldPosition, blockState);
    }

    @Override
    protected Component getDefaultName() {
        return DEFAULT_NAME;
    }

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(final NonNullList<ItemStack> items) {
        this.items = items;
    }

    public static void serverTick(final Level level, final BlockPos pos, final BlockState selfState, final BrewingStandBlockEntity entity) {
        ItemStack fuel = entity.items.get(4);
        if (entity.fuel <= 0 && fuel.is(ItemTags.BREWING_FUEL)) {
            // CraftBukkit start
            org.bukkit.event.inventory.BrewingStandFuelEvent event = new org.bukkit.event.inventory.BrewingStandFuelEvent(
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(fuel),
                20
            );
            if (!event.callEvent()) {
                return;
            }

            entity.fuel = event.getFuelPower();
            if (entity.fuel > 0 && event.isConsuming()) {
                fuel.shrink(1);
            }
            // CraftBukkit end
            setChanged(level, pos, selfState);
        }

        boolean brewable = isBrewable(level.potionBrewing(), entity.items);
        boolean isBrewing = entity.brewTime > 0;
        ItemStack ingredient = entity.items.get(3);
        if (isBrewing) {
            entity.brewTime--;
            boolean isDoneBrewing = entity.brewTime == 0;
            if (isDoneBrewing && brewable) {
                doBrew(level, pos, entity.items, entity); // CraftBukkit
            } else if (!brewable || !ingredient.is(entity.ingredient)) {
                entity.brewTime = 0;
            }

            setChanged(level, pos, selfState);
        } else if (brewable && entity.fuel > 0) {
            entity.fuel--;
            // CraftBukkit start
            org.bukkit.event.block.BrewingStartEvent event = new org.bukkit.event.block.BrewingStartEvent(
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(ingredient), 400);
            event.callEvent();
            entity.recipeBrewTime = event.getRecipeBrewTime(); // Paper - use recipe brew time from event
            entity.brewTime = event.getBrewingTime(); // 400 -> event.getTotalBrewTime() // Paper - use brewing time from event
            // CraftBukkit end
            entity.ingredient = ingredient.getItem();
            setChanged(level, pos, selfState);
        }

        boolean[] newCount = entity.getPotionBits();
        if (!Arrays.equals(newCount, entity.lastPotionCount)) {
            entity.lastPotionCount = newCount;
            BlockState state = selfState;
            if (!(state.getBlock() instanceof BrewingStandBlock)) {
                return;
            }

            for (int i = 0; i < BrewingStandBlock.HAS_BOTTLE.length; i++) {
                state = state.setValue(BrewingStandBlock.HAS_BOTTLE[i], newCount[i]);
            }

            level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        }
    }

    private boolean[] getPotionBits() {
        boolean[] result = new boolean[3];

        for (int potion = 0; potion < 3; potion++) {
            if (!this.items.get(potion).isEmpty()) {
                result[potion] = true;
            }
        }

        return result;
    }

    private static boolean isBrewable(final PotionBrewing potionBrewing, final NonNullList<ItemStack> items) {
        ItemStack ingredient = items.get(3);
        if (ingredient.isEmpty()) {
            return false;
        }

        if (!potionBrewing.isIngredient(ingredient)) {
            return false;
        }

        for (int dest = 0; dest < 3; dest++) {
            ItemStack itemStack = items.get(dest);
            if (!itemStack.isEmpty() && potionBrewing.hasMix(itemStack, ingredient)) {
                return true;
            }
        }

        return false;
    }

    private static void doBrew(final Level level, final BlockPos pos, final NonNullList<ItemStack> items, final BrewingStandBlockEntity entity) { // CraftBukkit
        ItemStack ingredient = items.get(3);
        PotionBrewing potionBrewing = level.potionBrewing();

        // CraftBukkit start
        org.bukkit.inventory.InventoryHolder owner = entity.getOwner();
        java.util.List<org.bukkit.inventory.ItemStack> brewResults = new java.util.ArrayList<>(3);
        for (int dest = 0; dest < 3; dest++) {
            brewResults.add(dest, org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(potionBrewing.mix(ingredient, items.get(dest))));
        }

        if (owner != null) {
            org.bukkit.event.inventory.BrewEvent event = new org.bukkit.event.inventory.BrewEvent(
                org.bukkit.craftbukkit.block.CraftBlock.at(level, pos),
                (org.bukkit.inventory.BrewerInventory) owner.getInventory(),
                brewResults,
                entity.fuel
            );
            if (!event.callEvent()) {
                return;
            }

            for (int dest = 0; dest < 3; dest++) {
                if (dest < brewResults.size()) {
                    items.set(dest, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(brewResults.get(dest)));
                } else {
                    items.set(dest, ItemStack.EMPTY);
                }
            }
        }
        // CraftBukkit end

        ingredient.shrink(1);
        ItemStackTemplate remainder = ingredient.getItem().getCraftingRemainder();
        if (remainder != null) {
            if (ingredient.isEmpty()) {
                ingredient = remainder.create();
            } else {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), remainder.create());
            }
        }

        items.set(3, ingredient);
        level.levelEvent(LevelEvent.SOUND_BREWING_STAND_BREW, pos, 0);
    }

    @Override
    protected void loadAdditional(final ValueInput input) {
        super.loadAdditional(input);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(input, this.items);
        this.brewTime = input.getShortOr("BrewTime", (short)0);
        if (this.brewTime > 0) {
            this.ingredient = this.items.get(3).getItem();
        }

        this.fuel = input.getByteOr("Fuel", (byte)0);
    }

    @Override
    protected void saveAdditional(final ValueOutput output) {
        super.saveAdditional(output);
        output.putShort("BrewTime", (short)this.brewTime);
        ContainerHelper.saveAllItems(output, this.items);
        output.putByte("Fuel", (byte)this.fuel);
    }

    @Override
    public boolean canPlaceItem(final int slot, final ItemStack itemStack) {
        PotionBrewing potionBrewing = this.level != null ? this.level.potionBrewing() : PotionBrewing.EMPTY; // Paper - move up
        if (slot == 3) {
            return potionBrewing.isIngredient(itemStack);
        } else {
            return slot == 4
                ? itemStack.is(ItemTags.BREWING_FUEL)
                : (itemStack.is(Items.POTION) || itemStack.is(Items.SPLASH_POTION) || itemStack.is(Items.LINGERING_POTION) || itemStack.is(Items.GLASS_BOTTLE) || potionBrewing.isCustomInput(itemStack)) // Paper - Custom Potion Mixes
                    && this.getItem(slot).isEmpty();
        }
    }

    @Override
    public int[] getSlotsForFace(final Direction direction) {
        if (direction == Direction.UP) {
            return SLOTS_FOR_UP;
        } else {
            return direction == Direction.DOWN ? SLOTS_FOR_DOWN : SLOTS_FOR_SIDES;
        }
    }

    @Override
    public boolean canPlaceItemThroughFace(final int slot, final ItemStack itemStack, final @Nullable Direction direction) {
        return this.canPlaceItem(slot, itemStack);
    }

    @Override
    public boolean canTakeItemThroughFace(final int slot, final ItemStack itemStack, final Direction direction) {
        return slot != 3 || itemStack.is(Items.GLASS_BOTTLE);
    }

    @Override
    protected AbstractContainerMenu createMenu(final int containerId, final Inventory inventory) {
        return new BrewingStandMenu(containerId, inventory, this, this.dataAccess);
    }
}
