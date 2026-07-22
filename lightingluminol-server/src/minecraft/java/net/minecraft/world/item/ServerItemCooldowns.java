package net.minecraft.world.item;

import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

public class ServerItemCooldowns extends ItemCooldowns {
    private final ServerPlayer player;

    public ServerItemCooldowns(final ServerPlayer player) {
        this.player = player;
    }

    // Paper start - Add PlayerItemCooldownEvent
    @Override
    public void addCooldown(ItemStack item, int duration) {
        final Identifier cooldownGroup = this.getCooldownGroup(item);
        final io.papermc.paper.event.player.PlayerItemCooldownEvent event = new io.papermc.paper.event.player.PlayerItemCooldownEvent(
            this.player.getBukkitEntity(),
            org.bukkit.craftbukkit.inventory.CraftItemType.minecraftToBukkit(item.getItem()),
            org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(cooldownGroup),
            duration
        );
        if (event.callEvent()) {
            this.addCooldown(cooldownGroup, event.getCooldown(), false);
        } else {
            this.player.connection.send(new ClientboundCooldownPacket(cooldownGroup, this.getRemainingCooldown(cooldownGroup)));
        }
    }

    @Override
    public void addCooldown(Identifier cooldownGroup, int time, boolean callEvent) {
        if (callEvent) {
            final io.papermc.paper.event.player.PlayerItemGroupCooldownEvent event = new io.papermc.paper.event.player.PlayerItemGroupCooldownEvent(
                this.player.getBukkitEntity(),
                org.bukkit.craftbukkit.util.CraftNamespacedKey.fromMinecraft(cooldownGroup),
                time
            );
            if (!event.callEvent()) {
                this.player.connection.send(new ClientboundCooldownPacket(cooldownGroup, this.getRemainingCooldown(cooldownGroup)));
                return;
            }

            time = event.getCooldown();
        }
        super.addCooldown(cooldownGroup, time, false);
    }
    // Paper end - Add PlayerItemCooldownEvent

    @Override
    protected void onCooldownStarted(final Identifier cooldownGroup, final int duration) {
        super.onCooldownStarted(cooldownGroup, duration);
        this.player.connection.send(new ClientboundCooldownPacket(cooldownGroup, duration));
    }

    @Override
    protected void onCooldownEnded(final Identifier cooldownGroup) {
        super.onCooldownEnded(cooldownGroup);
        this.player.connection.send(new ClientboundCooldownPacket(cooldownGroup, 0));
    }
}
