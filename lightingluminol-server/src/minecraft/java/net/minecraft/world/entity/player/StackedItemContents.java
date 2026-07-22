package net.minecraft.world.entity.player;

import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import org.jspecify.annotations.Nullable;

public class StackedItemContents {
    // Paper start - Improve exact choice recipe ingredients
    private final StackedContents<io.papermc.paper.inventory.recipe.ItemOrExact> raw = new StackedContents<>();
    private io.papermc.paper.inventory.recipe.@Nullable StackedContentsExtrasMap extrasMap;

    public void initializeExtras(final Recipe<?> recipe, final net.minecraft.world.item.crafting.@Nullable CraftingInput input) {
        if (this.extrasMap == null) {
            this.extrasMap = new io.papermc.paper.inventory.recipe.StackedContentsExtrasMap(this.raw);
        }
        this.extrasMap.initialize(recipe);
        if (input != null) this.extrasMap.accountInput(input);
    }

    public void resetExtras() {
        if (this.extrasMap != null && !this.raw.amounts.isEmpty()) {
            this.extrasMap.resetExtras();
        }
    }
    // Paper end - Improve exact choice recipe ingredients

    public void accountSimpleStack(final ItemStack itemStack) {
        if (this.extrasMap != null && this.extrasMap.accountStack(itemStack, Math.min(itemStack.getMaxStackSize(), itemStack.getCount()))) return; // Paper - Improve exact choice recipe ingredients; Referenced from the accountStack method below
        if (Inventory.isUsableForCrafting(itemStack)) {
            this.accountStack(itemStack);
        }
    }

    public void accountStack(final ItemStack itemStack) {
        this.accountStack(itemStack, itemStack.getMaxStackSize());
    }

    public void accountStack(final ItemStack itemStack, final int maxCount) {
        if (!itemStack.isEmpty()) {
            int count = Math.min(maxCount, itemStack.getCount());
            if (this.extrasMap != null && !itemStack.getComponentsPatch().isEmpty() && this.extrasMap.accountStack(itemStack, count)) return; // Paper - Improve exact choice recipe ingredients; if an exact ingredient, don't include it
            this.raw.account(new io.papermc.paper.inventory.recipe.ItemOrExact.Item(itemStack.typeHolder()), count);
        }
    }

    public boolean canCraft(final Recipe<?> recipe, final StackedContents.@Nullable Output<io.papermc.paper.inventory.recipe.ItemOrExact> output) { // Paper - Improve exact choice recipe ingredients
        return this.canCraft(recipe, 1, output);
    }

    public boolean canCraft(final Recipe<?> recipe, final int amount, final StackedContents.@Nullable Output<io.papermc.paper.inventory.recipe.ItemOrExact> output) { // Paper - Improve exact choice recipe ingredients
        PlacementInfo placementInfo = recipe.placementInfo();
        return !placementInfo.isImpossibleToPlace() && this.canCraft(placementInfo.ingredients(), amount, output);
    }

    public boolean canCraft(
        final List<? extends StackedContents.IngredientInfo<io.papermc.paper.inventory.recipe.ItemOrExact>> contents, final StackedContents.@Nullable Output<io.papermc.paper.inventory.recipe.ItemOrExact> output // Paper - Improve exact choice recipe ingredients
    ) {
        return this.canCraft(contents, 1, output);
    }

    private boolean canCraft(
        final List<? extends StackedContents.IngredientInfo<io.papermc.paper.inventory.recipe.ItemOrExact>> contents, // Paper - Improve exact choice recipe ingredients
        final int amount,
        final StackedContents.@Nullable Output<io.papermc.paper.inventory.recipe.ItemOrExact> output // Paper - Improve exact choice recipe ingredients
    ) {
        return this.raw.tryPick(contents, amount, output);
    }

    public int getBiggestCraftableStack(final Recipe<?> recipe, final StackedContents.@Nullable Output<io.papermc.paper.inventory.recipe.ItemOrExact> output) { // Paper - Improve exact choice recipe ingredients
        return this.getBiggestCraftableStack(recipe, Integer.MAX_VALUE, output);
    }

    public int getBiggestCraftableStack(final Recipe<?> recipe, final int maxSize, final StackedContents.@Nullable Output<io.papermc.paper.inventory.recipe.ItemOrExact> output) { // Paper - Improve exact choice recipe ingredients
        return this.raw.tryPickAll(recipe.placementInfo().ingredients(), maxSize, output);
    }

    public void clear() {
        this.raw.clear();
    }
}
