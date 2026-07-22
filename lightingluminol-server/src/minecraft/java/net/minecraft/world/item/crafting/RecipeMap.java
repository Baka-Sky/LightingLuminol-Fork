package net.minecraft.world.item.crafting;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.ImmutableMultimap.Builder;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

public class RecipeMap {
    public static final RecipeMap EMPTY = new RecipeMap(ImmutableMultimap.of(), Map.of());
    public final Multimap<RecipeType<?>, RecipeHolder<?>> byType;
    public final Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey;

    private RecipeMap(final Multimap<RecipeType<?>, RecipeHolder<?>> byType, final Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey) {
        this.byType = byType;
        this.byKey = byKey;
    }

    public static RecipeMap create(final Iterable<RecipeHolder<?>> recipes) {
        Builder<RecipeType<?>, RecipeHolder<?>> byType = ImmutableMultimap.builder();
        com.google.common.collect.ImmutableMap.Builder<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey = ImmutableMap.builder();

        for (RecipeHolder<?> recipe : recipes) {
            byType.put(recipe.value().getType(), recipe);
            byKey.put(recipe.id(), recipe);
        }

        // CraftBukkit start - mutable
        return new RecipeMap(com.google.common.collect.LinkedHashMultimap.create(byType.build()), com.google.common.collect.Maps.newLinkedHashMap(byKey.build()));
    }

    public void addRecipe(RecipeHolder<?> holder) {
        Collection<RecipeHolder<?>> recipes = this.byType.get(holder.value().getType());

        if (this.byKey.containsKey(holder.id())) {
            throw new IllegalStateException("Duplicate recipe ignored with ID " + holder.id());
        } else {
            recipes.add(holder);
            this.byKey.put(holder.id(), holder);
        }
    }
    // CraftBukkit end

    // Paper start - replace removeRecipe implementation
    public boolean removeRecipe(ResourceKey<Recipe<?>> id) {
        final RecipeHolder<?> remove = this.byKey.remove(id);
        if (remove == null) {
            return false;
        }
        final Collection<? extends RecipeHolder<?>> recipes = this.byType(remove.value().getType());
        return recipes.remove(remove);
    }
    // Paper end - replace removeRecipe implementation

    public <I extends RecipeInput, T extends Recipe<I>> Collection<RecipeHolder<T>> byType(final RecipeType<T> type) {
        return (Collection)this.byType.get(type);
    }

    public Collection<RecipeHolder<?>> values() {
        return this.byKey.values();
    }

    public @Nullable RecipeHolder<?> byKey(final ResourceKey<Recipe<?>> recipeId) {
        return this.byKey.get(recipeId);
    }

    public <I extends RecipeInput, T extends Recipe<I>> Stream<RecipeHolder<T>> getRecipesFor(final RecipeType<T> type, final I container, final Level level) {
        return container.isEmpty() ? Stream.empty() : this.byType(type).stream().filter(r -> r.value().matches(container, level));
    }
}
