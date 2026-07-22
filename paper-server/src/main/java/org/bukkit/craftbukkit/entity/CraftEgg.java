package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Egg;

public class CraftEgg extends CraftThrowableProjectile implements Egg {

    public CraftEgg(CraftServer server, ThrownEgg entity) {
        super(server, entity);
    }

    @Override
    public ThrownEgg getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (ThrownEgg) this.entity;
    }
}
