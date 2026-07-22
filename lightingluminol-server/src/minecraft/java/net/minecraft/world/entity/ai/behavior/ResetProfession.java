package net.minecraft.world.entity.ai.behavior;

import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

public class ResetProfession {
    public static BehaviorControl<Villager> create() {
        return BehaviorBuilder.create(i -> i.group(i.absent(MemoryModuleType.JOB_SITE)).apply(i, jobSite -> (level, body, timestamp) -> {
            VillagerData bodyData = body.getVillagerData();
            boolean canBeFired = !bodyData.profession().is(VillagerProfession.NONE) && !bodyData.profession().is(VillagerProfession.NITWIT);
            if (canBeFired && body.getVillagerXp() == 0 && bodyData.level() <= 1) {
                // CraftBukkit start
                org.bukkit.event.entity.VillagerCareerChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callVillagerCareerChangeEvent(body, org.bukkit.craftbukkit.entity.CraftVillager.CraftProfession.minecraftHolderToBukkit(level.registryAccess().getOrThrow(VillagerProfession.NONE)), org.bukkit.event.entity.VillagerCareerChangeEvent.ChangeReason.LOSING_JOB);
                if (event.isCancelled()) {
                    return false;
                }

                body.setVillagerData(body.getVillagerData().withProfession(org.bukkit.craftbukkit.entity.CraftVillager.CraftProfession.bukkitToMinecraftHolder(event.getProfession())));
                // CraftBukkit end
                body.refreshBrain(level);
                return true;
            } else {
                return false;
            }
        }));
    }
}
