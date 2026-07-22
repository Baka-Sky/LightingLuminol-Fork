package net.minecraft.world.item.enchantment.effects;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.phys.Vec3;

public record Ignite(LevelBasedValue duration) implements EnchantmentEntityEffect {
    public static final MapCodec<Ignite> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(LevelBasedValue.CODEC.fieldOf("duration").forGetter(e -> e.duration)).apply(i, Ignite::new)
    );

    @Override
    public void apply(final ServerLevel serverLevel, final int enchantmentLevel, final EnchantedItemInUse item, final Entity entity, final Vec3 position) {
        // CraftBukkit start - Call a combust event when somebody hits with a fire enchanted item
        org.bukkit.event.entity.EntityCombustEvent entityCombustEvent;
        if (item.owner() != null) {
            entityCombustEvent = new org.bukkit.event.entity.EntityCombustByEntityEvent(item.owner().getBukkitEntity(), entity.getBukkitEntity(), this.duration.calculate(enchantmentLevel));
        } else {
            entityCombustEvent = new org.bukkit.event.entity.EntityCombustEvent(entity.getBukkitEntity(), this.duration.calculate(enchantmentLevel));
        }

        org.bukkit.Bukkit.getPluginManager().callEvent(entityCombustEvent);
        if (entityCombustEvent.isCancelled()) {
            return;
        }

        entity.igniteForSeconds(entityCombustEvent.getDuration(), false);
        // CraftBukkit end
    }

    @Override
    public MapCodec<Ignite> codec() {
        return CODEC;
    }
}
