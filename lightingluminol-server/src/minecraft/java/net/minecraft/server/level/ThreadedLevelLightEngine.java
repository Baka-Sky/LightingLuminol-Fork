package net.minecraft.server.level;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Util;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class ThreadedLevelLightEngine extends LevelLightEngine implements AutoCloseable, ca.spottedleaf.moonrise.patches.starlight.light.StarLightLightingProvider { // Paper - rewrite chunk system
    public static final int DEFAULT_BATCH_SIZE = 1000;
    private static final Logger LOGGER = LogUtils.getLogger();
    // Paper - rewrite chunk sytem
    private final ChunkMap chunkMap;
    // Paper - rewrite chunk sytem
    private final int taskPerBatch = 1000;
    // Paper - rewrite chunk sytem

    // Paper start - rewrite chunk system
    private final java.util.concurrent.atomic.AtomicLong chunkWorkCounter = new java.util.concurrent.atomic.AtomicLong();
    private void queueTaskForSection(final int chunkX, final int chunkY, final int chunkZ,
                                     final java.util.function.Supplier<ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LightQueue.ChunkTasks> supplier) {
        final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();

        final ChunkAccess center = this.starlight$getLightEngine().getAnyChunkNow(chunkX, chunkZ);
        if (center == null || !center.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.LIGHT)) {
            // do not accept updates in unlit chunks, unless we might be generating a chunk
            return;
        }

        final ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.ServerLightQueue.ServerChunkTasks scheduledTask = (ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.ServerLightQueue.ServerChunkTasks)supplier.get();

        if (scheduledTask == null) {
            // not scheduled
            return;
        }

        if (!scheduledTask.markTicketAdded()) {
            // ticket already added
            return;
        }

        final Long ticketId = Long.valueOf(this.chunkWorkCounter.getAndIncrement());
        final ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.CHUNK_WORK_TICKET, pos, ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LIGHT_TICKET_LEVEL, ticketId);

        scheduledTask.queueOrRunTask(() -> {
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.CHUNK_WORK_TICKET, pos, ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LIGHT_TICKET_LEVEL, ticketId);
        });
    }

    @Override
    public final int starlight$serverRelightChunks(final java.util.Collection<net.minecraft.world.level.ChunkPos> chunks0,
                                                   final java.util.function.Consumer<net.minecraft.world.level.ChunkPos> chunkLightCallback,
                                                   final java.util.function.IntConsumer onComplete) {
        final java.util.Set<net.minecraft.world.level.ChunkPos> chunks = new java.util.LinkedHashSet<>(chunks0);
        final java.util.Map<net.minecraft.world.level.ChunkPos, Long> ticketIds = new java.util.HashMap<>();
        final ServerLevel world = (ServerLevel)this.starlight$getLightEngine().getWorld();

        if (chunks.isEmpty()) {
            if (onComplete != null) {
                onComplete.accept(0);
            }
            return 0;
        }

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int maxX = Integer.MIN_VALUE;

        for (final java.util.Iterator<net.minecraft.world.level.ChunkPos> iterator = chunks.iterator(); iterator.hasNext();) {
            final ChunkPos pos = iterator.next();

            minX = Math.min(pos.x(), minX);
            minZ = Math.min(pos.z(), minZ);
            maxX = Math.max(pos.x(), maxX);
            maxZ = Math.max(pos.z(), maxZ);

            final Long id = ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.getNextChunkRelightId();
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.CHUNK_RELIGHT, pos, ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LIGHT_TICKET_LEVEL, id);
            ticketIds.put(pos, id);

            final ChunkAccess chunk = (ChunkAccess)world.getChunkSource().getChunkForLighting(pos.x(), pos.z());
            if (chunk == null || !chunk.isLightCorrect() || !chunk.getPersistedStatus().isOrAfter(net.minecraft.world.level.chunk.status.ChunkStatus.LIGHT)) {
                // cannot relight this chunk
                iterator.remove();
                ticketIds.remove(pos);
                ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.CHUNK_RELIGHT, pos, ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LIGHT_TICKET_LEVEL, id);
                continue;
            }
        }

        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().radiusAwareScheduler.queueTask(minX, minZ, maxX, maxZ, () -> {
            ThreadedLevelLightEngine.this.starlight$getLightEngine().relightChunks(
                chunks,
                (final ChunkPos pos) -> {
                    if (chunkLightCallback != null) {
                        chunkLightCallback.accept(pos);
                    }

                    ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().scheduleChunkTask(pos.x(), pos.z(), () -> {
                        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(
                            pos.x(), pos.z()
                        );

                        if (chunkHolder == null) {
                            return;
                        }

                        final java.util.List<ServerPlayer> players = ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)chunkHolder.vanillaChunkHolder).moonrise$getPlayers(false);

                        if (players.isEmpty()) {
                            return;
                        }

                        final net.minecraft.network.protocol.Packet<?> relightPacket = new net.minecraft.network.protocol.game.ClientboundLightUpdatePacket(
                            pos, (ThreadedLevelLightEngine)(Object)ThreadedLevelLightEngine.this,
                            null, null
                        );

                        for (final ServerPlayer player : players) {
                            final net.minecraft.server.network.ServerGamePacketListenerImpl conn = player.connection;
                            if (conn != null) {
                                conn.send(relightPacket);
                            }
                        }
                    });
                },
                (final int relight) -> {
                    if (onComplete != null) {
                        onComplete.accept(relight);
                    }

                    for (final java.util.Map.Entry<ChunkPos, Long> entry : ticketIds.entrySet()) {
                        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)world).moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.CHUNK_RELIGHT, entry.getKey(),
                            ca.spottedleaf.moonrise.patches.starlight.light.StarLightInterface.LIGHT_TICKET_LEVEL, entry.getValue()
                        );
                    }
                }
            );
        });

        return chunks.size();
    }
    // Paper end - rewrite chunk system

    public ThreadedLevelLightEngine(
        final LightChunkGetter lightChunkGetter,
        final ChunkMap chunkMap,
        final boolean hasSkyLight,
        final ConsecutiveExecutor consecutiveExecutor,
        final ChunkTaskDispatcher taskDispatcher
    ) {
        super(lightChunkGetter, true, hasSkyLight);
        this.chunkMap = chunkMap;
        // Paper - rewrite chunk system
    }

    @Override
    public void close() {
    }

    @Override
    public int runLightUpdates() {
        throw (UnsupportedOperationException)Util.pauseInIde(new UnsupportedOperationException("Ran automatically on a different thread!"));
    }

    @Override
    public void checkBlock(final BlockPos pos) {
        // Paper start - rewrite chunk system
        final BlockPos posCopy = pos.immutable();
        this.queueTaskForSection(posCopy.getX() >> 4, posCopy.getY() >> 4, posCopy.getZ() >> 4, () -> {
            return ThreadedLevelLightEngine.this.starlight$getLightEngine().blockChange(posCopy);
        });
        // Paper end - rewrite chunk system
    }

    protected void updateChunkStatus(final ChunkPos pos) {
        // Paper - rewrite chunk system
    }

    @Override
    public void updateSectionStatus(final SectionPos pos, final boolean sectionEmpty) {
        // Paper start - rewrite chunk system
        this.queueTaskForSection(pos.getX(), pos.getY(), pos.getZ(), () -> {
            return ThreadedLevelLightEngine.this.starlight$getLightEngine().sectionChange(pos, sectionEmpty);
        });
        // Paper end - rewrite chunk system
    }

    @Override
    public void propagateLightSources(final ChunkPos pos) {
        // Paper - rewrite chunk system
    }

    @Override
    public void setLightEnabled(final ChunkPos pos, final boolean enable) {
        // Paper - rewrite chunk system
    }

    @Override
    public void queueSectionData(final LightLayer layer, final SectionPos pos, final @Nullable DataLayer data) {
        // Paper - rewrite chunk system
    }

    private void addTask(final int chunkX, final int chunkZ, final ThreadedLevelLightEngine.TaskType type, final Runnable runnable) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private void addTask(final int chunkX, final int chunkZ, final IntSupplier level, final ThreadedLevelLightEngine.TaskType type, final Runnable runnable) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @Override
    public void retainData(final ChunkPos pos, final boolean retain) {
        // Paper start - rewrite chunk system
    }

    public CompletableFuture<ChunkAccess> initializeLight(final ChunkAccess chunk, final boolean lighted) {
        return CompletableFuture.completedFuture(chunk); // Paper start - rewrite chunk system
    }

    public CompletableFuture<ChunkAccess> lightChunk(final ChunkAccess centerChunk, final boolean lighted) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public void tryScheduleUpdate() {
        // Paper - rewrite chunk system
    }

    private void runUpdate() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public CompletableFuture<?> waitForPendingTasks(final int chunkX, final int chunkZ) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    private enum TaskType {
        PRE_UPDATE,
        POST_UPDATE;
    }
}
