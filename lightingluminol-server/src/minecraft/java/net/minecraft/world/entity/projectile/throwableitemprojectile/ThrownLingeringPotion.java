package net.minecraft.world.entity.projectile.throwableitemprojectile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

public class ThrownLingeringPotion extends AbstractThrownPotion {
    public ThrownLingeringPotion(final EntityType<? extends ThrownLingeringPotion> type, final Level level) {
        super(type, level);
    }

    public ThrownLingeringPotion(final Level level, final LivingEntity owner, final ItemStack itemStack) {
        super(EntityTypes.LINGERING_POTION, level, owner, itemStack);
    }

    public ThrownLingeringPotion(final Level level, final double x, final double y, final double z, final ItemStack itemStack) {
        super(EntityTypes.LINGERING_POTION, level, x, y, z, itemStack);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.LINGERING_POTION;
    }

    @Override
    public boolean onHitAsPotion(final ServerLevel level, final ItemStack potionItem, final @org.jspecify.annotations.Nullable HitResult hitResult) { // Paper - More projectile API
        AreaEffectCloud cloud = new AreaEffectCloud(this.level(), this.getX(), this.getY(), this.getZ());
        if (this.getOwner() instanceof LivingEntity owner) {
            cloud.setOwner(owner);
        }

        cloud.setRadius(3.0F);
        cloud.setRadiusOnUse(-0.5F);
        cloud.setDuration(600);
        cloud.setWaitTime(10);
        cloud.setRadiusPerTick(-cloud.getRadius() / cloud.getDuration());
        cloud.applyComponentsFromItemStack(potionItem);
        boolean noEffects = this.getItem().getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS, net.minecraft.world.item.alchemy.PotionContents.EMPTY).hasEffects(); // Paper - Fix potions splash events
        // CraftBukkit start
        org.bukkit.event.entity.LingeringPotionSplashEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callLingeringPotionSplashEvent(this, hitResult, cloud);
        if (!(event.isCancelled() || cloud.isRemoved() || (!event.allowsEmptyCreation() && (noEffects && !cloud.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS).hasEffects())))) { // Paper - don't spawn area effect cloud if the effects were empty and not changed during the event handling
        level.addFreshEntity(cloud);
        } else {
            cloud.discard(null);
        }
        // CraftBukkit end
        return !event.isCancelled(); // Paper - Fix potions splash events
    }
}
