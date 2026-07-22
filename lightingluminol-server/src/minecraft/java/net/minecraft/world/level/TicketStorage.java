package net.minecraft.world.level;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public class TicketStorage extends SavedData implements ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketStorage { // Paper - rewrite chunk system
    private static final int INITIAL_TICKET_LIST_CAPACITY = 4;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Codec<Pair<ChunkPos, Ticket>> TICKET_ENTRY = Codec.mapPair(ChunkPos.CODEC.fieldOf("chunk_pos"), Ticket.CODEC).codec();
    public static final Codec<TicketStorage> CODEC = RecordCodecBuilder.create(
        i -> i.group(TICKET_ENTRY.listOf().optionalFieldOf("tickets", List.of()).forGetter(TicketStorage::packTickets)).apply(i, TicketStorage::fromPacked)
    );
    public static final SavedDataType<TicketStorage> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace("chunk_tickets"), TicketStorage::new, CODEC, DataFixTypes.SAVED_DATA_FORCED_CHUNKS
    );
    // Paper - rewrite chunk system
    private final Long2ObjectOpenHashMap<List<Ticket>> deactivatedTickets;
    // Paper - rewrite chunk system
    private TicketStorage.@Nullable ChunkUpdated loadingChunkUpdatedListener;
    private TicketStorage.@Nullable ChunkUpdated simulationChunkUpdatedListener;

    // Paper start - rewrite chunk system
    private ChunkMap chunkMap;

    @Override
    public final ChunkMap moonrise$getChunkMap() {
        return this.chunkMap;
    }

    @Override
    public final void moonrise$setChunkMap(final ChunkMap chunkMap) {
        this.chunkMap = chunkMap;
    }
    // Paper end - rewrite chunk system

    private TicketStorage(final Long2ObjectOpenHashMap<List<Ticket>> tickets, final Long2ObjectOpenHashMap<List<Ticket>> deactivatedTickets) {
        // Paper - rewrite chunk system
        this.deactivatedTickets = deactivatedTickets;
        // Paper - rewrite chunk system
    }

    public TicketStorage() {
        this(new Long2ObjectOpenHashMap<>(4), new Long2ObjectOpenHashMap<>());
    }

    private static TicketStorage fromPacked(final List<Pair<ChunkPos, Ticket>> tickets) {
        Long2ObjectOpenHashMap<List<Ticket>> ticketsToLoad = new Long2ObjectOpenHashMap<>();

        for (Pair<ChunkPos, Ticket> ticket : tickets) {
            ChunkPos pos = ticket.getFirst();
            List<Ticket> ticketsInChunk = ticketsToLoad.computeIfAbsent(pos.pack(), k -> new ObjectArrayList<>(4));
            ticketsInChunk.add(ticket.getSecond());
        }

        return new TicketStorage(new Long2ObjectOpenHashMap<>(4), ticketsToLoad);
    }

    private List<Pair<ChunkPos, Ticket>> packTickets() {
        List<Pair<ChunkPos, Ticket>> tickets = new ArrayList<>();
        this.forEachTicket((pos, ticket) -> {
            if (ticket.getType().persist()) {
                tickets.add(new Pair<>(pos, ticket));
            }
        });
        return tickets;
    }

    // Paper start - rewrite chunk system
    private void redirectRegularTickets(final BiConsumer<ChunkPos, Ticket> consumer, final Long2ObjectOpenHashMap<List<Ticket>> ticketsParam) {
        if (ticketsParam != null) {
            throw new IllegalStateException("Bad injection point");
        }

        final Long2ObjectOpenHashMap<java.util.Collection<net.minecraft.server.level.Ticket>> tickets = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.chunkMap.level)
            .moonrise$getChunkTaskScheduler().chunkHolderManager.getTicketsCopy();

        for (final Iterator<it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<java.util.Collection<net.minecraft.server.level.Ticket>>> iterator = tickets.long2ObjectEntrySet().fastIterator(); iterator.hasNext();) {
            final it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<java.util.Collection<net.minecraft.server.level.Ticket>> entry = iterator.next();

            final long pos = entry.getLongKey();
            final java.util.Collection<net.minecraft.server.level.Ticket> chunkTickets = entry.getValue();

            final ChunkPos chunkPos = ChunkPos.unpack(pos);

            for (final Ticket ticket : chunkTickets) {
                consumer.accept(chunkPos, ticket);
            }
        }
    }
    // Paper end - rewrite chunk system

    private void forEachTicket(final BiConsumer<ChunkPos, Ticket> output) {
        this.redirectRegularTickets(output, null); // Paper - rewrite chunk system
        forEachTicket(output, this.deactivatedTickets);
    }

    private static void forEachTicket(final BiConsumer<ChunkPos, Ticket> output, final Long2ObjectOpenHashMap<List<Ticket>> tickets) {
        for (Entry<List<Ticket>> entry : Long2ObjectMaps.fastIterable(tickets)) {
            ChunkPos chunkPos = ChunkPos.unpack(entry.getLongKey());

            for (Ticket ticket : entry.getValue()) {
                output.accept(chunkPos, ticket);
            }
        }
    }

    public void activateAllDeactivatedTickets() {
        for (Entry<List<Ticket>> entry : Long2ObjectMaps.fastIterable(this.deactivatedTickets)) {
            for (Ticket ticket : entry.getValue()) {
                this.addTicket(entry.getLongKey(), ticket);
            }
        }

        this.deactivatedTickets.clear();
    }

    public void setLoadingChunkUpdatedListener(final TicketStorage.@Nullable ChunkUpdated loadingChunkUpdatedListener) {
        // Paper - rewrite chunk system
    }

    public void setSimulationChunkUpdatedListener(final TicketStorage.@Nullable ChunkUpdated simulationChunkUpdatedListener) {
        // Paper - rewrite chunk system
    }

    public boolean hasTickets() {
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.chunkMap.level).moonrise$getChunkTaskScheduler().chunkHolderManager.hasTickets(); // Paper - rewrite chunk system
    }

    public boolean shouldKeepDimensionActive() {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2LongHashTable ticketCounters = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.chunkMap.level).moonrise$getChunkTaskScheduler().chunkHolderManager
            .getTicketCounters(ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType.COUNTER_TYPE_KEEP_DIMENSION_ACTIVE);
        return ticketCounters != null && !ticketCounters.isEmpty();
        // Paper end - rewrite chunk system
    }

    public List<Ticket> getTickets(final long key) {
        // Paper start - rewrite chunk system
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.chunkMap.level).moonrise$getChunkTaskScheduler().chunkHolderManager
            .getTicketsAt(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(key), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(key));
        // Paper end - rewrite chunk system
    }

    private List<Ticket> getOrCreateTickets(final long key) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public void addTicketWithRadius(final TicketType type, final ChunkPos chunkPos, final int radius) {
        Ticket ticket = new Ticket(type, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius);
        this.addTicket(chunkPos.pack(), ticket);
    }

    public void addTicket(final Ticket ticket, final ChunkPos chunkPos) {
        this.addTicket(chunkPos.pack(), ticket);
    }

    public boolean addTicket(final long key, final Ticket ticket) {
        // Paper start - rewrite chunk system
        final boolean ret = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.chunkMap.level).moonrise$getChunkTaskScheduler().chunkHolderManager
            .addTicketAtLevel(ticket.getType(), key, ticket.getTicketLevel(), ((ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicket<?>)ticket).moonrise$getIdentifier());

        this.setDirty();

        return ret;
        // Paper end - rewrite chunk system
    }

    private static boolean isTicketSameTypeAndLevel(final Ticket ticket, final Ticket t) {
        return t.getType() == ticket.getType() && t.getTicketLevel() == ticket.getTicketLevel() && java.util.Objects.equals(t.getIdentifier(), ticket.getIdentifier()); // Paper - add identifier
    }

    public int getTicketLevelAt(final long key, final boolean simulation) {
        return getTicketLevelAt(this.getTickets(key), simulation);
    }

    private static int getTicketLevelAt(final List<Ticket> tickets, final boolean simulation) {
        Ticket lowestTicket = getLowestTicket(tickets, simulation);
        return lowestTicket == null ? ChunkLevel.MAX_LEVEL + 1 : lowestTicket.getTicketLevel();
    }

    private static @Nullable Ticket getLowestTicket(final @Nullable List<Ticket> tickets, final boolean simulation) {
        if (tickets == null) {
            return null;
        }

        Ticket t = null;

        for (Ticket ticket : tickets) {
            if (t == null || ticket.getTicketLevel() < t.getTicketLevel()) {
                if (simulation && ticket.getType().doesSimulate()) {
                    t = ticket;
                } else if (!simulation && ticket.getType().doesLoad()) {
                    t = ticket;
                }
            }
        }

        return t;
    }

    public void removeTicketWithRadius(final TicketType type, final ChunkPos chunkPos, final int radius) {
        Ticket ticket = new Ticket(type, ChunkLevel.byStatus(FullChunkStatus.FULL) - radius);
        this.removeTicket(chunkPos.pack(), ticket);
    }

    public void removeTicket(final Ticket ticket, final ChunkPos chunkPos) {
        this.removeTicket(chunkPos.pack(), ticket);
    }

    public boolean removeTicket(final long key, final Ticket ticket) {
        // Paper start - rewrite chunk system
        final boolean ret = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.chunkMap.level).moonrise$getChunkTaskScheduler().chunkHolderManager
            .removeTicketAtLevel(ticket.getType(), key, ticket.getTicketLevel(), ((ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicket<?>)ticket).moonrise$getIdentifier());

        if (ret) {
            this.setDirty();
        }

        return ret;
        // Paper end - rewrite chunk system
    }

    private void updateForcedChunks() {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public String getTicketDebugString(final long key, final boolean simulation) {
        List<Ticket> tickets = this.getTickets(key);
        Ticket lowestTicket = getLowestTicket(tickets, simulation);
        return lowestTicket == null ? "no_ticket" : lowestTicket.toString();
    }

    public void purgeStaleTickets(final ChunkMap chunkMap) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)chunkMap.level).moonrise$getChunkTaskScheduler().chunkHolderManager.tick(); // Paper - rewrite chunk system
        this.setDirty();
    }

    private boolean canTicketExpire(final ChunkMap chunkMap, final Ticket ticket, final long chunkPos) {
        if (!ticket.getType().hasTimeout()) {
            return false;
        }

        if (ticket.getType().canExpireIfUnloaded()) {
            return true;
        }

        ChunkHolder updatingChunk = chunkMap.getUpdatingChunkIfPresent(chunkPos);
        return updatingChunk == null || updatingChunk.isReadyForSaving();
    }

    public void deactivateTicketsOnClosing() {
        // Paper - rewrite chunk system
    }

    public void removeTicketIf(final TicketStorage.TicketPredicate predicate, final @Nullable Long2ObjectOpenHashMap<List<Ticket>> removedTickets) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public void replaceTicketLevelOfType(final int newLevel, final TicketType ticketType) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    public boolean updateChunkForced(final ChunkPos chunkPos, final boolean forced) {
        Ticket ticket = new Ticket(TicketType.FORCED, ChunkMap.FORCED_TICKET_LEVEL);
        return forced ? this.addTicket(chunkPos.pack(), ticket) : this.removeTicket(chunkPos.pack(), ticket);
    }

    public LongSet getForceLoadedChunks() {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2LongHashTable forced = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.chunkMap.level).moonrise$getChunkTaskScheduler()
            .chunkHolderManager.getTicketCounters(ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType.COUNTER_TYPE_FORCED);

        if (forced == null) {
            return new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
        }

       // note: important to presize correctly using size/loadfactor to avoid awful write performance
       //       think: iteration over our map has the same hash strategy, and if ret is not sized
       //       correctly then every (ret.table.length) may collide. During resize, open hashed tables
       //       (like LongLinkedOpenHashSet) must reinsert - leading to O(n^2) to copy IF we do not initially
       //       size correctly
       final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet ret = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet(forced.size(), forced.getLoadFactor());
       for (final java.util.PrimitiveIterator.OfLong iterator = forced.keyIterator(); iterator.hasNext();) {
           ret.add(iterator.nextLong());
       }
       return ret;
        // Paper end - rewrite chunk system
    }

    private LongSet getAllChunksWithTicketThat(final Predicate<Ticket> ticketCheck) {
        throw new UnsupportedOperationException(); // Paper - rewrite chunk system
    }

    @FunctionalInterface
    public interface ChunkUpdated {
        void update(final long node, final int newLevelFrom, final boolean onlyDecreased);
    }

    public interface TicketPredicate {
        boolean test(Ticket ticket, long chunkPos);
    }

    // Paper start
    public boolean addPluginRegionTicket(final ChunkPos pos, final org.bukkit.plugin.Plugin value) {
        // Keep inline with force loading
        return addTicket(pos.pack(), new Ticket(TicketType.PLUGIN_TICKET, ChunkMap.FORCED_TICKET_LEVEL, value));
    }

    public boolean removePluginRegionTicket(final ChunkPos pos, final org.bukkit.plugin.Plugin value) {
        // Keep inline with force loading
        return removeTicket(pos.pack(), new Ticket(TicketType.PLUGIN_TICKET, ChunkMap.FORCED_TICKET_LEVEL, value));
    }

    public void removeAllPluginRegionTickets(TicketType ticketType, int ticketLevel, org.bukkit.plugin.Plugin ticketIdentifier) {
        this.chunkMap.level.moonrise$getChunkTaskScheduler().chunkHolderManager.removeAllTicketsFor(ticketType, ticketLevel, ticketIdentifier); // Paper - rewrite chunk system
    }
    // Paper end
}
