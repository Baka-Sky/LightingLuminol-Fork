package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Collection;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

public class EnchantCommand {
    private static final DynamicCommandExceptionType ERROR_NOT_LIVING_ENTITY = new DynamicCommandExceptionType(
        target -> Component.translatableEscape("commands.enchant.failed.entity", target)
    );
    private static final DynamicCommandExceptionType ERROR_NO_ITEM = new DynamicCommandExceptionType(
        target -> Component.translatableEscape("commands.enchant.failed.itemless", target)
    );
    private static final DynamicCommandExceptionType ERROR_INCOMPATIBLE = new DynamicCommandExceptionType(
        item -> Component.translatableEscape("commands.enchant.failed.incompatible", item)
    );
    private static final Dynamic2CommandExceptionType ERROR_LEVEL_TOO_HIGH = new Dynamic2CommandExceptionType(
        (level, max) -> Component.translatableEscape("commands.enchant.failed.level", level, max)
    );
    private static final SimpleCommandExceptionType ERROR_NOTHING_HAPPENED = new SimpleCommandExceptionType(Component.translatable("commands.enchant.failed"));

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("enchant")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.argument("targets", EntityArgument.entities())
                        .then(
                            Commands.argument("enchantment", ResourceArgument.resource(context, Registries.ENCHANTMENT))
                                .executes(
                                    c -> enchant(c.getSource(), EntityArgument.getEntities(c, "targets"), ResourceArgument.getEnchantment(c, "enchantment"), 1)
                                )
                                .then(
                                    Commands.argument("level", IntegerArgumentType.integer(0))
                                        .executes(
                                            c -> enchant(
                                                c.getSource(),
                                                EntityArgument.getEntities(c, "targets"),
                                                ResourceArgument.getEnchantment(c, "enchantment"),
                                                IntegerArgumentType.getInteger(c, "level")
                                            )
                                        )
                                )
                        )
                )
        );
    }

    // Folia start - region threading
    private static void sendMessage(CommandSourceStack src, CommandSyntaxException ex) {
        src.sendFailure((Component)ex.getRawMessage());
    }
    // Folia end - region threading

    private static int enchant(
        final CommandSourceStack source, final Collection<? extends Entity> targets, final Holder<Enchantment> enchantmentHolder, final int level
    ) throws CommandSyntaxException {
        Enchantment enchantment = enchantmentHolder.value();
        if (level > enchantment.getMaxLevel()) {
            throw ERROR_LEVEL_TOO_HIGH.create(level, enchantment.getMaxLevel());
        } else {
            final java.util.concurrent.atomic.AtomicInteger changed = new java.util.concurrent.atomic.AtomicInteger(0); // Folia - region threading
            final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(targets.size()); // Folia - region threading
            final java.util.concurrent.atomic.AtomicReference<Component> possibleSingleDisplayName = new java.util.concurrent.atomic.AtomicReference<>(); // Folia - region threading

            for (Entity entity : targets) {
                // Folia start - region threading
                if (entity instanceof LivingEntity oldTarget) {
                    entity.getBukkitEntity().taskScheduler.schedule((LivingEntity target) -> {
                        try {
                            ItemStack item = target.getMainHandItem();
                            if (!item.isEmpty()) {
                                if (enchantment.canEnchant(item)
                                    && EnchantmentHelper.isEnchantmentCompatible(EnchantmentHelper.getEnchantmentsForCrafting(item).keySet(), enchantmentHolder)) {
                                    item.enchant(enchantmentHolder, level);
                                    possibleSingleDisplayName.set(target.getDisplayName());
                                    changed.incrementAndGet();
                                } else if (targets.size() == 1) {
                                    throw ERROR_INCOMPATIBLE.create(item.getHoverName().getString());
                                }
                            } else if (targets.size() == 1) {
                                throw ERROR_NO_ITEM.create(target.getName().getString());
                            }
                        } catch (final CommandSyntaxException exception) {
                            sendMessage(source, exception);
                            return; // don't send feedback twice
                        }
                        sendFeedback(source, enchantmentHolder, level, possibleSingleDisplayName, count, changed);
                    }, ignored -> sendFeedback(source, enchantmentHolder, level, possibleSingleDisplayName, count, changed), 1L);
                } else if (targets.size() == 1) {
                    throw ERROR_NOT_LIVING_ENTITY.create(entity.getName().getString());
                } else {
                    sendFeedback(source, enchantmentHolder, level, possibleSingleDisplayName, count, changed);
                    // Folia end - region threading
                }
            }
            return targets.size(); // Folia - region threading
        }
    }

    // Folia start - region threading
    private static void sendFeedback(
            final CommandSourceStack source, final Holder<Enchantment> enchantmentHolder, final int level,
            final java.util.concurrent.atomic.AtomicReference<Component> possibleSingleDisplayName,
            final java.util.concurrent.atomic.AtomicInteger count,
            final java.util.concurrent.atomic.AtomicInteger changed
    ) {
        if (count.decrementAndGet() == 0) {
            final int i = changed.get();
            if (i == 0) {
                sendMessage(source, ERROR_NOTHING_HAPPENED.create());
            } else {
                if (i == 1) {
                    source.sendSuccess(
                            () -> Component.translatable(
                                    "commands.enchant.success.single", Enchantment.getFullname(enchantmentHolder, level), possibleSingleDisplayName.get()
                            ),
                            true
                    );
                } else {
                    source.sendSuccess(
                            () -> Component.translatable("commands.enchant.success.multiple", Enchantment.getFullname(enchantmentHolder, level), i),
                            true
                    );
                }
            }
        }
    }
    // Folia end - region threading
}
