package net.minecraft.world.item.component;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public interface ConsumableListener {
    void onConsume(final Level level, final LivingEntity user, final ItemStack stack, final Consumable consumable);

    default void cancelUsingItem(net.minecraft.server.level.ServerPlayer player, ItemStack stack, java.util.List<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> collectedPackets) {} // CraftBukkit // Paper - properly resend entities - collect packets for bundle
}
