package net.minecraft.server.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.Dynamic2CommandExceptionType;
import com.mojang.brigadier.exceptions.Dynamic3CommandExceptionType;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.stream.Stream;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

public class AttributeCommand {
    private static final DynamicCommandExceptionType ERROR_NOT_LIVING_ENTITY = new DynamicCommandExceptionType(
        target -> Component.translatableEscape("commands.attribute.failed.entity", target)
    );
    private static final Dynamic2CommandExceptionType ERROR_NO_SUCH_ATTRIBUTE = new Dynamic2CommandExceptionType(
        (target, attribute) -> Component.translatableEscape("commands.attribute.failed.no_attribute", target, attribute)
    );
    private static final Dynamic3CommandExceptionType ERROR_NO_SUCH_MODIFIER = new Dynamic3CommandExceptionType(
        (target, attribute, modifier) -> Component.translatableEscape("commands.attribute.failed.no_modifier", attribute, target, modifier)
    );
    private static final Dynamic3CommandExceptionType ERROR_MODIFIER_ALREADY_PRESENT = new Dynamic3CommandExceptionType(
        (target, attribute, modifier) -> Component.translatableEscape("commands.attribute.failed.modifier_already_present", modifier, attribute, target)
    );

    public static void register(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext context) {
        dispatcher.register(
            Commands.literal("attribute")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(
                    Commands.argument("target", EntityArgument.entity())
                        .then(
                            Commands.argument("attribute", ResourceArgument.resource(context, Registries.ATTRIBUTE))
                                .then(
                                    Commands.literal("get")
                                        .executes(
                                            c -> getAttributeValue(
                                                c.getSource(), EntityArgument.getEntity(c, "target"), ResourceArgument.getAttribute(c, "attribute"), 1.0
                                            )
                                        )
                                        .then(
                                            Commands.argument("scale", DoubleArgumentType.doubleArg())
                                                .executes(
                                                    c -> getAttributeValue(
                                                        c.getSource(),
                                                        EntityArgument.getEntity(c, "target"),
                                                        ResourceArgument.getAttribute(c, "attribute"),
                                                        DoubleArgumentType.getDouble(c, "scale")
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("base")
                                        .then(
                                            Commands.literal("set")
                                                .then(
                                                    Commands.argument("value", DoubleArgumentType.doubleArg())
                                                        .executes(
                                                            c -> setAttributeBase(
                                                                c.getSource(),
                                                                EntityArgument.getEntity(c, "target"),
                                                                ResourceArgument.getAttribute(c, "attribute"),
                                                                DoubleArgumentType.getDouble(c, "value")
                                                            )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("get")
                                                .executes(
                                                    c -> getAttributeBase(
                                                        c.getSource(),
                                                        EntityArgument.getEntity(c, "target"),
                                                        ResourceArgument.getAttribute(c, "attribute"),
                                                        1.0
                                                    )
                                                )
                                                .then(
                                                    Commands.argument("scale", DoubleArgumentType.doubleArg())
                                                        .executes(
                                                            c -> getAttributeBase(
                                                                c.getSource(),
                                                                EntityArgument.getEntity(c, "target"),
                                                                ResourceArgument.getAttribute(c, "attribute"),
                                                                DoubleArgumentType.getDouble(c, "scale")
                                                            )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("reset")
                                                .executes(
                                                    c -> resetAttributeBase(
                                                        c.getSource(), EntityArgument.getEntity(c, "target"), ResourceArgument.getAttribute(c, "attribute")
                                                    )
                                                )
                                        )
                                )
                                .then(
                                    Commands.literal("modifier")
                                        .then(
                                            Commands.literal("add")
                                                .then(
                                                    Commands.argument("id", IdentifierArgument.id())
                                                        .then(
                                                            Commands.argument("value", DoubleArgumentType.doubleArg())
                                                                .then(
                                                                    Commands.literal("add_value")
                                                                        .executes(
                                                                            c -> addModifier(
                                                                                c.getSource(),
                                                                                EntityArgument.getEntity(c, "target"),
                                                                                ResourceArgument.getAttribute(c, "attribute"),
                                                                                IdentifierArgument.getId(c, "id"),
                                                                                DoubleArgumentType.getDouble(c, "value"),
                                                                                AttributeModifier.Operation.ADD_VALUE
                                                                            )
                                                                        )
                                                                )
                                                                .then(
                                                                    Commands.literal("add_multiplied_base")
                                                                        .executes(
                                                                            c -> addModifier(
                                                                                c.getSource(),
                                                                                EntityArgument.getEntity(c, "target"),
                                                                                ResourceArgument.getAttribute(c, "attribute"),
                                                                                IdentifierArgument.getId(c, "id"),
                                                                                DoubleArgumentType.getDouble(c, "value"),
                                                                                AttributeModifier.Operation.ADD_MULTIPLIED_BASE
                                                                            )
                                                                        )
                                                                )
                                                                .then(
                                                                    Commands.literal("add_multiplied_total")
                                                                        .executes(
                                                                            c -> addModifier(
                                                                                c.getSource(),
                                                                                EntityArgument.getEntity(c, "target"),
                                                                                ResourceArgument.getAttribute(c, "attribute"),
                                                                                IdentifierArgument.getId(c, "id"),
                                                                                DoubleArgumentType.getDouble(c, "value"),
                                                                                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
                                                                            )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("remove")
                                                .then(
                                                    Commands.argument("id", IdentifierArgument.id())
                                                        .suggests(
                                                            (c, p) -> SharedSuggestionProvider.suggestResource(
                                                                getAttributeModifiers(
                                                                    EntityArgument.getEntity(c, "target"), ResourceArgument.getAttribute(c, "attribute")
                                                                ),
                                                                p
                                                            )
                                                        )
                                                        .executes(
                                                            c -> removeModifier(
                                                                c.getSource(),
                                                                EntityArgument.getEntity(c, "target"),
                                                                ResourceArgument.getAttribute(c, "attribute"),
                                                                IdentifierArgument.getId(c, "id")
                                                            )
                                                        )
                                                )
                                        )
                                        .then(
                                            Commands.literal("value")
                                                .then(
                                                    Commands.literal("get")
                                                        .then(
                                                            Commands.argument("id", IdentifierArgument.id())
                                                                .suggests(
                                                                    (c, p) -> SharedSuggestionProvider.suggestResource(
                                                                        getAttributeModifiers(
                                                                            EntityArgument.getEntity(c, "target"),
                                                                            ResourceArgument.getAttribute(c, "attribute")
                                                                        ),
                                                                        p
                                                                    )
                                                                )
                                                                .executes(
                                                                    c -> getAttributeModifier(
                                                                        c.getSource(),
                                                                        EntityArgument.getEntity(c, "target"),
                                                                        ResourceArgument.getAttribute(c, "attribute"),
                                                                        IdentifierArgument.getId(c, "id"),
                                                                        1.0
                                                                    )
                                                                )
                                                                .then(
                                                                    Commands.argument("scale", DoubleArgumentType.doubleArg())
                                                                        .executes(
                                                                            c -> getAttributeModifier(
                                                                                c.getSource(),
                                                                                EntityArgument.getEntity(c, "target"),
                                                                                ResourceArgument.getAttribute(c, "attribute"),
                                                                                IdentifierArgument.getId(c, "id"),
                                                                                DoubleArgumentType.getDouble(c, "scale")
                                                                            )
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private static AttributeInstance getAttributeInstance(final Entity target, final Holder<Attribute> attribute) throws CommandSyntaxException {
        AttributeInstance attributeInstance = getLivingEntity(target).getAttributes().getInstance(attribute);
        if (attributeInstance == null) {
            throw ERROR_NO_SUCH_ATTRIBUTE.create(target.getName(), getAttributeDescription(attribute));
        } else {
            return attributeInstance;
        }
    }

    private static LivingEntity getLivingEntity(final Entity target) throws CommandSyntaxException {
        if (target instanceof LivingEntity livingEntity) {
            return livingEntity;
        } else {
            throw ERROR_NOT_LIVING_ENTITY.create(target.getName());
        }
    }

    private static LivingEntity getEntityWithAttribute(final Entity target, final Holder<Attribute> attribute) throws CommandSyntaxException {
        LivingEntity livingEntity = getLivingEntity(target);
        if (!livingEntity.getAttributes().hasAttribute(attribute)) {
            throw ERROR_NO_SUCH_ATTRIBUTE.create(target.getName(), getAttributeDescription(attribute));
        } else {
            return livingEntity;
        }
    }

    // Folia start - region threading
    private static void sendMessage(CommandSourceStack src, CommandSyntaxException ex) {
        src.sendFailure((Component)ex.getRawMessage());
    }
    // Folia end - region threading

    private static int getAttributeValue(final CommandSourceStack source, final Entity target, final Holder<Attribute> attribute, final double scale) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity newTarget) -> {
            try {
                // Folia end - region threading
        LivingEntity livingEntity = getEntityWithAttribute(newTarget, attribute); // Folia - region threading
        double result = livingEntity.getAttributeValue(attribute);
        source.sendSuccess(
            () -> Component.translatable("commands.attribute.value.get.success", getAttributeDescription(attribute), newTarget.getName(), result), false // Folia - region threading
        );
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 0;
        // Folia end - region threading
    }

    private static int getAttributeBase(final CommandSourceStack source, final Entity target, final Holder<Attribute> attribute, final double scale) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity newTarget) -> {
            try {
                // Folia end - region threading
        LivingEntity livingEntity = getEntityWithAttribute(newTarget, attribute); // Folia - region threading
        double result = livingEntity.getAttributeBaseValue(attribute);
        source.sendSuccess(
            () -> Component.translatable("commands.attribute.base_value.get.success", getAttributeDescription(attribute), newTarget.getName(), result), false // Folia - region threading
        );
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 0;
        // Folia end - region threading
    }

    private static int getAttributeModifier(
        final CommandSourceStack source, final Entity target, final Holder<Attribute> attribute, final Identifier id, final double scale
    ) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity newTarget) -> {
            try {
                // Folia end - region threading
        LivingEntity livingEntity = getEntityWithAttribute(newTarget, attribute); // Folia - region threading
        AttributeMap attributes = livingEntity.getAttributes();
        if (!attributes.hasModifier(attribute, id)) {
            throw ERROR_NO_SUCH_MODIFIER.create(newTarget.getName(), getAttributeDescription(attribute), id); // Folia - region threading
        }

        double result = attributes.getModifierValue(attribute, id);
        source.sendSuccess(
            () -> Component.translatable(
                "commands.attribute.modifier.value.get.success", Component.translationArg(id), getAttributeDescription(attribute), newTarget.getName(), result // Folia - region threading
            ),
            false
        );
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 0;
        // Folia end - region threading
    }

    private static Stream<Identifier> getAttributeModifiers(final Entity target, final Holder<Attribute> attribute) throws CommandSyntaxException {
        AttributeInstance attributeInstance = getAttributeInstance(target, attribute);
        return attributeInstance.getModifiers().stream().map(AttributeModifier::id);
    }

    private static int setAttributeBase(final CommandSourceStack source, final Entity target, final Holder<Attribute> attribute, final double value) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity newTarget) -> {
            try {
                // Folia end - region threading
        getAttributeInstance(newTarget, attribute).setBaseValue(value); // Folia - region threading
        source.sendSuccess(
            () -> Component.translatable("commands.attribute.base_value.set.success", getAttributeDescription(attribute), newTarget.getName(), value), false // Folia - region threading
        );
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 0;
        // Folia end - region threading
    }

    private static int resetAttributeBase(final CommandSourceStack source, final Entity target, final Holder<Attribute> attribute) throws CommandSyntaxException {
        LivingEntity livingTarget = getLivingEntity(target);
        if (!livingTarget.getAttributes().resetBaseValue(attribute)) {
            throw ERROR_NO_SUCH_ATTRIBUTE.create(target.getName(), getAttributeDescription(attribute));
        }

        double value = livingTarget.getAttributeBaseValue(attribute);
        source.sendSuccess(
            () -> Component.translatable("commands.attribute.base_value.reset.success", getAttributeDescription(attribute), target.getName(), value), false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int addModifier(
        final CommandSourceStack source,
        final Entity target,
        final Holder<Attribute> attribute,
        final Identifier id,
        final double value,
        final AttributeModifier.Operation operation
    ) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity newTarget) -> {
            try {
                // Folia end - region threading
        AttributeInstance attributeInstance = getAttributeInstance(newTarget, attribute); // Folia - region threading
        AttributeModifier modifier = new AttributeModifier(id, value, operation);
        if (attributeInstance.hasModifier(id)) {
            throw ERROR_MODIFIER_ALREADY_PRESENT.create(newTarget.getName(), getAttributeDescription(attribute), id); // Folia - region threading
        }

        attributeInstance.addPermanentModifier(modifier);
        source.sendSuccess(
            () -> Component.translatable(
                "commands.attribute.modifier.add.success", Component.translationArg(id), getAttributeDescription(attribute), newTarget.getName() // Folia - region threading
            ),
            false
        );
        return; // Folia - region threading
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 0;
        // Folia end - region threading
    }

    private static int removeModifier(final CommandSourceStack source, final Entity target, final Holder<Attribute> attribute, final Identifier id) throws CommandSyntaxException {
        // Folia start - region threading
        target.getBukkitEntity().taskScheduler.scheduleOrExecute((Entity newTarget) -> {
            try {
                // Folia end - region threading
        AttributeInstance attributeInstance = getAttributeInstance(newTarget, attribute); // Folia - region threading
        if (attributeInstance.removeModifier(id)) {
            source.sendSuccess(
                () -> Component.translatable(
                    "commands.attribute.modifier.remove.success", Component.translationArg(id), getAttributeDescription(attribute), newTarget.getName() // Folia - region threading
                ),
                false
            );
            return; // Folia - region threading
        } else {
            throw ERROR_NO_SUCH_MODIFIER.create(newTarget.getName(), getAttributeDescription(attribute), id); // Folia - region threading
        }
        // Folia start - region threading
            } catch (CommandSyntaxException ex) {
                sendMessage(source, ex);
            }
        });
        return 0;
        // Folia end - region threading
    }

    private static Component getAttributeDescription(final Holder<Attribute> attribute) {
        return Component.translatable(attribute.value().getDescriptionId());
    }
}
