package net.minecraft.world.item.crafting;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

public class SmeltingRecipe extends AbstractCookingRecipe {
    public static final MapCodec<SmeltingRecipe> MAP_CODEC = cookingMapCodec(SmeltingRecipe::new, 200);
    public static final StreamCodec<RegistryFriendlyByteBuf, SmeltingRecipe> STREAM_CODEC = cookingStreamCodec(SmeltingRecipe::new);
    public static final RecipeSerializer<SmeltingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public SmeltingRecipe(
        final Recipe.CommonInfo commonInfo,
        final AbstractCookingRecipe.CookingBookInfo bookInfo,
        final Ingredient ingredient,
        final ItemStackTemplate result,
        final float experience,
        final int cookingTime
    ) {
        super(commonInfo, bookInfo, ingredient, result, experience, cookingTime);
    }

    @Override
    protected Item furnaceIcon() {
        return Items.FURNACE;
    }

    @Override
    public RecipeSerializer<SmeltingRecipe> getSerializer() {
        return SERIALIZER;
    }

    @Override
    public RecipeType<SmeltingRecipe> getType() {
        return RecipeType.SMELTING;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return switch (this.category()) {
            case BLOCKS -> RecipeBookCategories.FURNACE_BLOCKS;
            case FOOD -> RecipeBookCategories.FURNACE_FOOD;
            case MISC -> RecipeBookCategories.FURNACE_MISC;
        };
    }

    // CraftBukkit start
    @Override
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        org.bukkit.craftbukkit.inventory.CraftItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this.result().create());

        org.bukkit.craftbukkit.inventory.CraftFurnaceRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftFurnaceRecipe(id, result, org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.input()), this.experience(), this.cookingTime());
        recipe.setGroup(this.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(this.category()));

        return recipe;
    }
    // CraftBukkit end
}
