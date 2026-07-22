package net.minecraft.world.item.crafting;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;

public record RecipeHolder<T extends Recipe<?>>(ResourceKey<Recipe<?>> id, T value) {
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeHolder<?>> STREAM_CODEC = StreamCodec.composite(
        ResourceKey.streamCodec(Registries.RECIPE), RecipeHolder::id, Recipe.STREAM_CODEC, RecipeHolder::value, RecipeHolder::new
    );

    // CraftBukkit start
    public org.bukkit.inventory.Recipe toBukkitRecipe() {
        return this.value.toBukkitRecipe(org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(this.id.identifier()));
    }
    // CraftBukkit end

    @Override
    public boolean equals(final Object obj) {
        return this == obj || obj instanceof RecipeHolder<?> holder && this.id == holder.id;
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return this.id.toString();
    }
}
