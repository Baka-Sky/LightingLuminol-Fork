package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.LlamaSpit;

public class CraftLlamaSpit extends AbstractProjectile implements LlamaSpit {

    public CraftLlamaSpit(CraftServer server, net.minecraft.world.entity.projectile.LlamaSpit entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.projectile.LlamaSpit getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.projectile.LlamaSpit) this.entity;
    }
}
