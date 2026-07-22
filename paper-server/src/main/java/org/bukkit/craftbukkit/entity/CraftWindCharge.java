package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.WindCharge;

public class CraftWindCharge extends CraftAbstractWindCharge implements WindCharge {

    public CraftWindCharge(CraftServer server, net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge) this.entity;
    }
}
