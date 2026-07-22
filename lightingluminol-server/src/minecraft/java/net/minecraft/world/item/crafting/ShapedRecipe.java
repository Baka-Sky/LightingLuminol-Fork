package net.minecraft.world.item.crafting;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;

public class ShapedRecipe extends NormalCraftingRecipe {
    public static final MapCodec<ShapedRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(
                Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                ShapedRecipePattern.MAP_CODEC.forGetter(o -> o.pattern),
                ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.result)
            )
            .apply(i, ShapedRecipe::new)
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ShapedRecipe> STREAM_CODEC = StreamCodec.composite(
        Recipe.CommonInfo.STREAM_CODEC,
        o -> o.commonInfo,
        CraftingRecipe.CraftingBookInfo.STREAM_CODEC,
        o -> o.bookInfo,
        ShapedRecipePattern.STREAM_CODEC,
        o -> o.pattern,
        ItemStackTemplate.STREAM_CODEC,
        o -> o.result,
        ShapedRecipe::new
    );
    public static final RecipeSerializer<ShapedRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);
    private final ShapedRecipePattern pattern;
    private final ItemStackTemplate result;

    public ShapedRecipe(
        final Recipe.CommonInfo commonInfo, final CraftingRecipe.CraftingBookInfo bookInfo, final ShapedRecipePattern pattern, final ItemStackTemplate result
    ) {
        super(commonInfo, bookInfo);
        this.pattern = pattern;
        this.result = result;
    }

    @Override
    public RecipeSerializer<ShapedRecipe> getSerializer() {
        return SERIALIZER;
    }

    @VisibleForTesting
    public List<Optional<Ingredient>> getIngredients() {
        return this.pattern.ingredients();
    }

    @Override
    protected PlacementInfo createPlacementInfo() {
        return PlacementInfo.createFromOptionals(this.pattern.ingredients());
    }

    @Override
    public boolean matches(final CraftingInput input, final Level level) {
        return this.pattern.matches(input);
    }

    @Override
    public ItemStack assemble(final CraftingInput input) {
        return this.result.create();
    }

    public int getWidth() {
        return this.pattern.width();
    }

    public int getHeight() {
        return this.pattern.height();
    }

    @Override
    public List<RecipeDisplay> display() {
        return List.of(
            new ShapedCraftingRecipeDisplay(
                this.pattern.width(),
                this.pattern.height(),
                this.pattern.ingredients().stream().map(e -> e.map(Ingredient::display).orElse(SlotDisplay.Empty.INSTANCE)).toList(),
                new SlotDisplay.ItemStackSlotDisplay(this.result),
                new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)
            )
        );
    }

    // CraftBukkit start
    @Override
    public org.bukkit.inventory.ShapedRecipe toBukkitRecipe(org.bukkit.NamespacedKey id) {
        org.bukkit.craftbukkit.inventory.CraftItemStack result = org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(this.result.create());
        org.bukkit.craftbukkit.inventory.CraftShapedRecipe recipe = new org.bukkit.craftbukkit.inventory.CraftShapedRecipe(id, result, this);
        recipe.setGroup(this.group());
        recipe.setCategory(org.bukkit.craftbukkit.inventory.CraftRecipe.getCategory(this.category()));

        switch (this.pattern.height()) {
            case 1:
                switch (this.pattern.width()) {
                    case 1:
                        recipe.shape("a");
                        break;
                    case 2:
                        recipe.shape("ab");
                        break;
                    case 3:
                        recipe.shape("abc");
                        break;
                }
                break;
            case 2:
                switch (this.pattern.width()) {
                    case 1:
                        recipe.shape("a", "b");
                        break;
                    case 2:
                        recipe.shape("ab", "cd");
                        break;
                    case 3:
                        recipe.shape("abc", "def");
                        break;
                }
                break;
            case 3:
                switch (this.pattern.width()) {
                    case 1:
                        recipe.shape("a", "b", "c");
                        break;
                    case 2:
                        recipe.shape("ab", "cd", "ef");
                        break;
                    case 3:
                        recipe.shape("abc", "def", "ghi");
                        break;
                }
                break;
        }
        char c = 'a';
        for (Optional<Ingredient> ingredient : this.pattern.ingredients()) {
            org.bukkit.inventory.RecipeChoice choice = org.bukkit.craftbukkit.inventory.CraftRecipe.toChoice(ingredient);
            if (choice != org.bukkit.inventory.RecipeChoice.empty()) { // Paper
                recipe.setIngredient(c, choice);
            }

            c++;
        }
        return recipe;
    }
    // CraftBukkit end
}
