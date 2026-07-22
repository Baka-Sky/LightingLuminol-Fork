package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.IronGolem;

public class CraftIronGolem extends CraftGolem implements IronGolem {
    public CraftIronGolem(CraftServer server, net.minecraft.world.entity.animal.golem.IronGolem entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.animal.golem.IronGolem getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.animal.golem.IronGolem) this.entity;
    }

    @Override
    public boolean isPlayerCreated() {
        return this.getHandle().isPlayerCreated();
    }

    @Override
    public void setPlayerCreated(boolean playerCreated) {
        this.getHandle().setPlayerCreated(playerCreated);
    }
}
