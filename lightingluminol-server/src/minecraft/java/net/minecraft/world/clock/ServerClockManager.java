package net.minecraft.world.clock;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.apache.commons.lang3.mutable.MutableBoolean;

public class ServerClockManager extends SavedData implements ClockManager {
    public static final SavedDataType<ServerClockManager> TYPE = new SavedDataType<>(
        Identifier.withDefaultNamespace("world_clocks"),
        () -> new ServerClockManager(PackedClockStates.EMPTY),
        PackedClockStates.CODEC.xmap(ServerClockManager::new, ServerClockManager::packState),
        DataFixTypes.SAVED_DATA_WORLD_CLOCKS
    );
    private final PackedClockStates packedClockStates;
    private MinecraftServer server;
    private @org.jspecify.annotations.Nullable ServerLevel level; // Paper - per-world time
    private final Map<Holder<WorldClock>, ServerClockManager.ClockInstance> clocks = new HashMap<>();

    private ServerClockManager(final PackedClockStates packedClockStates) {
        this.packedClockStates = packedClockStates;
    }

    public void init(final MinecraftServer server) {
        // Paper start - per-world time
        this.init(server, null);
    }
    public void init(final MinecraftServer server, @org.jspecify.annotations.Nullable final ServerLevel level) {
        this.level = level;
        // Paper end - per-world time
        this.server = server;
        server.registryAccess()
            .lookupOrThrow(Registries.WORLD_CLOCK)
            .listElements()
            .forEach(definition -> this.clocks.put(definition, new ServerClockManager.ClockInstance()));
        server.registryAccess()
            .lookupOrThrow(Registries.TIMELINE)
            .listElements()
            .forEach(timeline -> timeline.value().registerTimeMarkers(this::registerTimeMarker));
        this.packedClockStates.clocks().forEach((definition, state) -> {
            ServerClockManager.ClockInstance instance = this.getInstance((Holder<WorldClock>)definition);
            instance.loadFrom(state);
        });
    }

    private void registerTimeMarker(final ResourceKey<ClockTimeMarker> timeMarkerId, final ClockTimeMarker timeMarker) {
        this.getInstance(timeMarker.clock()).timeMarkers.put(timeMarkerId, timeMarker);
    }

    public PackedClockStates packState() {
        return new PackedClockStates(Util.mapValues(this.clocks, ServerClockManager.ClockInstance::packState));
    }

    public void tick() {
        boolean advanceTime = this.advanceTime(); // Paper - per-world time
        if (advanceTime) {
            this.clocks.values().forEach(ServerClockManager.ClockInstance::tick);
            this.setDirty();
        }
    }

    private ServerClockManager.ClockInstance getInstance(final Holder<WorldClock> definition) {
        ServerClockManager.ClockInstance instance = this.clocks.get(definition);
        if (instance == null) {
            throw new IllegalStateException("No clock initialized for definition: " + definition);
        } else {
            return instance;
        }
    }

    public void setTotalTicks(final Holder<WorldClock> clock, final long totalTicks) {
        this.modifyClock(clock, instance -> {
            instance.totalTicks = totalTicks;
            instance.partialTick = 0.0F;
        });
    }

    public boolean moveToTimeMarker(final Holder<WorldClock> clock, final ResourceKey<ClockTimeMarker> timeMarkerId) {
        MutableBoolean set = new MutableBoolean();
        this.modifyClock(clock, instance -> {
            ClockTimeMarker timeMarker = instance.timeMarkers.get(timeMarkerId);
            if (timeMarker != null) {
                instance.totalTicks = timeMarker.resolveTimeToMoveTo(instance.totalTicks);
                instance.partialTick = 0.0F;
                set.setTrue();
            }
        });
        return set.booleanValue();
    }

    // Paper start - time skip event
    public java.util.OptionalLong getTotalTicksToTimeMarker(final Holder<WorldClock> clock, final ResourceKey<ClockTimeMarker> timeMarkerId) {
        final ServerClockManager.ClockInstance instance = this.getInstance(clock);
        final ClockTimeMarker timeMarker = instance.timeMarkers.get(timeMarkerId);

        return timeMarker != null ? java.util.OptionalLong.of(timeMarker.resolveTimeToMoveTo(instance.totalTicks)) : java.util.OptionalLong.empty();
    }
    // Paper end - time skip event

    public void addTicks(final Holder<WorldClock> clock, final long ticks) { // Paper
        this.modifyClock(clock, instance -> instance.totalTicks = Math.max(instance.totalTicks + ticks, 0L));
    }

    public void setPaused(final Holder<WorldClock> clock, final boolean paused) {
        this.modifyClock(clock, instance -> instance.paused = paused);
    }

    public void setRate(final Holder<WorldClock> clock, final float rate) {
        this.modifyClock(clock, instance -> instance.rate = rate);
    }

