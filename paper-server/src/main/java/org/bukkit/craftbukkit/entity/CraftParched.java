package org.bukkit.craftbukkit.entity;

import net.minecraft.world.entity.monster.skeleton.Parched;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Skeleton;

public class CraftParched extends CraftAbstractSkeleton implements org.bukkit.entity.Parched {
    public CraftParched(final CraftServer server, final Parched entity) {
        super(server, entity);
    }

    @Override
    public Parched getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (Parched) this.entity;
    }

    @Override
    public Skeleton.SkeletonType getSkeletonType() {
        return Skeleton.SkeletonType.PARCHED;
    }
}
