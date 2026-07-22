package net.minecraft.world.entity.ai.behavior;

import com.mojang.datafixers.util.Pair;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

public class GateBehavior<E extends LivingEntity> implements BehaviorControl<E> {
    private final Map<MemoryModuleType<?>, MemoryStatus> entryCondition;
    private final Set<MemoryModuleType<?>> exitErasedMemories;
    private final GateBehavior.OrderPolicy orderPolicy;
    private final GateBehavior.RunningPolicy runningPolicy;
    private final ShufflingList<BehaviorControl<? super E>> behaviors = new ShufflingList<>(false); // Paper - Fix Concurrency issue in ShufflingList during worldgen
    private Behavior.Status status = Behavior.Status.STOPPED;

    public GateBehavior(
        final Map<MemoryModuleType<?>, MemoryStatus> entryCondition,
        final Set<MemoryModuleType<?>> exitErasedMemories,
        final GateBehavior.OrderPolicy orderPolicy,
        final GateBehavior.RunningPolicy runningPolicy,
        final List<Pair<? extends BehaviorControl<? super E>, Integer>> behaviors
    ) {
        this.entryCondition = entryCondition;
        this.exitErasedMemories = exitErasedMemories;
        this.orderPolicy = orderPolicy;
        this.runningPolicy = runningPolicy;
        behaviors.forEach(entry -> this.behaviors.add(entry.getFirst(), entry.getSecond()));
    }

    @Override
    public Behavior.Status getStatus() {
        return this.status;
    }

    @Override
    public Set<MemoryModuleType<?>> getRequiredMemories() {
        Set<MemoryModuleType<?>> memories = new HashSet<>(this.entryCondition.keySet());

        for (BehaviorControl<? super E> behavior : this.behaviors) {
            memories.addAll(behavior.getRequiredMemories());
        }

        return memories;
    }

    private boolean hasRequiredMemories(final E body) {
        for (Entry<MemoryModuleType<?>, MemoryStatus> entry : this.entryCondition.entrySet()) {
            MemoryModuleType<?> memoryType = entry.getKey();
            MemoryStatus requiredStatus = entry.getValue();
            if (!body.getBrain().checkMemory(memoryType, requiredStatus)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public final boolean tryStart(final ServerLevel level, final E body, final long timestamp) {
        if (this.hasRequiredMemories(body)) {
            this.status = Behavior.Status.RUNNING;
            this.orderPolicy.apply(this.behaviors);
            this.runningPolicy.apply(this.behaviors, level, body, timestamp); // Paper - Perf: Remove streams from hot code
            return true;
        } else {
            return false;
        }
    }

    @Override
    public final void tickOrStop(final ServerLevel level, final E body, final long timestamp) {
        // Paper start - Perf: Remove streams from hot code
        for (final BehaviorControl<? super E> behavior : this.behaviors) {
            if (behavior.getStatus() == Behavior.Status.RUNNING) {
                behavior.tickOrStop(level, body, timestamp);
            }
        }
        // Paper end - Perf: Remove streams from hot code
        if (this.behaviors.stream().noneMatch(behavior -> behavior.getStatus() == Behavior.Status.RUNNING)) {
            this.doStop(level, body, timestamp);
        }
    }

    @Override
    public final void doStop(final ServerLevel level, final E body, final long timestamp) {
        this.status = Behavior.Status.STOPPED;
        // Paper start - Perf: Remove streams from hot code
        for (final BehaviorControl<? super E> behavior : this.behaviors) {
            if (behavior.getStatus() == Behavior.Status.RUNNING) {
                behavior.doStop(level, body, timestamp);
            }
        }
        for (final MemoryModuleType<?> exitErasedMemory : this.exitErasedMemories) {
            body.getBrain().eraseMemory(exitErasedMemory);
        }
        // Paper end - Perf: Remove streams from hot code
    }

    @Override
    public String debugString() {
        Set<String> runningBehaviours = this.behaviors
            .stream()
            .filter(goal -> goal.getStatus() == Behavior.Status.RUNNING)
            .map(b -> b.getClass().getSimpleName())
            .collect(Collectors.toSet());
        return this.getClass().getSimpleName() + ": " + runningBehaviours;
    }

    public enum OrderPolicy {
        ORDERED(t -> {}),
        SHUFFLED(ShufflingList::shuffle);

        private final Consumer<ShufflingList<?>> consumer;

        OrderPolicy(final Consumer<ShufflingList<?>> consumer) {
            this.consumer = consumer;
        }

        public void apply(final ShufflingList<?> list) {
            this.consumer.accept(list);
        }
    }

    public enum RunningPolicy {
        RUN_ONE {
            // Paper start - Perf: Remove streams from hot code
            @Override
            public <E extends LivingEntity> void apply(
                final ShufflingList<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
            ) {
                for (final BehaviorControl<? super E> behavior : behaviors) {
                    if (behavior.getStatus() == Behavior.Status.STOPPED && behavior.tryStart(level, body, timestamp)) {
                        break;
                    }
                }
                // Paper end - Perf: Remove streams from hot code
            }
        },
        TRY_ALL {
            // Paper start - Perf: Remove streams from hot code
            @Override
            public <E extends LivingEntity> void apply(
                final ShufflingList<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp
            ) {
                for (final BehaviorControl<? super E> behavior : behaviors) {
                    if (behavior.getStatus() == Behavior.Status.STOPPED) {
                        behavior.tryStart(level, body, timestamp);
                    }
                }
                // Paper end - Perf: Remove streams from hot code
            }
        };

        public abstract <E extends LivingEntity> void apply(
            final ShufflingList<BehaviorControl<? super E>> behaviors, final ServerLevel level, final E body, final long timestamp // Paper - Perf: Remove streams from hot code
        );
    }
}