    private void modifyClock(final Holder<WorldClock> clock, final Consumer<? super ServerClockManager.ClockInstance> action) {
        ServerClockManager.ClockInstance instance = this.getInstance(clock);
        action.accept(instance);
        // Paper start - per-world time
        this.broadcastUpdates(clock, instance);
        this.setDirty();

        if (this.level != null) {
            this.level.environmentAttributes().invalidateTickCache();
        } else {
        for (ServerLevel level : this.server.getAllLevels()) {
            level.environmentAttributes().invalidateTickCache();
        }
        }
        // Paper end - per-world time
    }

    @Override
    public long getTotalTicks(final Holder<WorldClock> definition) {
        return this.getInstance(definition).totalTicks;
    }

    public ClientboundSetTimePacket createFullSyncPacket() {
        // Paper start - per-player time
        return this.createFullSyncPacket(null);
    }

    public ClientboundSetTimePacket createFullSyncPacket(final net.minecraft.server.level.ServerPlayer player) {
        final Map<Holder<WorldClock>, ClockNetworkState> updates = new HashMap<>(this.clocks.size());
        this.clocks.forEach((clock, instance) -> updates.put(clock, this.packNetworkState(clock, instance, player)));
        return new ClientboundSetTimePacket(this.getGameTime(), updates);
        // Paper end - per-player time
    }

    private long getGameTime() {
        return this.level != null ? this.level.getLevelData().getGameTime() : this.server.overworld().getLevelData().getGameTime(); // Paper - per-world time // Folia - region threading
    }

    // Paper start - per-world time; per-player time
    private void broadcastUpdates(final Holder<WorldClock> clock, final ServerClockManager.ClockInstance instance) {
        for (final net.minecraft.server.level.ServerPlayer player : this.level != null ? this.level.players() : this.server.getPlayerList().getPlayers()) {
            final var packet = new net.minecraft.network.protocol.game.ClientboundSetTimePacket(
                this.getGameTime(),
                java.util.Map.of(clock, this.packNetworkState(clock, instance, player))
            );
            player.connection.send(packet);
        }
    }

    private ClockNetworkState packNetworkState(
        final Holder<WorldClock> clock,
        final ServerClockManager.ClockInstance instance,
        final net.minecraft.server.level.ServerPlayer player
    ) {
        if (player != null && player.level().dimensionType().defaultClock().filter(clock::equals).isPresent()) {
            final boolean paused = !player.relativeTime || instance.paused || !this.advanceTime();
            return new ClockNetworkState(player.getPlayerTime(), instance.partialTick, paused ? 0.0F : instance.rate);
        }
        return instance.packNetworkState(this.advanceTime());
    }

    private boolean advanceTime() {
        return this.level != null ? this.level.getGameRules().get(GameRules.ADVANCE_TIME) : this.server.getGlobalGameRules().get(GameRules.ADVANCE_TIME);
    }
    // Paper end - per-world time; per-player time

    public boolean isAtTimeMarker(final Holder<WorldClock> clock, final ResourceKey<ClockTimeMarker> timeMarkerId) {
        ServerClockManager.ClockInstance clockInstance = this.getInstance(clock);
        ClockTimeMarker timeMarker = clockInstance.timeMarkers.get(timeMarkerId);
        return timeMarker != null && timeMarker.occursAt(clockInstance.totalTicks);
    }

    public Stream<ResourceKey<ClockTimeMarker>> commandTimeMarkersForClock(final Holder<WorldClock> clock) {
        return this.getInstance(clock).timeMarkers.entrySet().stream().filter(entry -> entry.getValue().showInCommands()).map(Entry::getKey);
    }

    private static class ClockInstance {
        private final Map<ResourceKey<ClockTimeMarker>, ClockTimeMarker> timeMarkers = new Reference2ObjectOpenHashMap<>();
        private long totalTicks;
        private float partialTick;
        private float rate = 1.0F;
        private boolean paused;

        public void loadFrom(final ClockState state) {
            this.totalTicks = state.totalTicks();
            this.partialTick = state.partialTick();
            this.rate = state.rate();
            this.paused = state.paused();
        }

        public void tick() {
            if (!this.paused) {
                this.partialTick = this.partialTick + this.rate;
                int fullTicks = Mth.floor(this.partialTick);
                this.partialTick -= fullTicks;
                this.totalTicks += fullTicks;
            }
        }

        public ClockState packState() {
            return new ClockState(this.totalTicks, this.partialTick, this.rate, this.paused);
        }

        public ClockNetworkState packNetworkState(final boolean advanceTime) { // Paper - per-world time
            boolean paused = this.paused || !advanceTime;
            return new ClockNetworkState(this.totalTicks, this.partialTick, paused ? 0.0F : this.rate);
        }
    }
}
