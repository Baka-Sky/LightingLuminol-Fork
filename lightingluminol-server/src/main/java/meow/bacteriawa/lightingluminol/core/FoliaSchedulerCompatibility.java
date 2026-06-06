package meow.bacteriawa.lightingluminol.core;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.earthme.luminol.config.modules.unsupported.FoliaSchedulerCompatibilityConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class FoliaSchedulerCompatibility {
    private static final Set<BukkitBackedScheduledTask> BUKKIT_BACKED_TASKS = ConcurrentHashMap.newKeySet();

    private FoliaSchedulerCompatibility() {
    }

    public static boolean shouldUseBukkitScheduler(final Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        if (!FoliaSchedulerCompatibilityConfig.enabled || "Minecraft".equalsIgnoreCase(plugin.getPluginMeta().getName())) {
            return false;
        }

        final String pluginName = plugin.getPluginMeta().getName();
        if (containsPlugin(FoliaSchedulerCompatibilityConfig.forceFoliaSchedulerPlugins, pluginName)) {
            return false;
        }
        if (containsPlugin(FoliaSchedulerCompatibilityConfig.forceBukkitSchedulerPlugins, pluginName)) {
            return true;
        }
        return !isFoliaSupportedByPluginJar(plugin);
    }

    private static boolean isFoliaSupportedByPluginJar(final Plugin plugin) {
        // PluginMeta is parsed from plugin.yml, paper-plugin.yml, or leaves-plugin.json inside the plugin jar.
        return plugin.getPluginMeta().isFoliaSupported();
    }

    public static void executeOnBukkit(final Plugin plugin, final Runnable run, final String taskType) {
        validate(plugin);
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                run.run();
            } catch (final Throwable throwable) {
                plugin.getLogger().log(Level.WARNING, taskType + " compatibility task for " + plugin.getDescription().getFullName() + " generated an exception", throwable);
            }
        });
    }

    public static ScheduledTask runOnBukkit(final Plugin plugin, final Consumer<ScheduledTask> task, final String taskType) {
        return runDelayedOnBukkit(plugin, task, 1L, taskType);
    }

    public static ScheduledTask runDelayedOnBukkit(final Plugin plugin, final Consumer<ScheduledTask> task, final long delayTicks, final String taskType) {
        validate(plugin);
        Objects.requireNonNull(task, "Task may not be null");
        if (delayTicks <= 0L) {
            throw new IllegalArgumentException("Delay ticks may not be <= 0");
        }

        final BukkitBackedScheduledTask ret = new BukkitBackedScheduledTask(plugin, -1L, task, taskType);
        FoliaSchedulerCompatibility.BUKKIT_BACKED_TASKS.add(ret);
        final BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, ret, delayTicks);
        ret.setBukkitTask(bukkitTask);

        if (!plugin.isEnabled()) {
            ret.cancel();
        }

        return ret;
    }

    public static ScheduledTask runAtFixedRateOnBukkit(final Plugin plugin, final Consumer<ScheduledTask> task, final long initialDelayTicks,
                                                       final long periodTicks, final String taskType) {
        validate(plugin);
        Objects.requireNonNull(task, "Task may not be null");
        if (initialDelayTicks <= 0L) {
            throw new IllegalArgumentException("Initial delay ticks may not be <= 0");
        }
        if (periodTicks <= 0L) {
            throw new IllegalArgumentException("Period ticks may not be <= 0");
        }

        final BukkitBackedScheduledTask ret = new BukkitBackedScheduledTask(plugin, periodTicks, task, taskType);
        FoliaSchedulerCompatibility.BUKKIT_BACKED_TASKS.add(ret);
        final BukkitTask bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, ret, initialDelayTicks, periodTicks);
        ret.setBukkitTask(bukkitTask);

        if (!plugin.isEnabled()) {
            ret.cancel();
        }

        return ret;
    }

    public static void cancelTasks(final Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        for (final BukkitBackedScheduledTask task : BUKKIT_BACKED_TASKS) {
            if (task.plugin == plugin) {
                task.cancel();
            }
        }
    }

    private static void validate(final Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException("Plugin attempted to register task while disabled");
        }
    }

    private static boolean containsPlugin(final List<String> plugins, final String pluginName) {
        if (plugins == null) {
            return false;
        }
        for (final String configuredPlugin : plugins) {
            if (configuredPlugin.equalsIgnoreCase(pluginName)) {
                return true;
            }
        }
        return false;
    }

    private static final class BukkitBackedScheduledTask implements ScheduledTask, Runnable {
        private static final int STATE_IDLE = 0;
        private static final int STATE_EXECUTING = 1;
        private static final int STATE_EXECUTING_CANCELLED = 2;
        private static final int STATE_FINISHED = 3;
        private static final int STATE_CANCELLED = 4;

        private final Plugin plugin;
        private final long repeatDelay;
        private final String taskType;
        private Consumer<ScheduledTask> run;
        private volatile BukkitTask bukkitTask;
        private volatile int state = STATE_IDLE;

        private BukkitBackedScheduledTask(final Plugin plugin, final long repeatDelay, final Consumer<ScheduledTask> run, final String taskType) {
            this.plugin = plugin;
            this.repeatDelay = repeatDelay;
            this.run = run;
            this.taskType = taskType;
        }

        private void setBukkitTask(final BukkitTask bukkitTask) {
            this.bukkitTask = bukkitTask;
            if (this.state == STATE_CANCELLED) {
                bukkitTask.cancel();
            }
        }

        @Override
        public void run() {
            if (!this.plugin.isEnabled()) {
                this.clearTask(STATE_CANCELLED);
                return;
            }
            if (!this.compareAndSetState(STATE_IDLE, STATE_EXECUTING)) {
                return;
            }

            try {
                this.run.accept(this);
            } catch (final Throwable throwable) {
                this.plugin.getLogger().log(Level.WARNING, this.taskType + " compatibility task for " + this.plugin.getDescription().getFullName() + " generated an exception", throwable);
            } finally {
                if (!this.isRepeatingTask()) {
                    this.clearTask(STATE_FINISHED);
                } else if (!this.plugin.isEnabled()) {
                    this.clearTask(STATE_CANCELLED);
                } else if (!this.compareAndSetState(STATE_EXECUTING, STATE_IDLE)) {
                    this.clearTask(this.state);
                }
            }
        }

        @Override
        public Plugin getOwningPlugin() {
            return this.plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return this.repeatDelay > 0L;
        }

        @Override
        public CancelledState cancel() {
            for (;;) {
                final int current = this.state;
                switch (current) {
                    case STATE_IDLE:
                        if (this.compareAndSetState(STATE_IDLE, STATE_CANCELLED)) {
                            this.cancelBukkitTask();
                            this.clearTask(STATE_CANCELLED);
                            return CancelledState.CANCELLED_BY_CALLER;
                        }
                        continue;
                    case STATE_EXECUTING:
                        if (!this.isRepeatingTask()) {
                            return CancelledState.RUNNING;
                        }
                        if (this.compareAndSetState(STATE_EXECUTING, STATE_EXECUTING_CANCELLED)) {
                            this.cancelBukkitTask();
                            BUKKIT_BACKED_TASKS.remove(this);
                            return CancelledState.NEXT_RUNS_CANCELLED;
                        }
                        continue;
                    case STATE_EXECUTING_CANCELLED:
                        return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
                    case STATE_FINISHED:
                        return CancelledState.ALREADY_EXECUTED;
                    case STATE_CANCELLED:
                        return CancelledState.CANCELLED_ALREADY;
                    default:
                        throw new IllegalStateException("Unknown state: " + current);
                }
            }
        }

        @Override
        public ExecutionState getExecutionState() {
            return switch (this.state) {
                case STATE_IDLE -> ExecutionState.IDLE;
                case STATE_EXECUTING -> ExecutionState.RUNNING;
                case STATE_EXECUTING_CANCELLED -> ExecutionState.CANCELLED_RUNNING;
                case STATE_FINISHED -> ExecutionState.FINISHED;
                case STATE_CANCELLED -> ExecutionState.CANCELLED;
                default -> throw new IllegalStateException("Unknown state: " + this.state);
            };
        }

        private synchronized boolean compareAndSetState(final int expected, final int updated) {
            if (this.state != expected) {
                return false;
            }
            this.state = updated;
            return true;
        }

        private void clearTask(final int finalState) {
            this.run = null;
            this.state = finalState;
            BUKKIT_BACKED_TASKS.remove(this);
        }

        private void cancelBukkitTask() {
            final BukkitTask task = this.bukkitTask;
            if (task != null) {
                task.cancel();
            }
        }
    }
}
