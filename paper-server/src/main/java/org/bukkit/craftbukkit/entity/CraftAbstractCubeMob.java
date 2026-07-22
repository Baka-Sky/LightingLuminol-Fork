package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.AbstractCubeMob;

public abstract class CraftAbstractCubeMob extends CraftMob implements AbstractCubeMob {
    public CraftAbstractCubeMob(final CraftServer server, final net.minecraft.world.entity.monster.cubemob.AbstractCubeMob entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.monster.cubemob.AbstractCubeMob getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.monster.cubemob.AbstractCubeMob) this.entity;
    }

    @Override
    public int getSize() {
        return this.getHandle().getSize();
    }

    @Override
    public void setSize(int size) {
        this.getHandle().setSize(size, this.getHandle().isAlive());
    }

    @Override
    public boolean canWander() {
        return this.getHandle().canWander();
    }

    @Override
    public void setWander(boolean canWander) {
        this.getHandle().setWander(canWander);
    }
}
