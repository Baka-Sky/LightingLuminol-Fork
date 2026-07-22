package net.minecraft.world.entity;

import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

public record ConversionParams(ConversionType type, boolean keepEquipment, boolean preserveCanPickUpLoot, @Nullable PlayerTeam team) {
    public static ConversionParams single(final Mob mob, final boolean keepEquipment, final boolean preserveCanPickUpLoot) {
        return new ConversionParams(ConversionType.SINGLE, keepEquipment, preserveCanPickUpLoot, mob.getTeam());
    }

    @FunctionalInterface
    public interface AfterConversion<T extends Mob> {
        void finalizeConversion(T mob);
    }

    // Paper start - entity zap event - allow conversion to be cancelled during finalization
    @FunctionalInterface
    public interface CancellingAfterConversion<T extends Mob> {
        boolean finalizeConversionOrCancel(final T convertedEntity);
    }
    // Paper start - entity zap event - allow conversion to be cancelled during finalization
}
