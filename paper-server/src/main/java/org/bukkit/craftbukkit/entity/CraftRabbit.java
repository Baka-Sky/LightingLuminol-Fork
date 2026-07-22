package org.bukkit.craftbukkit.entity;

import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.entity.Rabbit;

public class CraftRabbit extends CraftAnimals implements Rabbit {

    public CraftRabbit(CraftServer server, net.minecraft.world.entity.animal.rabbit.Rabbit entity) {
        super(server, entity);
    }

    @Override
    public net.minecraft.world.entity.animal.rabbit.Rabbit getHandle() {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.entity, "Accessing entity state off owning region's thread"); // Folia - region threading
        return (net.minecraft.world.entity.animal.rabbit.Rabbit) this.entity;
    }

    @Override
    public Type getRabbitType() {
        return Type.values()[this.getHandle().getVariant().ordinal()];
    }

    @Override
    public void setRabbitType(Type type) {
        this.getHandle().setVariant(net.minecraft.world.entity.animal.rabbit.Rabbit.Variant.values()[type.ordinal()]);
    }

    @Override
    public void setMoreCarrotTicks(int ticks) {
        this.getHandle().moreCarrotTicks = ticks;
    }

    @Override
    public int getMoreCarrotTicks() {
        return this.getHandle().moreCarrotTicks;
    }
}
