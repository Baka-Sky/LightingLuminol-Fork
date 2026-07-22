package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import org.bukkit.craftbukkit.CraftServer;

public abstract class CraftMinecartContainer extends CraftMinecart implements com.destroystokyo.paper.loottable.PaperLootableEntityInventory { // Paper

    public CraftMinecartContainer(CraftServer server, AbstractMinecart entity) {
        super(server, entity);
    }

    @Override
    public AbstractMinecartContainer getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (AbstractMinecartContainer) this.entity;
    }
}
