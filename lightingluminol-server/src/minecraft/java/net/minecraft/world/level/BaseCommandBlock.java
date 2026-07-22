package net.minecraft.world.level;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public abstract class BaseCommandBlock {
    private static final Component DEFAULT_NAME = Component.literal("@");
    private static final int NO_LAST_EXECUTION = -1;
    private long lastExecution = -1L;
    private boolean updateLastExecution = true;
    private int successCount;
    private boolean trackOutput = true;
    private @Nullable Component lastOutput;
    private String command = "";
    private @Nullable Component customName;
    // CraftBukkit start
    protected abstract org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper);
    public abstract ServerLevel getLevel();
    // CraftBukkit end

    public int getSuccessCount() {
        return this.successCount;
    }

    public void setSuccessCount(final int successCount) {
        this.successCount = successCount;
    }

    public Component getLastOutput() {
        return this.lastOutput == null ? CommonComponents.EMPTY : this.lastOutput;
    }

    public void save(final ValueOutput output) {
        output.putString("Command", this.command);
        output.putInt("SuccessCount", this.successCount);
        output.storeNullable("CustomName", ComponentSerialization.CODEC, this.customName);
        output.putBoolean("TrackOutput", this.trackOutput);
        if (this.trackOutput) {
            output.storeNullable("LastOutput", ComponentSerialization.CODEC, this.lastOutput);
        }

        output.putBoolean("UpdateLastExecution", this.updateLastExecution);
        if (this.updateLastExecution && this.lastExecution != -1L) {
            output.putLong("LastExecution", this.lastExecution);
        }
    }

    public void load(final ValueInput input) {
        this.command = input.getStringOr("Command", "");
        this.successCount = input.getIntOr("SuccessCount", 0);
        this.setCustomName(BlockEntity.parseCustomNameSafe(input, "CustomName"));
        this.trackOutput = input.getBooleanOr("TrackOutput", true);
        if (this.trackOutput) {
            this.lastOutput = BlockEntity.parseCustomNameSafe(input, "LastOutput");
        } else {
            this.lastOutput = null;
        }

        this.updateLastExecution = input.getBooleanOr("UpdateLastExecution", true);
        if (this.updateLastExecution) {
            this.lastExecution = input.getLongOr("LastExecution", -1L);
        } else {
            this.lastExecution = -1L;
        }
    }

    public void setCommand(final String command) {
        this.command = command;
        this.successCount = 0;
    }

    public String getCommand() {
        return this.command;
    }

    public boolean performCommand(final ServerLevel level) {
        if (true) return false; // Folia - region threading
        if (level.getGameTime() == this.lastExecution) {
            return false;
        }

        if ("Searge".equalsIgnoreCase(this.command)) {
            this.lastOutput = Component.literal("#itzlipofutzli");
            this.successCount = 1;
            return true;
        }

        this.successCount = 0;
        if (level.isCommandBlockEnabled() && !StringUtil.isNullOrEmpty(this.command)) {
            try {
                this.lastOutput = null;

                try (BaseCommandBlock.CloseableCommandBlockSource commandSource = this.createSource(level)) {
                    CommandSource effectiveCommandSource = Objects.requireNonNullElse(commandSource, CommandSource.NULL);
                    CommandSourceStack commandSourceStack = this.createCommandSourceStack(level, effectiveCommandSource).withCallback((success, result) -> {
                        if (success) {
                            this.successCount++;
                        }
                    });
                    // Paper start - ServerCommandEvent
                    org.bukkit.event.server.ServerCommandEvent event = new org.bukkit.event.server.ServerCommandEvent(commandSourceStack.getBukkitSender(), net.minecraft.commands.Commands.trimOptionalPrefix(this.command));
                    if (!event.callEvent()) {
                        return true;
                    }
                    level.getServer().getCommands().performPrefixedCommand(commandSourceStack, event.getCommand());
                    // Paper end - ServerCommandEvent
                }
            } catch (Throwable t) {
                CrashReport report = CrashReport.forThrowable(t, "Executing command block");
                CrashReportCategory category = report.addCategory("Command to be executed");
                category.setDetail("Command", this::getCommand);
                category.setDetail("Name", () -> this.getName().getString());
                throw new ReportedException(report);
            }
        }

        if (this.updateLastExecution) {
            this.lastExecution = level.getGameTime();
        } else {
            this.lastExecution = -1L;
        }

        return true;
    }

    public BaseCommandBlock.CloseableCommandBlockSource createSource(final ServerLevel level) { // Paper - public, remove nullable
        return new BaseCommandBlock.CloseableCommandBlockSource(level, this.trackOutput); // Paper - add back source when output disabled
    }

    public Component getName() {
        return this.customName != null ? this.customName : DEFAULT_NAME;
    }

    public @Nullable Component getCustomName() {
        return this.customName;
    }

    public void setCustomName(final @Nullable Component name) {
        this.customName = name;
    }

    public abstract void onUpdated(ServerLevel level);

    public void setLastOutput(final @Nullable Component lastOutput) {
        this.lastOutput = lastOutput;
    }

    public void setTrackOutput(final boolean trackOutput) {
        this.trackOutput = trackOutput;
    }

    public boolean isTrackOutput() {
        return this.trackOutput;
    }

    public abstract CommandSourceStack createCommandSourceStack(ServerLevel level, CommandSource source);

    public abstract boolean isValid();

    public void threadCheck() {} // Folia

    public class CloseableCommandBlockSource implements CommandSource, AutoCloseable { // Paper - public
        private final ServerLevel level;
        private static final ThreadLocal<DateTimeFormatter> TIME_FORMAT = ThreadLocal.withInitial(() -> DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)); // Folia - region threading - SDF is not thread-safe
        private boolean closed;

        // Paper start - add back source when output disabled
        private final boolean trackOutput;
        public CloseableCommandBlockSource(final ServerLevel level, final boolean trackOutput) {
            this.level = level;
            this.trackOutput = trackOutput;
        }
        @io.papermc.paper.annotation.DoNotUse @Deprecated
        public CloseableCommandBlockSource(final ServerLevel level) { // Paper - public
            this(level, true);
            // Paper end - add back source when output disabled
        }

        @Override
        public boolean acceptsSuccess() {
            return !this.closed && this.level.getGameRules().get(GameRules.SEND_COMMAND_FEEDBACK);
        }

        @Override
        public boolean acceptsFailure() {
            return this.trackOutput && !this.closed; // Paper - add back source when output disabled
        }

        @Override
        public boolean shouldInformAdmins() {
            return !this.closed && this.level.getGameRules().get(GameRules.COMMAND_BLOCK_OUTPUT);
        }

        @Override
        public void sendSystemMessage(final Component message) {
            if (this.trackOutput && !this.closed) { // Paper - add back source when output disabled
                org.spigotmc.AsyncCatcher.catchOp("sendSystemMessage to a command block"); // Paper - Don't broadcast messages to command blocks
                BaseCommandBlock.this.threadCheck(); // Folia
                BaseCommandBlock.this.lastOutput = Component.literal("[" + TIME_FORMAT.get().format(ZonedDateTime.now()) + "] ").append(message); // Folia - region threading - SDF is not thread-safe
                BaseCommandBlock.this.onUpdated(this.level);
            }
        }

        @Override
        public void close() throws Exception {
            this.closed = true;
        }

        // CraftBukkit start
        @Override
        public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
            return BaseCommandBlock.this.getBukkitSender(wrapper);
        }
        // CraftBukkit end
    }
}
