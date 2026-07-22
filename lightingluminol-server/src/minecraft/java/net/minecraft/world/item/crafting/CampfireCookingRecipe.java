package net.minecraft.world.item.crafting;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

public class CampfireCookingRecipe extends AbstractCookingRecipe {
    public static final MapCodec<CampfireCookingRecipe> MAP_CODEC = cookingMapCodec(CampfireCookingRecipe::new, 100);
    public static final StreamCodec<RegistryFriendlyByteBuf, CampfireCookingRecipe> STREAM_CODEC = cookingStreamCodec(CampfireCookingRecipe::new);
    public static final RecipeSerializer<CampfireCookingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public CampfireCookingRecipe(
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
        return Items.CAMPFIRE;
    }

    @Override
    public RecipeSerializer<CampfireCookingRecipe> getSerializer() {
        return SERIALIZER;
    }

    @Override
    public RecipeType<CampfireCookingRecipe> getType() {
        return RecipeType.CAMPFIRE_COOKING;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return RecipeBookCategories.CAMPFIRE;
    }

    // CraftBukkit start
    @Override
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        org.bukkit.craftbukkit.inventory.CraftItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this.result().create());

        org.bukkit.craftbukkit.inventory.CraftCampfireRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftCampfireRecipe(id, result, org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.input()), this.experience(), this.cookingTime());
        recipe.setGroup(this.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(this.category()));

        return recipe;
    }
    // CraftBukkit end
}
