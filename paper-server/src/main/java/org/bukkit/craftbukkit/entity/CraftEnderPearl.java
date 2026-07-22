package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.EnderPearl;

public class CraftEnderPearl extends CraftThrowableProjectile implements EnderPearl {
    public CraftEnderPearl(CraftServer server, ThrownEnderpearl entity) {
        super(server, entity);
    }

    @Override
    public ThrownEnderpearl getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (ThrownEnderpearl) this.entity;
    }
}
