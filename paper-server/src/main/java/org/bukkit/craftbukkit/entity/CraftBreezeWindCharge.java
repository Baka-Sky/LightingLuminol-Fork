package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.BreezeWindCharge;

public class CraftBreezeWindCharge extends CraftAbstractWindCharge implements BreezeWindCharge {
    public CraftBreezeWindCharge(CraftServer server, net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.BreezeWindCharge) this.entity;
    }
}
