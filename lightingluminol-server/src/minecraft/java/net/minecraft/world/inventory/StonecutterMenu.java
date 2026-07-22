package net.minecraft.world.inventory;

import java.util.List;
import java.util.Optional;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public class StonecutterMenu extends AbstractContainerMenu {
    public static final int INPUT_SLOT = 0;
    public static final int RESULT_SLOT = 1;
    private static final int INV_SLOT_START = 2;
    private static final int INV_SLOT_END = 29;
    private static final int USE_ROW_SLOT_START = 29;
    private static final int USE_ROW_SLOT_END = 38;
    private final ContainerLevelAccess access;
    private final DataSlot selectedRecipeIndex = DataSlot.shared(new int[1], 0); // Paper - Add PlayerStonecutterRecipeSelectEvent
    private final Level level;
    private SelectableRecipe.SingleInputSet<StonecutterRecipe> recipesForInput = SelectableRecipe.SingleInputSet.empty();
    private ItemStack input = ItemStack.EMPTY;
    private long lastSoundTime;
    private final Slot inputSlot;
    private final Slot resultSlot;
    private Runnable slotUpdateListener = () -> {};
    public final Container container; // Paper - Add missing InventoryHolders - move down
    private final ResultContainer resultContainer; // Paper - Add missing InventoryHolders - move down
    // CraftBukkit start
    private org.bukkit.craftbukkit.inventory.view.@org.jspecify.annotations.Nullable CraftStonecutterView view = null;
    private final org.bukkit.entity.Player player;

    @Override
    public org.bukkit.craftbukkit.inventory.view.CraftStonecutterView getBukkitView() {
        if (this.view != null) {
            return this.view;
        }

        org.bukkit.craftbukkit.inventory.CraftInventoryStonecutter inventory = new org.bukkit.craftbukkit.inventory.CraftInventoryStonecutter(this.container, this.resultContainer);
        this.view = new org.bukkit.craftbukkit.inventory.view.CraftStonecutterView(this.player, inventory, this);
        return this.view;
    }
    // CraftBukkit end

    public StonecutterMenu(final int containerId, final Inventory inventory) {
        this(containerId, inventory, ContainerLevelAccess.NULL);
    }

    public StonecutterMenu(final int containerId, final Inventory inventory, final ContainerLevelAccess access) {
        super(MenuType.STONECUTTER, containerId);
        this.access = access;
        this.level = inventory.player.level();
        // Paper start
        this.container = new SimpleContainer(this.createBlockHolder(access), 1) { // Paper - Add missing InventoryHolders
            @Override
            public void setChanged() {
                super.setChanged();
                StonecutterMenu.this.slotsChanged(this);
                StonecutterMenu.this.slotUpdateListener.run();
            }
            // CraftBukkit start
            @Override
            public org.bukkit.Location getLocation() {
                return access.getLocation();
            }
            // CraftBukkit end
        };
        this.resultContainer = new ResultContainer(this.createBlockHolder(access)); // Paper - Add missing InventoryHolders
        // Paper end
        this.inputSlot = this.addSlot(new Slot(this.container, 0, 20, 33));
        this.resultSlot = this.addSlot(new Slot(this.resultContainer, 1, 143, 33) {
            @Override
            public boolean mayPlace(final ItemStack itemStack) {
                return false;
            }

            @Override
            public void onTake(final Player player, final ItemStack carried) {
                carried.onCraftedBy(player, carried.getCount());
                StonecutterMenu.this.resultContainer.awardUsedRecipes(player, this.getRelevantItems());
                ItemStack remaining = StonecutterMenu.this.inputSlot.remove(1);
                if (!remaining.isEmpty()) {
                    StonecutterMenu.this.setupResultSlot(StonecutterMenu.this.selectedRecipeIndex.get());
                }

                access.execute((level, pos) -> {
                    long gameTime = level.getGameTime();
                    if (StonecutterMenu.this.lastSoundTime != gameTime) {
                        level.playSound(null, pos, SoundEvents.UI_STONECUTTER_TAKE_RESULT, SoundSource.BLOCKS, 1.0F, 1.0F);
                        StonecutterMenu.this.lastSoundTime = gameTime;
                    }
                });
                super.onTake(player, carried);
            }

            private List<ItemStack> getRelevantItems() {
                return List.of(StonecutterMenu.this.inputSlot.getItem());
            }
        });
        this.addStandardInventorySlots(inventory, 8, 84);
        this.addDataSlot(this.selectedRecipeIndex);
        this.player = (org.bukkit.entity.Player) inventory.player.getBukkitEntity(); // CraftBukkit
    }

    public int getSelectedRecipeIndex() {
        return this.selectedRecipeIndex.get();
    }

    public SelectableRecipe.SingleInputSet<StonecutterRecipe> getVisibleRecipes() {
        return this.recipesForInput;
    }

    public int getNumberOfVisibleRecipes() {
        return this.recipesForInput.size();
    }

    public boolean hasInputItem() {
        return this.inputSlot.hasItem() && !this.recipesForInput.isEmpty();
    }

    @Override
    public boolean stillValid(final Player player) {
        if (!this.checkReachable) return true; // CraftBukkit
        return stillValid(this.access, player, Blocks.STONECUTTER);
    }

    @Override
    public boolean clickMenuButton(final Player player, final int buttonId) {
        if (this.selectedRecipeIndex.get() == buttonId) {
            return false;
        }

        if (this.isValidRecipeIndex(buttonId)) {
            // Paper start - Add PlayerStonecutterRecipeSelectEvent
            int recipeIndex = buttonId;
            this.selectedRecipeIndex.set(recipeIndex);
            this.selectedRecipeIndex.checkAndClearUpdateFlag(); // mark as changed
            paperEventBlock: if (this.isValidRecipeIndex(buttonId)) {
                final Optional<RecipeHolder<StonecutterRecipe>> recipe = this.recipesForInput.entries().get(buttonId).recipe().recipe();
                if (recipe.isEmpty()) break paperEventBlock; // The recipe selected does not have an actual server recipe (presumably its the empty one). Cannot call the event, just break.

                io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent event = new io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent((org.bukkit.entity.Player) player.getBukkitEntity(), getBukkitView().getTopInventory(), (org.bukkit.inventory.StonecuttingRecipe) recipe.get().toBukkitRecipe());
                if (!event.callEvent()) {
                    player.containerMenu.sendAllDataToRemote();
                    return false;
                }

                net.minecraft.resources.Identifier key = org.bukkit.craftbukkit.util.CraftNamespacedKey.toMinecraft(event.getStonecuttingRecipe().getKey());
                if (!recipe.get().id().identifier().equals(key)) { // If the recipe did NOT stay the same
                    for (int newRecipeIndex = 0; newRecipeIndex < this.recipesForInput.entries().size(); newRecipeIndex++) {
                        if (this.recipesForInput.entries().get(newRecipeIndex).recipe().recipe().filter(r -> r.id().identifier().equals(key)).isPresent()) {
                            recipeIndex = newRecipeIndex;
                            break;
                        }
                    }
                }
            }
            player.containerMenu.sendAllDataToRemote();
            this.selectedRecipeIndex.set(recipeIndex); // set new index, so that listeners can read it
            this.setupResultSlot(recipeIndex);
            // Paper end - Add PlayerStonecutterRecipeSelectEvent
        }

        return true;
    }

    private boolean isValidRecipeIndex(final int buttonId) {
        return buttonId >= 0 && buttonId < this.recipesForInput.size();
    }

    @Override
    public void slotsChanged(final Container container) {
        ItemStack input = this.inputSlot.getItem();
        if (!input.is(this.input.getItem())) {
            this.input = input.copy();
            this.setupRecipeList(input);
        }
        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareResultEvent(this, RESULT_SLOT); // Paper - Add PrepareResultEvent
    }

    private void setupRecipeList(final ItemStack item) {
        this.selectedRecipeIndex.set(-1);
        this.resultSlot.set(ItemStack.EMPTY);
        if (!item.isEmpty()) {
            this.recipesForInput = this.level.recipeAccess().stonecutterRecipes().selectByInput(item);
        } else {
            this.recipesForInput = SelectableRecipe.SingleInputSet.empty();
        }
    }

    private void setupResultSlot(final int index) {
        Optional<RecipeHolder<StonecutterRecipe>> usedRecipe;
        if (!this.recipesForInput.isEmpty() && this.isValidRecipeIndex(index)) {
            SelectableRecipe.SingleInputEntry<StonecutterRecipe> entry = this.recipesForInput.entries().get(index);
            usedRecipe = entry.recipe().recipe();
        } else {
            usedRecipe = Optional.empty();
        }

        usedRecipe.ifPresentOrElse(recipe -> {
            this.resultContainer.setRecipeUsed((RecipeHolder<?>)recipe);
            this.resultSlot.set(recipe.value().assemble(new SingleRecipeInput(this.container.getItem(0))));
        }, () -> {
            this.resultSlot.set(ItemStack.EMPTY);
            this.resultContainer.setRecipeUsed(null);
        });
        this.broadcastChanges();
    }

    @Override
    public MenuType<?> getType() {
        return MenuType.STONECUTTER;
    }

    public void registerUpdateListener(final Runnable slotUpdateListener) {
        this.slotUpdateListener = slotUpdateListener;
    }

    @Override
    public boolean canTakeItemForPickAll(final ItemStack carried, final Slot target) {
        return target.container != this.resultContainer && super.canTakeItemForPickAll(carried, target);
    }

    @Override
    public ItemStack quickMoveStack(final Player player, final int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = this.slots.get(slotIndex);
        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            Item item = stack.getItem();
            clicked = stack.copy();
            if (slotIndex == 1) {
                item.onCraftedBy(stack, player);
                if (!this.moveItemStackTo(stack, 2, 38, true)) {
                    return ItemStack.EMPTY;
                }

                slot.onQuickCraft(stack, clicked);
            } else if (slotIndex == 0) {
                if (!this.moveItemStackTo(stack, 2, 38, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (this.level.recipeAccess().stonecutterRecipes().acceptsInput(stack)) {
                if (!this.moveItemStackTo(stack, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slotIndex >= 2 && slotIndex < 29) {
                if (!this.moveItemStackTo(stack, 29, 38, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slotIndex >= 29 && slotIndex < 38 && !this.moveItemStackTo(stack, 2, 29, false)) {
                return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            }

            slot.setChanged();
            if (stack.getCount() == clicked.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(player, stack);
            if (slotIndex == 1) {
                player.drop(stack, false);
            }

            this.broadcastChanges();
        }

        return clicked;
    }

    @Override
    public void removed(final Player player) {
        super.removed(player);
        this.resultContainer.removeItemNoUpdate(1);
        this.access.execute((level, pos) -> this.clearContainer(player, this.container));
    }
}
