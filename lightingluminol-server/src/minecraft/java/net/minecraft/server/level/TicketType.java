package net.minecraft.server.level;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class TicketType<T> implements ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType<T> { // Paper - rewrite chunk system
    // Paper start - rewrite chunk system
    private static final java.util.concurrent.atomic.AtomicLong ID_GENERATOR = new java.util.concurrent.atomic.AtomicLong();
    private final long id = ID_GENERATOR.getAndIncrement();
    private java.util.Comparator<T> identifierComparator;
    private volatile long[] counterTypes;

    @Override
    public final long moonrise$getId() {
        return this.id;
    }

    @Override
    public final java.util.Comparator<T> moonrise$getIdentifierComparator() {
        return this.identifierComparator;
    }

    @Override
    public final void moonrise$setIdentifierComparator(final java.util.Comparator<T> comparator) {
        this.identifierComparator = comparator;
    }

    @Override
    public final long[] moonrise$getCounterTypes() {
        // need to lazy init this because we cannot check if we are FORCED during construction
        final long[] types = this.counterTypes;
        if (types != null) {
            return types;
        }

        return this.counterTypes = ca.spottedleaf.moonrise.common.PlatformHooks.get().getCounterTypesUncached((TicketType)(Object)this);
    }

    @Override
    public final void moonrise$setTimeout(final long to) {
        this.timeout = to;
    }
    // Paper end - rewrite chunk system

    public static final long NO_TIMEOUT = 0L;
    // Paper start - diff on change - all listed in Flags annotation
    public static final int FLAG_PERSIST = 1; // Paper - diff on change - all listed in Flags annotation
    public static final int FLAG_LOADING = 2; // Paper - diff on change - all listed in Flags annotation
    public static final int FLAG_SIMULATION = 4; // Paper - diff on change - all listed in Flags annotation
    public static final int FLAG_KEEP_DIMENSION_ACTIVE = 8; // Paper - diff on change - all listed in Flags annotation
    public static final int FLAG_CAN_EXPIRE_IF_UNLOADED = 16; // Paper - diff on change - all listed in Flags annotation
    // Paper end - diff on change - all listed in Flags annotation
    public static final TicketType PLAYER_SPAWN = register("player_spawn", 20L, FLAG_LOADING);
    public static final TicketType SPAWN_SEARCH = register("spawn_search", 1L, FLAG_LOADING);
    public static final TicketType DRAGON = register("dragon", NO_TIMEOUT, FLAG_LOADING | FLAG_SIMULATION);
    public static final TicketType PLAYER_LOADING = register("player_loading", NO_TIMEOUT, FLAG_LOADING);
    public static final TicketType PLAYER_SIMULATION = register("player_simulation", NO_TIMEOUT, FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType FORCED = register("forced", NO_TIMEOUT, FLAG_PERSIST | FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType PORTAL = register("portal", 300L, FLAG_PERSIST | FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType ENDER_PEARL = register("ender_pearl", 40L, FLAG_LOADING | FLAG_SIMULATION | FLAG_KEEP_DIMENSION_ACTIVE);
    public static final TicketType UNKNOWN = register("unknown", 1L, FLAG_CAN_EXPIRE_IF_UNLOADED | FLAG_LOADING);
    public static final TicketType PLUGIN = register("plugin", NO_TIMEOUT, FLAG_LOADING | FLAG_SIMULATION); // CraftBukkit
    public static final TicketType POST_TELEPORT = register("post_teleport", 5L, FLAG_LOADING | FLAG_SIMULATION); // Paper
    public static final TicketType PLUGIN_TICKET = register("plugin_ticket", NO_TIMEOUT, FLAG_LOADING | FLAG_SIMULATION); static { ((TicketType<org.bukkit.plugin.Plugin>)PLUGIN_TICKET).moonrise$setIdentifierComparator((org.bukkit.plugin.Plugin p1, org.bukkit.plugin.Plugin p2) -> p1.getName().compareTo(p2.getName())); } // Paper // Paper - rewrite chunk system
    public static final TicketType FUTURE_AWAIT = register("future_await", NO_TIMEOUT, FLAG_LOADING | FLAG_SIMULATION); // Paper
    public static final TicketType CHUNK_LOAD = register("chunk_load", NO_TIMEOUT, FLAG_LOADING); // Paper - moonrise
    // Folia start - region threading
    public static final TicketType DELAYED = register("folia:delay", 5L, FLAG_LOADING | FLAG_SIMULATION);
    public static final TicketType<Long> END_GATEWAY_EXIT_SEARCH = ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType.create("folia:end_gateway_exit_search", Long::compareTo);
    public static final TicketType<Long> NETHER_PORTAL_DOUBLE_CHECK = ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType.create("folia:nether_portal_double_check", Long::compareTo);
    public static final TicketType<Long> TELEPORT_HOLD_TICKET = ca.spottedleaf.moonrise.patches.chunk_system.ticket.ChunkSystemTicketType.create("folia:teleport_hold_ticket", Long::compareTo);
    public static final TicketType REGION_SCHEDULER_API_HOLD = register("folia:region_scheduler_api_hold", 0L, FLAG_LOADING | FLAG_SIMULATION);
    // Folia end - region threading

    private static TicketType register(final String name, final long timeout, final @TicketType.Flags int flags) {
        return Registry.register(BuiltInRegistries.TICKET_TYPE, name, new TicketType(timeout, flags));
    }

    public boolean persist() {
        return (this.flags & FLAG_PERSIST) != 0;
    }

    public boolean doesLoad() {
        return (this.flags & FLAG_LOADING) != 0;
    }

    public boolean doesSimulate() {
        return (this.flags & FLAG_SIMULATION) != 0;
    }

    public boolean shouldKeepDimensionActive() {
        return (this.flags & FLAG_KEEP_DIMENSION_ACTIVE) != 0;
    }

    public boolean canExpireIfUnloaded() {
        return (this.flags & FLAG_CAN_EXPIRE_IF_UNLOADED) != 0;
    }

    // Paper start - rewrite chunk system - convert to class
    private long timeout; // Paper - rewrite chunk system - remove final
    private final int flags;

    public TicketType(long timeout, int flags) {
        this.timeout = timeout;
        this.flags = flags;
    } // long timeout, int flags

    public int flags() {
        return this.flags;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (net.minecraft.server.level.TicketType) obj;
        return this.timeout == that.timeout &&
            this.flags == that.flags;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(timeout, flags);
    }

    @Override
    public String toString() {
        return "TicketType[" +
            "timeout=" + timeout + ", " +
            "flags=" + flags + ']';
    }
    // Paper end - rewrite chunk system - convert to class

    // Paper start - chunk-gc config
    public static int PLUGIN_TYPE_TIMEOUT = 600;
    // Paper - rewrite chunk system - convert to class
    public long timeout() {
        return this == PLUGIN ? PLUGIN_TYPE_TIMEOUT : this.timeout;
    }
    // Paper end - chunk-gc config

    public boolean hasTimeout() {
        return this.timeout() != NO_TIMEOUT; // Paper - chunk-gc config
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    @org.intellij.lang.annotations.MagicConstant(flags = {FLAG_PERSIST, FLAG_LOADING, FLAG_SIMULATION, FLAG_KEEP_DIMENSION_ACTIVE, FLAG_CAN_EXPIRE_IF_UNLOADED}) // Paper - add back source-retention annotation for IDE
    public @interface Flags {
    }
}
