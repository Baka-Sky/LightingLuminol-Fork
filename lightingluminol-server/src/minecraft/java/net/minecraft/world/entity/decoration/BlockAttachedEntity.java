package net.minecraft.world.entity.decoration;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public abstract class BlockAttachedEntity extends Entity {
    private static final Logger LOGGER = LogUtils.getLogger();
    private int checkInterval; { this.checkInterval = this.getId() % this.level().spigotConfig.hangingTickFrequency; } // Paper - Perf: offset item frame ticking
    protected BlockPos pos;

    protected BlockAttachedEntity(final EntityType<? extends BlockAttachedEntity> type, final Level level) {
        super(type, level);
    }

    protected BlockAttachedEntity(final EntityType<? extends BlockAttachedEntity> type, final Level level, final BlockPos pos) {
        this(type, level);
        this.pos = pos;
    }

    protected abstract void recalculateBoundingBox();

    @Override
    public void tick() {
        if (this.level() instanceof ServerLevel level) {
            this.checkBelowWorld();
            if (this.checkInterval++ == this.level().spigotConfig.hangingTickFrequency) { // Spigot
                this.checkInterval = 0;
                if (!this.isRemoved() && !this.survives()) {
                    // CraftBukkit start - fire break events
                    final org.bukkit.event.hanging.HangingBreakEvent.RemoveCause cause;
                    if (!this.level().getBlockState(this.blockPosition()).isAir()) {
                        // TODO: This feels insufficient to catch 100% of suffocation cases
                        cause = org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.OBSTRUCTION;
                    } else {
                        cause = org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.PHYSICS;
                    }

                    org.bukkit.event.hanging.HangingBreakEvent event = new org.bukkit.event.hanging.HangingBreakEvent((org.bukkit.entity.Hanging) this.getBukkitEntity(), cause);
                    this.level().getCraftServer().getPluginManager().callEvent(event);

                    if (this.isRemoved() || event.isCancelled()) {
                        return;
                    }
                    // CraftBukkit end
                    this.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.DROP); // CraftBukkit - add Bukkit remove cause
                    this.dropItem(level, null);
                }
            }
        }
    }

    public abstract boolean survives();

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean skipAttackInteraction(final Entity source) {
        return source instanceof Player player
            && (!this.level().mayInteract(player, this.pos) || this.hurtOrSimulate(this.damageSources().playerAttack(player), 0.0F));
    }

    @Override
    public boolean hurtClient(final DamageSource source) {
        return !this.isInvulnerableToBase(source);
    }

    @Override
    public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
        if (this.isInvulnerableToBase(source)) {
            return false;
        }

        if (!level.getGameRules().get(GameRules.MOB_GRIEFING) && source.getEntity() instanceof Mob) {
            return false;
        }

        if (!this.isRemoved()) {
            // CraftBukkit start - fire break events
            Entity damager = source.getDirectEntity();
            final org.bukkit.event.hanging.HangingBreakEvent event;
            if (damager != null) {
                event = new org.bukkit.event.hanging.HangingBreakByEntityEvent((org.bukkit.entity.Hanging) this.getBukkitEntity(), damager.getBukkitEntity(), new org.bukkit.craftbukkit.damage.CraftDamageSource(source), source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.EXPLOSION : org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.ENTITY);
            } else {
                event = new org.bukkit.event.hanging.HangingBreakEvent((org.bukkit.entity.Hanging) this.getBukkitEntity(), source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) ? org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.EXPLOSION : org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.DEFAULT);
            }

            event.callEvent();

            if (this.isRemoved() || event.isCancelled()) {
                return true;
            }
            // CraftBukkit end
            this.kill(level);
            this.markHurt();
            this.dropItem(level, source.getEntity());
        }

        return true;
    }

    @Override
    public boolean ignoreExplosion(final Explosion explosion) {
        Entity directEntity = explosion.getDirectSourceEntity();
        return directEntity != null && directEntity.isInWater() || !explosion.shouldAffectBlocklikeEntities() || super.ignoreExplosion(explosion);
    }

    @Override
    public void move(final MoverType moverType, final Vec3 delta) {
        if (this.level() instanceof ServerLevel level && !this.isRemoved() && delta.lengthSqr() > 0.0) {
            // CraftBukkit start - fire break events
            // TODO - Does this need its own cause? Seems to only be triggered by pistons
            org.bukkit.event.hanging.HangingBreakEvent event = new org.bukkit.event.hanging.HangingBreakEvent((org.bukkit.entity.Hanging) this.getBukkitEntity(), org.bukkit.event.hanging.HangingBreakEvent.RemoveCause.PHYSICS);
            this.level().getCraftServer().getPluginManager().callEvent(event);

            if (this.isRemoved() || event.isCancelled()) {
                return;
            }
            // CraftBukkit end
            this.kill(level);
            this.dropItem(level, null);
        }
    }

    @Override
    public void push(final double xa, final double ya, final double za, @Nullable final Entity pushingEntity) { // Paper - override correct overload
        if (false && this.level() instanceof ServerLevel level && !this.isRemoved() && xa * xa + ya * ya + za * za > 0.0) { // CraftBukkit - not needed
            this.kill(level);
            this.dropItem(level, null);
        }
    }

    // CraftBukkit start - selectively save tile position
    @Override
    protected void addAdditionalSaveData(final net.minecraft.world.level.storage.ValueOutput output, final boolean includeAll) {
        if (includeAll) {
            this.addAdditionalSaveData(output);
        }
    }
    // CraftBukkit end

    @Override
    protected void addAdditionalSaveData(final ValueOutput output) {
        output.store("block_pos", BlockPos.CODEC, this.getPos());
    }

    @Override
    protected void readAdditionalSaveData(final ValueInput input) {
        BlockPos storedPos = input.read("block_pos", BlockPos.CODEC).orElse(null);
        if (storedPos != null && storedPos.closerThan(this.blockPosition(), 16.0)) {
            this.pos = storedPos;
        } else {
            LOGGER.error("Block-attached entity at invalid position: {}", storedPos);
        }
    }

    public abstract void dropItem(ServerLevel level, @Nullable Entity causedBy);

    @Override
    protected boolean repositionEntityAfterLoad() {
        return false;
    }

    @Override
    public void setPos(final double x, final double y, final double z) {
        this.pos = BlockPos.containing(x, y, z);
        this.recalculateBoundingBox();
        this.needsSync = true;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    @Override
    public void thunderHit(final ServerLevel level, final LightningBolt lightningBolt) {
    }

    @Override
    public void refreshDimensions() {
    }
}
