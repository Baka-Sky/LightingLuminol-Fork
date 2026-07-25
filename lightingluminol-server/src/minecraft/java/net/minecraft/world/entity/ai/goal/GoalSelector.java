package net.minecraft.world.entity.ai.goal;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;

public class GoalSelector {
    private static final WrappedGoal NO_GOAL = new WrappedGoal(Integer.MAX_VALUE, new Goal() {
        @Override
        public boolean canUse() {
            return false;
        }
    }) {
        @Override
        public boolean isRunning() {
            return false;
        }
    };
    private final Map<Goal.Flag, WrappedGoal> lockedFlags = new EnumMap<>(Goal.Flag.class);
    private final Set<WrappedGoal> availableGoals = new ObjectLinkedOpenHashSet<>();
    private static final Goal.Flag[] GOAL_FLAG_VALUES = Goal.Flag.values(); // Paper - remove streams from GoalSelector
    private final ca.spottedleaf.moonrise.common.set.OptimizedSmallEnumSet<net.minecraft.world.entity.ai.goal.Goal.Flag> goalTypes = new ca.spottedleaf.moonrise.common.set.OptimizedSmallEnumSet<>(Goal.Flag.class); // Paper - remove streams from GoalSelector
    private int curRate; // Paper - EAR 2

    public void addGoal(final int prio, final Goal goal) {
        this.availableGoals.add(new WrappedGoal(prio, goal));
    }

    public void removeAllGoals(final Predicate<Goal> predicate) {
        for (WrappedGoal availableGoal : this.availableGoals) {
            if (predicate.test(availableGoal.getGoal()) && availableGoal.isRunning()) {
                availableGoal.stop();
            }
        }

        this.availableGoals.removeIf(goal -> predicate.test(goal.getGoal()));
    }

    // Paper start - EAR 2
    public boolean inactiveTick() {
        this.curRate++;
        return this.curRate % 3 == 0; // TODO newGoalRate was already unused in 1.20.4, check if this is correct
    }

    public boolean hasTasks() {
        for (final WrappedGoal task : this.availableGoals) {
            if (task.isRunning()) {
                return true;
            }
        }
        return false;
    }
    // Paper end - EAR 2

    public void removeGoal(final Goal toRemove) {
        this.removeAllGoals(goal -> goal == toRemove);
    }

    // Paper start - Perf: optimize goal types
    private static boolean goalContainsAnyFlags(final WrappedGoal goal, final ca.spottedleaf.moonrise.common.set.OptimizedSmallEnumSet<Goal.Flag> disabledFlags) {
        return goal.getFlags().hasCommonElements(disabledFlags);
    }

    private static boolean goalCanBeReplacedForAllFlags(final WrappedGoal goal, final Map<Goal.Flag, WrappedGoal> lockedFlags) {
        long flagIterator = goal.getFlags().getBackingSet();
        final int flagSize = goal.getFlags().size();
        for (int i = 0; i < flagSize; ++i) {
            final Goal.Flag flag = GOAL_FLAG_VALUES[Long.numberOfTrailingZeros(flagIterator)];
            flagIterator ^= ca.spottedleaf.common.util.IntegerUtil.getTrailingBit(flagIterator);
            // Paper end - Perf: optimize goal types
            if (!lockedFlags.getOrDefault(flag, NO_GOAL).canBeReplacedBy(goal)) {
                return false;
            }
        }

        return true;
    }

    public void tick() {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("goalCleanup");

        for (WrappedGoal goal : this.availableGoals) {
            if (goal.isRunning() && (goalContainsAnyFlags(goal, this.goalTypes) || !goal.canContinueToUse())) { // Paper - Perf: optimize goal types by removing streams
                goal.stop();
            }
        }

        this.lockedFlags.entrySet().removeIf(entry -> !entry.getValue().isRunning());
        profiler.pop();
        profiler.push("goalUpdate");

        for (WrappedGoal goal : this.availableGoals) {
            // Paper start
            if (!goal.isRunning() && !goalContainsAnyFlags(goal, this.goalTypes) && goalCanBeReplacedForAllFlags(goal, this.lockedFlags) && goal.canUse()) {
                long flagIterator = goal.getFlags().getBackingSet();
                int wrappedGoalSize = goal.getFlags().size();
                for (int i = 0; i < wrappedGoalSize; ++i) {
                    final Goal.Flag flag = GOAL_FLAG_VALUES[Long.numberOfTrailingZeros(flagIterator)];
                    flagIterator ^= ca.spottedleaf.common.util.IntegerUtil.getTrailingBit(flagIterator);
                    // Paper end
                    WrappedGoal currentGoal = this.lockedFlags.getOrDefault(flag, NO_GOAL);
                    currentGoal.stop();
                    this.lockedFlags.put(flag, goal);
                }

                goal.start();
            }
        }

        profiler.pop();
        this.tickRunningGoals(true);
    }

    public void tickRunningGoals(final boolean forceTickAllRunningGoals) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("goalTick");

        for (WrappedGoal goal : this.availableGoals) {
            if (goal.isRunning() && (forceTickAllRunningGoals || goal.requiresUpdateEveryTick())) {
                goal.tick();
            }
        }

        profiler.pop();
    }

    public Set<WrappedGoal> getAvailableGoals() {
        return this.availableGoals;
    }

    public void disableControlFlag(final Goal.Flag flag) {
        this.goalTypes.addUnchecked(flag); // Paper - remove streams from GoalSelector
    }

    public void enableControlFlag(final Goal.Flag flag) {
        this.goalTypes.removeUnchecked(flag); // Paper - remove streams from GoalSelector
    }

    public void setControlFlag(final Goal.Flag flag, final boolean enabled) {
        if (enabled) {
            this.enableControlFlag(flag);
        } else {
            this.disableControlFlag(flag);
        }
    }
}
