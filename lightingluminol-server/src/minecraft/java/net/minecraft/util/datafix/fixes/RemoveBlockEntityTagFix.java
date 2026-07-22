package net.minecraft.util.datafix.fixes;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.OpticFinder;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.Typed;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.types.templates.List.ListType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.ExtraDataFixUtils;
import net.minecraft.util.datafix.schemas.NamespacedSchema;

public class RemoveBlockEntityTagFix extends DataFix {
    public static final org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry(); // Paper - call event on block entity removal
    private final Set<String> blockEntityIdsToDrop;
    private final boolean useLegacyDataStructure;

    public RemoveBlockEntityTagFix(final Schema outputSchema, final Set<String> blockEntityIdsToDrop) {
        this(outputSchema, false, blockEntityIdsToDrop);
    }

    public RemoveBlockEntityTagFix(final Schema outputSchema, final boolean useLegacyDataStructure, final Set<String> blockEntityIdsToDrop) {
        super(outputSchema, true);
        this.blockEntityIdsToDrop = blockEntityIdsToDrop;
        this.useLegacyDataStructure = useLegacyDataStructure;
    }

    @Override
    public TypeRewriteRule makeRule() {
        OpticFinder<String> blockEntityIdF = DSL.fieldFinder("id", NamespacedSchema.namespacedString());
        return this.useLegacyDataStructure
            ? TypeRewriteRule.seq(
                this.createItemBlockEntityRemover(blockEntityIdF, "tag", "BlockEntityTag"),
                this.createFallingBlockBlockEntityRemover(blockEntityIdF),
                this.createStructureBlockEntityRemover(blockEntityIdF),
                this.createUncheckedConverterHack()
            )
            : TypeRewriteRule.seq(
                this.createItemBlockEntityRemover(blockEntityIdF, "components", "minecraft:block_entity_data"),
                this.createFallingBlockBlockEntityRemover(blockEntityIdF),
                this.createStructureBlockEntityRemover(blockEntityIdF),
                this.createChunkBlockEntityRemover(blockEntityIdF),
                this.createUncheckedConverterHack()
            );
    }

    private TypeRewriteRule createItemBlockEntityRemover(
        final OpticFinder<String> blockEntityIdF, final String itemTagOrComponentKey, final String itemBlockEntityDataKey
    ) {
        Type<?> itemStackType = this.getInputSchema().getType(References.ITEM_STACK);
        OpticFinder<?> itemTagF = itemStackType.findField(itemTagOrComponentKey);
        OpticFinder<?> itemBlockEntityF = itemTagF.type().findField(itemBlockEntityDataKey);
        return this.fixTypeEverywhereTyped(
            "ItemRemoveBlockEntityTagFix" + this.getOutputSchema().getVersionKey(),
            itemStackType,
            input -> input.updateTyped(itemTagF, tag -> this.removeBlockEntity(tag, itemBlockEntityF, blockEntityIdF, itemBlockEntityDataKey))
        );
    }

    private TypeRewriteRule createFallingBlockBlockEntityRemover(final OpticFinder<String> blockEntityIdF) {
        Type<?> entityType = this.getInputSchema().getType(References.ENTITY);
        OpticFinder<?> fallingBlockF = DSL.namedChoice(
            "minecraft:falling_block", this.getInputSchema().getChoiceType(References.ENTITY, "minecraft:falling_block")
        );
        OpticFinder<?> fallingBlockEntityTagF = fallingBlockF.type().findField("TileEntityData");
        return this.fixTypeEverywhereTyped(
            "FallingBlockEntityRemoveBlockEntityTagFix" + this.getOutputSchema().getVersionKey(),
            entityType,
            input -> input.updateTyped(fallingBlockF, tag -> this.removeBlockEntity(tag, fallingBlockEntityTagF, blockEntityIdF, "TileEntityData"))
        );
    }

    private TypeRewriteRule createStructureBlockEntityRemover(final OpticFinder<String> blockEntityIdF) {
        Type<?> structureType = this.getInputSchema().getType(References.STRUCTURE);
        OpticFinder<?> blocksF = structureType.findField("blocks");
        OpticFinder<?> blockTypeF = DSL.typeFinder(((ListType)blocksF.type()).getElement());
        OpticFinder<?> blockNbtF = blockTypeF.type().findField("nbt");
        return this.fixTypeEverywhereTyped(
            "StructureRemoveBlockEntityTagFix" + this.getOutputSchema().getVersionKey(),
            structureType,
            input -> input.updateTyped(
                blocksF, tag -> tag.updateTyped(blockTypeF, blockTag -> this.removeBlockEntity(blockTag, blockNbtF, blockEntityIdF, "nbt"))
            )
        );
    }

