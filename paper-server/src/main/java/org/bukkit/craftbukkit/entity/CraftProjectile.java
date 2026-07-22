package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Projectile;

public abstract class CraftProjectile extends AbstractProjectile implements Projectile {

    public CraftProjectile(CraftServer server, net.minecraft.world.entity.projectile.Projectile entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.projectile.Projectile getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.projectile.Projectile) this.entity;
    }
}
