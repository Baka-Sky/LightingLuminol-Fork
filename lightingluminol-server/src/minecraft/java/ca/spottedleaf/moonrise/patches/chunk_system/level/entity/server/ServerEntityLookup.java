package ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server;

import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.entity.LevelCallback;

public final class ServerEntityLookup extends EntityLookup {

    private static final Entity[] EMPTY_ENTITY_ARRAY = new Entity[0];

    private final ServerLevel serverWorld;
    // Folia - move to regionized world data

    // Vanilla does not increment ticket timeouts if the chunk is progressing in generation. They made this change in 1.21.6 so that the ender pearl
    // ticket does not expire if the chunk fails to generate before the timeout expires. Rather than blindly adjusting the entire system behavior
    // to fix this small issue, we instead add non-expirable tickets here to keep ender pearls ticking. This is how the original feature should have
    // been implemented, but I don't think Vanilla has proper entity add/remove hooks like we do. Fixes MC-297591
    private static final TicketType ENDER_PEARL_TICKER = ChunkSystemTicketType.create("chunk_system:ender_pearl_ticker", Integer::compareTo, 0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE); // Folia - region threading
    private final Long2IntOpenHashMap enderPearlChunkCount = new Long2IntOpenHashMap();
    private final boolean keepEnderPearlsTicking;

    public ServerEntityLookup(final ServerLevel world, final LevelCallback<Entity> worldCallback) {
        super(world, worldCallback);
        this.serverWorld = world;
        this.keepEnderPearlsTicking = PlatformHooks.get().addTicketForEnderPearls(world);
    }

    @Override
    protected Boolean blockTicketUpdates() {
        return ((ChunkSystemServerLevel)this.serverWorld).moonrise$getChunkTaskScheduler().chunkHolderManager.blockTicketUpdates();
    }

    @Override
    protected void setBlockTicketUpdates(final Boolean value) {
        ((ChunkSystemServerLevel)this.serverWorld).moonrise$getChunkTaskScheduler().chunkHolderManager.unblockTicketUpdates(value);
    }

    @Override
    protected void checkThread(final int chunkX, final int chunkZ, final String reason) {
        TickThread.ensureTickThread(this.serverWorld, chunkX, chunkZ, reason);
    }

    @Override
    protected void checkThread(final Entity entity, final String reason) {
        TickThread.ensureTickThread(entity, reason);
    }

    @Override
    protected ChunkEntitySlices createEntityChunk(final int chunkX, final int chunkZ, final boolean transientChunk) {
        // loadInEntityChunk will call addChunk for us
        return ((ChunkSystemServerLevel)this.serverWorld).moonrise$getChunkTaskScheduler().chunkHolderManager
                .getOrCreateEntityChunk(chunkX, chunkZ, transientChunk);
    }

    @Override
    protected void onEmptySlices(final int chunkX, final int chunkZ) {
        // entity slices unloading is managed by ticket levels in chunk system
    }

    @Override
    protected void entitySectionChangeCallback(final Entity entity,
                                               final int oldSectionX, final int oldSectionY, final int oldSectionZ,
                                               final int newSectionX, final int newSectionY, final int newSectionZ) {
        if (entity instanceof ServerPlayer player) {
            ((ChunkSystemServerLevel)this.serverWorld).moonrise$getNearbyPlayers().tickPlayer(player);
        }
        if (entity instanceof ThrownEnderpearl enderpearl && (oldSectionX != newSectionX || oldSectionZ != newSectionZ)) {
            this.removeEnderPearl(CoordinateUtils.getChunkKey(oldSectionX, oldSectionZ), enderpearl.getId()); // Folia - region threading
            this.addEnderPearl(CoordinateUtils.getChunkKey(newSectionX, newSectionZ), enderpearl.getId()); // Folia - region threading
        }
        PlatformHooks.get().entityMove(
            entity,
            CoordinateUtils.getChunkSectionKey(oldSectionX, oldSectionY, oldSectionZ),
            CoordinateUtils.getChunkSectionKey(newSectionX, newSectionY, newSectionZ)
        );
    }

    @Override
    protected void addEntityCallback(final Entity entity) {
        this.world.getCurrentWorldData().addEntity(entity); // Folia - region threading
        if (entity instanceof ServerPlayer player) {
            ((ChunkSystemServerLevel)this.serverWorld).moonrise$getNearbyPlayers().addPlayer(player);
        }
        if (entity instanceof ThrownEnderpearl enderpearl) {
            this.addEnderPearl(CoordinateUtils.getChunkKey(enderpearl.chunkPosition()), enderpearl.getId()); // Folia - region threading
        }
        entity.registerScheduler(); // Paper - optimise Folia entity scheduler
    }

    @Override
    protected void removeEntityCallback(final Entity entity) {
        if (entity instanceof ServerPlayer player) {
            ((ChunkSystemServerLevel)this.serverWorld).moonrise$getNearbyPlayers().removePlayer(player);
        }
        if (entity instanceof ThrownEnderpearl enderpearl) {
            this.removeEnderPearl(CoordinateUtils.getChunkKey(enderpearl.chunkPosition()), enderpearl.getId()); // Folia - region threading
        }
        entity.getBukkitEntity().taskScheduler.registerTo(null); // Folia - region threading
    }

    @Override
    protected void entityStartLoaded(final Entity entity) {
        // Moonrise start - entity tracker
        this.world.getCurrentWorldData().trackerEntities.add(entity); // Folia - region threading
        // Moonrise end - entity tracker
    }

    @Override
    protected void entityEndLoaded(final Entity entity) {
        // Moonrise start - entity tracker
        this.world.getCurrentWorldData().trackerEntities.remove(entity); // Folia - region threading
        // Moonrise end - entity tracker
    }

    @Override
    protected void entityStartTicking(final Entity entity) {

    }

    @Override
    protected void entityEndTicking(final Entity entity) {

    }

    @Override
    protected boolean screenEntity(final Entity entity, final boolean fromDisk, final boolean event) {
        return PlatformHooks.get().screenEntity(this.serverWorld, entity, fromDisk, event);
    }

    private void addEnderPearl(final long coordinate, final int entityId) { // Folia - region threading
        if (!this.keepEnderPearlsTicking) {
            return;
        }
        // Folia - region threading
        ((ChunkSystemServerLevel)this.serverWorld).moonrise$getChunkTaskScheduler().chunkHolderManager
            .addTicketAtLevel(ENDER_PEARL_TICKER, coordinate, ChunkHolderManager.ENTITY_TICKING_TICKET_LEVEL, Integer.valueOf(entityId)); // Folia - region threading
    }

    private void removeEnderPearl(final long coordinate, final int entityId) { // Folia - region threading
        if (!this.keepEnderPearlsTicking) {
            return;
        }

        // Folia - region threading
        ((ChunkSystemServerLevel)this.serverWorld).moonrise$getChunkTaskScheduler().chunkHolderManager
            .removeTicketAtLevel(ENDER_PEARL_TICKER, coordinate, ChunkHolderManager.ENTITY_TICKING_TICKET_LEVEL, Integer.valueOf(entityId)); // Folia - region threading
    }
}