    private TypeRewriteRule createChunkBlockEntityRemover(final OpticFinder<String> blockEntityIdF) {
        Type<?> chunkType = this.getInputSchema().getType(References.CHUNK);
        OpticFinder<? extends List<?>> blockEntitiesF = (OpticFinder<? extends List<?>>)chunkType.findField("block_entities");
        Type<?> blockEntityElementsType = ((ListType)blockEntitiesF.type()).getElement();
        OpticFinder<?> blockEntityTypeFinder = this.getInputSchema().getType(References.BLOCK_ENTITY).finder();
        Type<?> chunkTypeOut = this.getOutputSchema().getType(References.CHUNK);
        Type<List<?>> blockEntitiesTypeOut = (Type<List<?>>)chunkType.findField("block_entities").type();
        return this.fixTypeEverywhereTyped(
            "BlockEntityChunkRemover" + this.getOutputSchema().getVersionKey(),
            chunkType,
            chunkTypeOut,
            input -> input.update(blockEntitiesF, blockEntitiesTypeOut, listTag -> {
                ArrayList<Object> keptBlockEntities = new ArrayList<>();
                // Paper start - extract world for removal event
                // The context is not part of the schema, so we need to grab it ourselves. Do this lazily and once.
                final java.util.function.Supplier<net.kyori.adventure.key.@org.jspecify.annotations.Nullable Key> dimensionKey = com.google.common.base.Suppliers.memoize(() -> input.write()
                    .flatMap(d -> d.get(ChunkHeightAndBiomeFix.DATAFIXER_CONTEXT_TAG).get("level_identifier").asString())
                    .mapOrElse(net.kyori.adventure.key.Key::key, _ -> null)
                );
                // Paper end - extract world for removal event

                for (Object untypedBlockEntity : listTag) {
                    Typed<?> typedBlockEntity = ExtraDataFixUtils.cast(blockEntityElementsType, untypedBlockEntity, input.getOps());
                    Typed<?> typedBlockEntityUnwrapped = typedBlockEntity.getOrCreateTyped(blockEntityTypeFinder);
                    String blockEntityId = typedBlockEntityUnwrapped.getOptional(blockEntityIdF).orElse("");
                    if (!this.blockEntityIdsToDrop.contains(blockEntityId)) {
                        keptBlockEntities.add(untypedBlockEntity);
                    // Paper start - call event on block entity removal
                    } else {
                        final net.kyori.adventure.key.Key worldKey = dimensionKey.get();
                        if (worldKey == null) continue;

                        final java.util.Optional<? extends com.mojang.serialization.Dynamic<?>> blockEntityOpt = typedBlockEntity.write().result();
                        if (blockEntityOpt.isEmpty()) continue;

                        final java.util.Optional<Number> x = blockEntityOpt.flatMap(e -> e.get("x").asNumber().result());
                        final java.util.Optional<Number> y = blockEntityOpt.flatMap(e -> e.get("y").asNumber().result());
                        final java.util.Optional<Number> z = blockEntityOpt.flatMap(e -> e.get("z").asNumber().result());
                        if (x.isEmpty() || y.isEmpty() || z.isEmpty()) continue;

                        final org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer container = new org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer(DATA_TYPE_REGISTRY);
                        if (blockEntityOpt.get().get("PublicBukkitValues").orElseEmptyMap().convert(net.minecraft.nbt.NbtOps.INSTANCE).getValue() instanceof final net.minecraft.nbt.CompoundTag compoundTag) {
                            container.putAll(compoundTag);
                        }
                        new io.papermc.paper.event.server.AsyncServerDataFixerRemoveBlockEntityEvent(
                            worldKey,
                            net.kyori.adventure.key.Key.key(blockEntityId),
                            io.papermc.paper.math.Position.block(x.get().intValue(), y.get().intValue(), z.get().intValue()),
                            container
                        ).callEvent();
                    // Paper end - call event on block entity removal
                    }
                }

                return List.copyOf(keptBlockEntities);
            })
        );
    }

    private TypeRewriteRule createUncheckedConverterHack() {
        return this.convertUnchecked(
            "ItemRemoveBlockEntityTagFix - update block entity type" + this.getOutputSchema().getVersionKey(),
            this.getInputSchema().getType(References.BLOCK_ENTITY),
            this.getOutputSchema().getType(References.BLOCK_ENTITY)
        );
    }

    private Typed<?> removeBlockEntity(
        final Typed<?> tag, final OpticFinder<?> blockEntityF, final OpticFinder<String> blockEntityIdF, final String blockEntityFieldName
    ) {
        Optional<? extends Typed<?>> maybeBlockEntity = tag.getOptionalTyped(blockEntityF);
        if (maybeBlockEntity.isEmpty()) {
            return tag;
        }

        String blockEntityId = maybeBlockEntity.get().getOptional(blockEntityIdF).orElse("");
        return !this.blockEntityIdsToDrop.contains(blockEntityId)
            ? tag
            : Util.writeAndReadTypedOrThrow(tag, tag.getType(), tagData -> tagData.remove(blockEntityFieldName));
    }
}
