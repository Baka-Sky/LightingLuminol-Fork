package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public class EndPortalBlock extends BaseEntityBlock implements Portal {
    public static final MapCodec<EndPortalBlock> CODEC = simpleCodec(EndPortalBlock::new);
    private static final VoxelShape SHAPE = Block.column(16.0, 6.0, 12.0);

    @Override
    public MapCodec<EndPortalBlock> codec() {
        return CODEC;
    }

    protected EndPortalBlock(final BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(final BlockPos worldPosition, final BlockState blockState) {
        return new TheEndPortalBlockEntity(worldPosition, blockState);
    }

    @Override
    protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getEntityInsideCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final Entity entity) {
        return state.getShape(level, pos);
    }

    @Override
    protected void entityInside(
        final BlockState state,
        final Level level,
        final BlockPos pos,
        final Entity entity,
        final InsideBlockEffectApplier effectApplier,
        final boolean isPrecise
    ) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(level, pos)).callEvent()) { return; } // Paper - Add EntityInsideBlockEvent
        if (entity.canUsePortal(false)) {
            // CraftBukkit start - Entity in portal
            org.bukkit.event.entity.EntityPortalEnterEvent event = new org.bukkit.event.entity.EntityPortalEnterEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.util.CraftLocation.toBukkit(pos, level), org.bukkit.PortalType.ENDER); // Paper - add portal type
            if (!event.callEvent()) return; // Paper - make cancellable
            // CraftBukkit end
            if (false && !level.isClientSide() && level.dimension() == Level.END && entity instanceof ServerPlayer player && !player.seenCredits) { // Folia - region threading - do not show credits
                if (level.paperConfig().misc.disableEndCredits) {player.seenCredits = true; return;} // Paper - Option to disable end credits
                player.showEndCredits();
            } else {
                // LightingLuminol start - unsafe teleportation
                if (meow.bacteriawa.lightingluminol.config.FixesConfig.AllowUnsafeTeleportation.enabled && !(entity instanceof net.minecraft.world.entity.player.Player)) {
                    entity.endPortalLogicAsync(pos);
                    return;
                }
                // LightingLuminol end
                entity.setAsInsidePortal(this, pos);
            }
        }
    }

    @Override
    public @Nullable TeleportTransition getPortalDestination(final ServerLevel currentLevel, final Entity entity, final BlockPos portalEntryPos) {
        LevelData.RespawnData respawnData = currentLevel.getRespawnData();
        boolean fromEnd = currentLevel.getTypeKey() == net.minecraft.world.level.dimension.LevelStem.END; // CraftBukkit - SPIGOT-6152: send back to main overworld in custom ends
        ResourceKey<Level> newDimension = fromEnd ? respawnData.dimension() : Level.END;
        BlockPos spawnBlockPos = fromEnd ? respawnData.pos() : ServerLevel.END_SPAWN_POINT;
        ServerLevel newLevel = currentLevel.getServer().getLevel(newDimension);
        if (newLevel == null) {
            return null;
        }

        Vec3 spawnPos = Vec3.atBottomCenterOf(spawnBlockPos);
        float yRot;
        float xRot;
        Set<Relative> relatives;
        if (!fromEnd) {
            EndPlatformFeature.createEndPlatform(newLevel, BlockPos.containing(spawnPos).below(), true, entity); // CraftBukkit
            yRot = Direction.WEST.toYRot();
            xRot = 0.0F;
            relatives = Relative.union(Relative.DELTA, Set.of(Relative.X_ROT));
            if (entity instanceof ServerPlayer) {
                spawnPos = spawnPos.subtract(0.0, 1.0, 0.0);
            }
        } else {
            yRot = respawnData.yaw();
            xRot = respawnData.pitch();
            relatives = Relative.union(Relative.DELTA, Relative.ROTATION);
            if (entity instanceof ServerPlayer serverPlayer) {
                return serverPlayer.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING, org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.END_PORTAL); // CraftBukkit
            }

            spawnPos = Vec3.atBottomCenterOf(entity.adjustSpawnLocation(newLevel, spawnBlockPos));
        }

        // CraftBukkit start
        relatives.removeAll(Relative.ROTATION); // remove relative rotation flags to simplify event mutation
        float absoluteYaw = !fromEnd ? yRot : entity.getYRot() + yRot;
        float absolutePitch = entity.getXRot() + xRot;
        org.bukkit.craftbukkit.event.PortalEventResult result = org.bukkit.craftbukkit.event.CraftEventFactory.handlePortalEvents(entity, org.bukkit.craftbukkit.util.CraftLocation.toBukkit(spawnPos, newLevel, absoluteYaw, absolutePitch), org.bukkit.PortalType.ENDER, 0, 0);
        if (result == null) {
            return null;
        }
        org.bukkit.Location to = result.to();

        return new TeleportTransition(
            ((org.bukkit.craftbukkit.CraftWorld) to.getWorld()).getHandle(), org.bukkit.craftbukkit.util.CraftLocation.toVec3(to), Vec3.ZERO, to.getYaw(), to.getPitch(), relatives, TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.END_PORTAL
        );
        // CraftBukkit end
    }

    // Folia start - region threading
    @Override
    public boolean portalAsync(ServerLevel sourceWorld, Entity portalTarget, BlockPos portalPos) {
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(portalTarget)) {
            return false;
        }
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(sourceWorld, portalPos)) {
            return false;
        }

        return portalTarget.endPortalLogicAsync(portalPos);
    }
    // Folia end - region threading

    @Override
    public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
        double x = pos.getX() + random.nextDouble();
        double y = pos.getY() + 0.8;
        double z = pos.getZ() + random.nextDouble();
        level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.0, 0.0);
    }

    @Override
    protected ItemStack getCloneItemStack(final LevelReader level, final BlockPos pos, final BlockState state, final boolean includeData) {
        return ItemStack.EMPTY;
    }

    @Override
    protected boolean canBeReplaced(final BlockState state, final Fluid fluid) {
        return false;
    }

    @Override
    protected RenderShape getRenderShape(final BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
