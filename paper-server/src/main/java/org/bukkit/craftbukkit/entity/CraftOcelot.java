package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Ocelot;

public class CraftOcelot extends CraftAnimals implements Ocelot {

    public CraftOcelot(CraftServer server, net.minecraft.world.entity.animal.feline.Ocelot ocelot) {
        super(server, ocelot);
    }

    @Override
    public net.minecraft.world.entity.animal.feline.Ocelot getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.animal.feline.Ocelot) this.entity;
    }

    @Override
    public boolean isTrusting() {
        return this.getHandle().isTrusting();
    }

    @Override
    public void setTrusting(boolean trust) {
        this.getHandle().setTrusting(trust);
    }

    @Override
    public Type getCatType() {
        return Type.WILD_OCELOT;
    }

    @Override
    public void setCatType(Type type) {
        throw new UnsupportedOperationException("Cats are now a different entity!");
    }
}
