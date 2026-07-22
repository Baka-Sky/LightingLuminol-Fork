package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.TraderLlama;

public class CraftTraderLlama extends CraftLlama implements TraderLlama {

    public CraftTraderLlama(CraftServer server, net.minecraft.world.entity.animal.equine.TraderLlama entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.animal.equine.TraderLlama getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.animal.equine.TraderLlama) this.entity;
    }
}
