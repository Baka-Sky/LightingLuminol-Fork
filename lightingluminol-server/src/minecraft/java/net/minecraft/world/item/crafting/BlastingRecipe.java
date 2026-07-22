package net.minecraft.world.item.crafting;

import com.mojang.serialization.MapCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

public class BlastingRecipe extends AbstractCookingRecipe {
    public static final MapCodec<BlastingRecipe> MAP_CODEC = cookingMapCodec(BlastingRecipe::new, 100);
    public static final StreamCodec<RegistryFriendlyByteBuf, BlastingRecipe> STREAM_CODEC = cookingStreamCodec(BlastingRecipe::new);
    public static final RecipeSerializer<BlastingRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    public BlastingRecipe(
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
        return Items.BLAST_FURNACE;
    }

    @Override
    public RecipeSerializer<BlastingRecipe> getSerializer() {
        return SERIALIZER;
    }

    @Override
    public RecipeType<BlastingRecipe> getType() {
        return RecipeType.BLASTING;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return switch (this.category()) {
            case BLOCKS -> RecipeBookCategories.BLAST_FURNACE_BLOCKS;
            case FOOD, MISC -> RecipeBookCategories.BLAST_FURNACE_MISC;
        };
    }

    // CraftBukkit start
    @Override
    public org.bukkit.inventory.Recipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        org.bukkit.craftbukkit.inventory.CraftItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this.result().create());

        org.bukkit.craftbukkit.inventory.CraftBlastingRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftBlastingRecipe(id, result, org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(this.input()), this.experience(), this.cookingTime());
        recipe.setGroup(this.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(this.category()));

        return recipe;
    }
    // CraftBukkit end
}
