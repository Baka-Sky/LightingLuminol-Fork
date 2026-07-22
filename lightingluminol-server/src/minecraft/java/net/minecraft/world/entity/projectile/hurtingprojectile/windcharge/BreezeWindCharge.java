package net.minecraft.world.entity.projectile.hurtingprojectile.windcharge;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class BreezeWindCharge extends AbstractWindCharge {
    private static final float RADIUS = 3.0F;

    public BreezeWindCharge(final EntityType<? extends AbstractWindCharge> type, final Level level) {
        super(type, level);
    }

    public BreezeWindCharge(final Breeze breeze, final Level level) {
        super(EntityTypes.BREEZE_WIND_CHARGE, level, breeze, breeze.getX(), breeze.getFiringYPosition(), breeze.getZ());
    }

    @Override
    public void explode(final Vec3 position) {
        // Paper start - Fire event for WindCharge explosions
        org.bukkit.event.entity.ExplosionPrimeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callExplosionPrimeEvent(this, RADIUS, false);
        if (event.isCancelled()) {
            return;
        }
        // Paper end - Fire event for WindCharge explosions
        this.level()
            .explode(
                this,
                null,
                EXPLOSION_DAMAGE_CALCULATOR,
                position.x(),
                position.y(),
                position.z(),
                event.getRadius(), // Paper - Fire event for WindCharge explosions
                event.getFire(), // Paper - Fire event for WindCharge explosions
                Level.ExplosionInteraction.TRIGGER,
                ParticleTypes.GUST_EMITTER_SMALL,
                ParticleTypes.GUST_EMITTER_LARGE,
                WeightedList.of(),
                SoundEvents.BREEZE_WIND_CHARGE_BURST
            );
    }
}
