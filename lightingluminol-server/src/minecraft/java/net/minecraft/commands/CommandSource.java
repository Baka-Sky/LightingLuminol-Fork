package net.minecraft.commands;

import net.minecraft.network.chat.Component;

public interface CommandSource {
    CommandSource NULL = new CommandSource() {
        @Override
        public void sendSystemMessage(final Component message) {
        }

        @Override
        public boolean acceptsSuccess() {
            return false;
        }

        @Override
        public boolean acceptsFailure() {
            return false;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        // CraftBukkit start
        @Override
        public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack stack) {
            return io.papermc.paper.brigadier.NullCommandSender.INSTANCE; // Paper - expose a no-op CommandSender
        }
        // CraftBukkit end
    };

    void sendSystemMessage(Component message);

    boolean acceptsSuccess();

    boolean acceptsFailure();

    boolean shouldInformAdmins();

    default boolean alwaysAccepts() {
        return false;
    }

    org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper); // CraftBukkit
}
