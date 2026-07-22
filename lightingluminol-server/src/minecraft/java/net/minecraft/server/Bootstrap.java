package net.minecraft.server;

import com.mojang.logging.LogUtils;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.SuppressForbidden;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.selector.options.EntitySelectorOptions;
import net.minecraft.core.cauldron.CauldronInteractions;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.minecraft.world.level.gamerules.GameRules;
import org.slf4j.Logger;

@SuppressForbidden(reason = "System.out setup")
public class Bootstrap {
    public static final PrintStream STDOUT = System.out;
    private static volatile boolean isBootstrapped;
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final AtomicLong bootstrapDuration = new AtomicLong(-1L);

    public static void bootStrap() {
        if (!isBootstrapped) {
            isBootstrapped = true;
            Instant start = Instant.now();
            io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler.enterBootstrappers(); // Paper - Entrypoint for bootstrapping
            if (BuiltInRegistries.REGISTRY.keySet().isEmpty()) {
                throw new IllegalStateException("Unable to load registries");
            }

            FireBlock.bootStrap();
            ComposterBlock.bootStrap();
            if (EntityType.getKey(EntityTypes.PLAYER) == null) {
                throw new IllegalStateException("Failed loading EntityTypes");
            }

            EntitySelectorOptions.bootStrap();
            DispenseItemBehavior.bootStrap();
            CauldronInteractions.bootStrap();
            // Paper start
            BuiltInRegistries.bootStrap(() -> {
                io.papermc.paper.world.worldgen.OptionallyFlatBedrockConditionSource.bootstrap(); // Paper - Flat bedrock generator settings
            });
            // Paper end
            CreativeModeTabs.validate();
            wrapStreams();
            bootstrapDuration.set(Duration.between(start, Instant.now()).toMillis());
            // CraftBukkit start
            // TODO Check what of this is needed, maybe report it to Mojira. if deemed relevant, move to the respective classes
            //  Used in CraftLegacy
            for (int i = 0; i <= 15; i++) {
                net.minecraft.util.datafix.fixes.BlockStateData.register(
                    1008 + i,
                    new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                        "Name", "minecraft:oak_sign",
                        "Properties", java.util.Map.of("rotation", String.valueOf(i))
                    )).convert(net.minecraft.nbt.NbtOps.INSTANCE),
                    new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                        "Name", "minecraft:standing_sign",
                        "Properties", java.util.Map.of("rotation", String.valueOf(i))
                    )).convert(net.minecraft.nbt.NbtOps.INSTANCE)
                );
            }

            net.minecraft.util.datafix.fixes.BlockStateData.register(1440,
                new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                    "Name", "minecraft:portal",
                    "Properties", java.util.Map.of("axis", "x")
                )).convert(net.minecraft.nbt.NbtOps.INSTANCE),
                new com.mojang.serialization.Dynamic<>(com.mojang.serialization.JavaOps.INSTANCE, java.util.Map.of(
                    "Name", "minecraft:portal",
                    "Properties", java.util.Map.of("axis", "x")
                )).convert(net.minecraft.nbt.NbtOps.INSTANCE)
            );

            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(409, "minecraft:prismarine_shard");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(410, "minecraft:prismarine_crystals");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(411, "minecraft:rabbit");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(412, "minecraft:cooked_rabbit");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(413, "minecraft:rabbit_stew");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(414, "minecraft:rabbit_foot");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(415, "minecraft:rabbit_hide");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(416, "minecraft:armor_stand");

            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(423, "minecraft:mutton");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(424, "minecraft:cooked_mutton");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(425, "minecraft:banner");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(426, "minecraft:end_crystal");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(427, "minecraft:spruce_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(428, "minecraft:birch_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(429, "minecraft:jungle_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(430, "minecraft:acacia_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(431, "minecraft:dark_oak_door");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(432, "minecraft:chorus_fruit");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(433, "minecraft:chorus_fruit_popped");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(434, "minecraft:beetroot");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(435, "minecraft:beetroot_seeds");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(436, "minecraft:beetroot_soup");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(437, "minecraft:dragon_breath");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(438, "minecraft:splash_potion");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(439, "minecraft:spectral_arrow");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(440, "minecraft:tipped_arrow");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(441, "minecraft:lingering_potion");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(442, "minecraft:shield");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(443, "minecraft:elytra");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(444, "minecraft:spruce_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(445, "minecraft:birch_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(446, "minecraft:jungle_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(447, "minecraft:acacia_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(448, "minecraft:dark_oak_boat");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(449, "minecraft:totem_of_undying");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(450, "minecraft:shulker_shell");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(452, "minecraft:iron_nugget");
            net.minecraft.util.datafix.fixes.ItemIdFix.ITEM_NAMES.put(453, "minecraft:knowledge_book");

            net.minecraft.util.datafix.fixes.ItemSpawnEggFix.ID_TO_ENTITY[23] = "Arrow";
            // CraftBukkit end
        }
    }

    private static <T> void checkTranslations(
        final Language language, final Iterable<T> registry, final Function<T, String> descriptionGetter, final Set<String> output
    ) {
        registry.forEach(t -> {
            String id = descriptionGetter.apply((T)t);
            if (!language.has(id)) {
                output.add(id);
            }
        });
    }

    private static void checkGameruleTranslations(final Language language, final Set<String> missing) {
        GameRules rules = new GameRules(FeatureFlags.REGISTRY.allFlags());
        rules.visitGameRuleTypes(new GameRuleTypeVisitor() {
            @Override
            public <T> void visit(final GameRule<T> gameRule) {
                if (!language.has(gameRule.getDescriptionId())) {
                    missing.add(gameRule.id());
                }
            }
        });
    }

    public static Set<String> getMissingTranslations(final Language language) {
        Set<String> missing = new TreeSet<>();
        checkTranslations(language, BuiltInRegistries.ATTRIBUTE, Attribute::getDescriptionId, missing);
        checkTranslations(language, BuiltInRegistries.ENTITY_TYPE, EntityType::getDescriptionId, missing);
        checkTranslations(language, BuiltInRegistries.MOB_EFFECT, MobEffect::getDescriptionId, missing);
        checkTranslations(language, BuiltInRegistries.ITEM, Item::getDescriptionId, missing);
        checkTranslations(language, BuiltInRegistries.BLOCK, BlockBehaviour::getDescriptionId, missing);
        checkTranslations(language, BuiltInRegistries.CUSTOM_STAT, id -> "stat." + id.toString().replace(':', '.'), missing);
        checkGameruleTranslations(language, missing);
        return missing;
    }

    public static void checkBootstrapCalled(final Supplier<String> location) {
        if (!isBootstrapped) {
            throw createBootstrapException(location);
        }
    }

    private static RuntimeException createBootstrapException(final Supplier<String> location) {
        try {
            String resolvedLocation = location.get();
            return new IllegalArgumentException("Not bootstrapped (called from " + resolvedLocation + ")");
        } catch (Exception e) {
            RuntimeException result = new IllegalArgumentException("Not bootstrapped (failed to resolve location)");
            result.addSuppressed(e);
            return result;
        }
    }

    public static void validate() {
        checkBootstrapCalled(() -> "validate");
        if (SharedConstants.IS_RUNNING_IN_IDE) {
            getMissingTranslations(Language.DEFAULT_INSTANCE).forEach(key -> LOGGER.error("Missing translations: {}", key));
            Commands.validate();
        }

        DefaultAttributes.validate();
    }

    private static void wrapStreams() {
        if (LOGGER.isDebugEnabled()) {
            System.setErr(new DebugLoggedPrintStream("STDERR", System.err));
            System.setOut(new DebugLoggedPrintStream("STDOUT", STDOUT));
        } else {
            System.setErr(new LoggedPrintStream("STDERR", System.err));
            System.setOut(new LoggedPrintStream("STDOUT", STDOUT));
        }
    }

    public static void realStdoutPrintln(final String string) {
        STDOUT.println(string);
    }

    public static void shutdownStdout() {
        STDOUT.close();
    }
}
