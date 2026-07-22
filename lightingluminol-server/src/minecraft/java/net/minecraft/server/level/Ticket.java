package net.minecraft.server.level;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Util;

public class Ticket<T> implements Comparable<Ticket>, ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicket<T> { // Paper - rewrite chunk system
    public static final MapCodec<Ticket> CODEC = RecordCodecBuilder.mapCodec(
        i -> i.group(
                BuiltInRegistries.TICKET_TYPE.byNameCodec().fieldOf("type").forGetter(Ticket::getType),
                ExtraCodecs.NON_NEGATIVE_INT.fieldOf("level").forGetter(Ticket::getTicketLevel),
                Codec.LONG.optionalFieldOf("ticks_left", 0L).forGetter(t -> t.ticksLeft)
            )
            .apply(i, (type, level, ticks) -> new Ticket(type, level.intValue(), ticks.longValue())) // Paper - add identifier
    );
    private final TicketType type;
    private final int ticketLevel;
    private long ticksLeft;
    // Paper start - add identifier
    private T identifier; // Paper - rewrite chunk system

    public Object getIdentifier() {
        return this.identifier;
    }
    // Paper end - add identifier
    // Paper start - rewrite chunk system
    @Override
    public final long moonrise$getRemoveDelay() {
        return this.ticksLeft;
    }

    @Override
    public final void moonrise$setRemoveDelay(final long removeDelay) {
        this.ticksLeft = removeDelay;
    }

    @Override
    public final T moonrise$getIdentifier() {
        return this.identifier;
    }

    @Override
    public final void moonrise$setIdentifier(final T identifier) {
        if ((identifier == null) != (((ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType<T>)(Object)this.type).moonrise$getIdentifierComparator() == null)) {
            throw new IllegalStateException("Nullability of identifier should match nullability of comparator");
        }
        this.identifier = identifier;
    }

    @Override
    public final int compareTo(final Ticket ticket) {
        final int levelCompare = Integer.compare(this.ticketLevel, ticket.ticketLevel);
        if (levelCompare != 0) {
            return levelCompare;
        }

        final int typeCompare = Long.compare(
            ((ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType<T>)(Object)this.type).moonrise$getId(),
            ((ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType<?>)(Object)ticket.type).moonrise$getId()
        );
        if (typeCompare != 0) {
            return typeCompare;
        }

        final java.util.Comparator<T> comparator = ((ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType<T>)(Object)this.type).moonrise$getIdentifierComparator();
        return comparator == null ? 0 : comparator.compare(this.identifier, (T)ticket.identifier);
    }
    // Paper end - rewrite chunk system

    public Ticket(final TicketType type, final int ticketLevel) {
        // Paper start - add identifier
        this(type, ticketLevel, null);
    }
    public Ticket(TicketType type, int ticketLevel, Object identifier) {
        this(type, ticketLevel, type.timeout(), identifier);
        // Paper end - add identifier
    }

    public Ticket(final TicketType type, final int ticketLevel, final long ticksLeft) { // Paper - rewrite chunk system - public
        // Paper start - add identifier
        this(type, ticketLevel, ticksLeft, null);
    }
    private Ticket(TicketType type, int ticketLevel, long ticksLeft, Object identifier) {
        this.identifier = (T)identifier; // Paper - rewrite chunk system
        // Paper end - add identifier
        this.type = type;
        this.ticketLevel = ticketLevel;
        this.ticksLeft = ticksLeft;
    }

    @Override
    public String toString() {
        return "Ticket[" + this.type + " " + this.ticketLevel + " (" + this.identifier + ")] to die in " + this.ticksLeft; // Paper - rewrite chunk system
    }

    public TicketType getType() {
        return this.type;
    }

    public int getTicketLevel() {
        return this.ticketLevel;
    }

    public void resetTicksLeft() {
        this.ticksLeft = this.type.timeout();
    }

    public void decreaseTicksLeft() {
        if (this.type.hasTimeout()) {
            this.ticksLeft--;
        }
    }

    public boolean isTimedOut() {
        return this.type.hasTimeout() && this.ticksLeft < 0L;
    }
}
