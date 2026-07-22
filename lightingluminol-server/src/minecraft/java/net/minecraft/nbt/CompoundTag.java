package net.minecraft.nbt;

import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class CompoundTag implements Tag {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Codec<CompoundTag> CODEC = Codec.PASSTHROUGH
        .comapFlatMap(
            t -> {
                Tag tag = t.convert(NbtOps.INSTANCE).getValue();
                return tag instanceof CompoundTag compoundTag
                    ? DataResult.success(compoundTag == t.getValue() ? compoundTag.copy() : compoundTag)
                    : DataResult.error(() -> "Not a compound tag: " + tag);
            },
            t -> new Dynamic<>(NbtOps.INSTANCE, t.copy())
        );
    private static final int SELF_SIZE_IN_BYTES = 48;
    private static final int MAP_ENTRY_SIZE_IN_BYTES = 32;
    public static final TagType<CompoundTag> TYPE = new TagType.VariableSize<CompoundTag>() {
        @Override
        public CompoundTag load(final DataInput input, final NbtAccounter accounter) throws IOException {
            accounter.pushDepth();

            try {
                return loadCompound(input, accounter);
            } finally {
                accounter.popDepth();
            }
        }

        private static CompoundTag loadCompound(final DataInput input, final NbtAccounter accounter) throws IOException {
            accounter.accountBytes(48L);
            it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, Tag> values = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(8, 0.8f); // Paper - Reduce memory footprint of CompoundTag

            byte tagType;
            while ((tagType = input.readByte()) != 0) {
                String key = readString(input, accounter);
                Tag tag = CompoundTag.readNamedTagData(TagTypes.getType(tagType), key, input, accounter);
                if (values.put(key, tag) == null) {
                    accounter.accountBytes(36L);
                }
            }

            return new CompoundTag(values);
        }

        @Override
        public StreamTagVisitor.ValueResult parse(final DataInput input, final StreamTagVisitor output, final NbtAccounter accounter) throws IOException {
            accounter.pushDepth();

            try {
                return parseCompound(input, output, accounter);
            } finally {
                accounter.popDepth();
            }
        }

        private static StreamTagVisitor.ValueResult parseCompound(final DataInput input, final StreamTagVisitor output, final NbtAccounter accounter) throws IOException {
            accounter.accountBytes(48L);

            byte tagTypeId;
            label35:
            while ((tagTypeId = input.readByte()) != 0) {
                TagType<?> tagType = TagTypes.getType(tagTypeId);
                switch (output.visitEntry(tagType)) {
                    case HALT:
                        return StreamTagVisitor.ValueResult.HALT;
                    case BREAK:
                        StringTag.skipString(input);
                        tagType.skip(input, accounter);
                        break label35;
                    case SKIP:
                        StringTag.skipString(input);
                        tagType.skip(input, accounter);
                        break;
                    default:
                        String key = readString(input, accounter);
                        switch (output.visitEntry(tagType, key)) {
                            case HALT:
                                return StreamTagVisitor.ValueResult.HALT;
                            case BREAK:
                                tagType.skip(input, accounter);
                                break label35;
                            case SKIP:
                                tagType.skip(input, accounter);
                                break;
                            default:
                                accounter.accountBytes(36L);
                                switch (tagType.parse(input, output, accounter)) {
                                    case HALT:
                                        return StreamTagVisitor.ValueResult.HALT;
                                    case BREAK:
                                }
                        }
                }
            }

            if (tagTypeId != 0) {
                while ((tagTypeId = input.readByte()) != 0) {
                    StringTag.skipString(input);
                    TagTypes.getType(tagTypeId).skip(input, accounter);
                }
            }

            return output.visitContainerEnd();
        }

        private static String readString(final DataInput input, final NbtAccounter accounter) throws IOException {
            String key = input.readUTF();
            accounter.accountBytes(28L);
            accounter.accountBytes(2L, key.length());
            return key;
        }

        @Override
        public void skip(final DataInput input, final NbtAccounter accounter) throws IOException {
            accounter.pushDepth();

            byte tagTypeId;
            try {
                while ((tagTypeId = input.readByte()) != 0) {
                    StringTag.skipString(input);
                    TagTypes.getType(tagTypeId).skip(input, accounter);
                }
            } finally {
                accounter.popDepth();
            }
        }

        @Override
        public String getName() {
            return "COMPOUND";
        }

        @Override
        public String getPrettyName() {
            return "TAG_Compound";
        }
    };
    private final Map<String, Tag> tags;

    CompoundTag(final Map<String, Tag> tags) {
        this.tags = tags;
    }

    public CompoundTag() {
        this(new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(8, 0.8f)); // Paper - Reduce memory footprint of CompoundTag
    }

    @Override
    public void write(final DataOutput output) throws IOException {
        for (String key : this.tags.keySet()) {
            Tag tag = this.tags.get(key);
            writeNamedTag(key, tag, output);
        }

        output.writeByte(0);
    }

    @Override
    public int sizeInBytes() {
        int size = 48;

        for (Entry<String, Tag> entry : this.tags.entrySet()) {
            size += 28 + 2 * entry.getKey().length();
            size += 36;
            size += entry.getValue().sizeInBytes();
        }

        return size;
    }

    public Set<String> keySet() {
        return this.tags.keySet();
    }

    public Set<Entry<String, Tag>> entrySet() {
        return this.tags.entrySet();
    }

    public Collection<Tag> values() {
        return this.tags.values();
    }

    public void forEach(final BiConsumer<String, Tag> consumer) {
        this.tags.forEach(consumer);
    }

    @Override
    public byte getId() {
        return Tag.TAG_COMPOUND;
    }

    @Override
    public TagType<CompoundTag> getType() {
        return TYPE;
    }

    public int size() {
        return this.tags.size();
    }

    public @Nullable Tag put(final String name, final Tag tag) {
        return this.tags.put(name, tag);
    }

    public void putByte(final String name, final byte value) {
        this.tags.put(name, ByteTag.valueOf(value));
    }

    public void putShort(final String name, final short value) {
        this.tags.put(name, ShortTag.valueOf(value));
    }

    public void putInt(final String name, final int value) {
        this.tags.put(name, IntTag.valueOf(value));
    }

    public void putLong(final String name, final long value) {
        this.tags.put(name, LongTag.valueOf(value));
    }

    public void putFloat(final String name, final float value) {
        this.tags.put(name, FloatTag.valueOf(value));
    }

    public void putDouble(final String name, final double value) {
        this.tags.put(name, DoubleTag.valueOf(value));
    }

    public void putString(final String name, final String value) {
        this.tags.put(name, StringTag.valueOf(value));
    }

    public void putByteArray(final String name, final byte[] value) {
        this.tags.put(name, new ByteArrayTag(value));
    }

    public void putIntArray(final String name, final int[] value) {
        this.tags.put(name, new IntArrayTag(value));
    }

    public void putLongArray(final String name, final long[] value) {
        this.tags.put(name, new LongArrayTag(value));
    }

    public void putBoolean(final String name, final boolean value) {
        this.tags.put(name, ByteTag.valueOf(value));
    }

    public @Nullable Tag get(final String name) {
        return this.tags.get(name);
    }

    public boolean contains(final String name) {
        return this.tags.containsKey(name);
    }

    private Optional<Tag> getOptional(final String name) {
        return Optional.ofNullable(this.tags.get(name));
    }

    public Optional<Byte> getByte(final String name) {
        return this.getOptional(name).flatMap(Tag::asByte);
    }

    public byte getByteOr(final String name, final byte defaultValue) {
        return this.tags.get(name) instanceof NumericTag tag ? tag.byteValue() : defaultValue;
    }

    public Optional<Short> getShort(final String name) {
        return this.getOptional(name).flatMap(Tag::asShort);
    }

    public short getShortOr(final String name, final short defaultValue) {
        return this.tags.get(name) instanceof NumericTag tag ? tag.shortValue() : defaultValue;
    }

    public Optional<Integer> getInt(final String name) {
        return this.getOptional(name).flatMap(Tag::asInt);
    }

    public int getIntOr(final String name, final int defaultValue) {
        return this.tags.get(name) instanceof NumericTag tag ? tag.intValue() : defaultValue;
    }

    public Optional<Long> getLong(final String name) {
        return this.getOptional(name).flatMap(Tag::asLong);
    }

    public long getLongOr(final String name, final long defaultValue) {
        return this.tags.get(name) instanceof NumericTag tag ? tag.longValue() : defaultValue;
    }

    public Optional<Float> getFloat(final String name) {
        return this.getOptional(name).flatMap(Tag::asFloat);
    }

    public float getFloatOr(final String name, final float defaultValue) {
        return this.tags.get(name) instanceof NumericTag tag ? tag.floatValue() : defaultValue;
    }

    public Optional<Double> getDouble(final String name) {
        return this.getOptional(name).flatMap(Tag::asDouble);
    }

    public double getDoubleOr(final String name, final double defaultValue) {
        return this.tags.get(name) instanceof NumericTag tag ? tag.doubleValue() : defaultValue;
    }

    public Optional<String> getString(final String name) {
        return this.getOptional(name).flatMap(Tag::asString);
    }

    public String getStringOr(final String name, final String defaultValue) {
        return this.tags.get(name) instanceof StringTag(String var8) ? var8 : defaultValue;
    }

    public Optional<byte[]> getByteArray(final String name) {
        return this.tags.get(name) instanceof ByteArrayTag tag ? Optional.of(tag.getAsByteArray()) : Optional.empty();
    }

    public Optional<int[]> getIntArray(final String name) {
        return this.tags.get(name) instanceof IntArrayTag tag ? Optional.of(tag.getAsIntArray()) : Optional.empty();
    }

    public Optional<long[]> getLongArray(final String name) {
        return this.tags.get(name) instanceof LongArrayTag tag ? Optional.of(tag.getAsLongArray()) : Optional.empty();
    }

    public Optional<CompoundTag> getCompound(final String name) {
        return this.tags.get(name) instanceof CompoundTag tag ? Optional.of(tag) : Optional.empty();
    }

    public CompoundTag getCompoundOrEmpty(final String name) {
        return this.getCompound(name).orElseGet(CompoundTag::new);
    }

    public Optional<ListTag> getList(final String name) {
        return this.tags.get(name) instanceof ListTag tag ? Optional.of(tag) : Optional.empty();
    }

    public ListTag getListOrEmpty(final String name) {
        return this.getList(name).orElseGet(ListTag::new);
    }

    public Optional<Boolean> getBoolean(final String name) {
        return this.getOptional(name).flatMap(Tag::asBoolean);
    }

    public boolean getBooleanOr(final String string, final boolean defaultValue) {
        return this.getByteOr(string, (byte)(defaultValue ? 1 : 0)) != 0;
    }

    public @Nullable Tag remove(final String name) {
        return this.tags.remove(name);
    }

    @Override
    public String toString() {
        StringTagVisitor visitor = new StringTagVisitor();
        visitor.visitCompound(this);
        return visitor.build();
    }

    public boolean isEmpty() {
        return this.tags.isEmpty();
    }

    CompoundTag shallowCopy() {
        return new CompoundTag(new HashMap<>(this.tags));
    }

    @Override
    public CompoundTag copy() {
        // Paper start - Reduce memory footprint of CompoundTag
        it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<String, Tag> ret = new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(this.tags.size(), 0.8f);
        java.util.Iterator<java.util.Map.Entry<String, Tag>> iterator = (this.tags instanceof it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap) ? ((it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap)this.tags).object2ObjectEntrySet().fastIterator() : this.tags.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Tag> entry = iterator.next();
            ret.put(entry.getKey(), entry.getValue().copy());
        }

        return new CompoundTag(ret);
        // Paper end - Reduce memory footprint of CompoundTag
    }

    @Override
    public Optional<CompoundTag> asCompound() {
        return Optional.of(this);
    }

    @Override
    public boolean equals(final Object obj) {
        return this == obj || obj instanceof CompoundTag compoundTag && Objects.equals(this.tags, compoundTag.tags);
    }

    @Override
    public int hashCode() {
        return this.tags.hashCode();
    }

    private static void writeNamedTag(final String name, final Tag tag, final DataOutput output) throws IOException {
        output.writeByte(tag.getId());
        if (tag.getId() != 0) {
            output.writeUTF(name);
            tag.write(output);
        }
    }

    private static Tag readNamedTagData(final TagType<?> type, final String name, final DataInput input, final NbtAccounter accounter) {
        try {
            return type.load(input, accounter);
        } catch (IOException e) {
            CrashReport report = CrashReport.forThrowable(e, "Loading NBT data");
            CrashReportCategory category = report.addCategory("NBT Tag");
            category.setDetail("Tag name", name);
            category.setDetail("Tag type", type.getName());
            throw new ReportedNbtException(report);
        }
    }

    public CompoundTag merge(final CompoundTag other) {
        for (String tagName : other.tags.keySet()) {
            Tag otherTag = other.tags.get(tagName);
            if (otherTag instanceof CompoundTag otherCompound && this.tags.get(tagName) instanceof CompoundTag selfCompound) {
                selfCompound.merge(otherCompound);
            } else {
                this.put(tagName, otherTag.copy());
            }
        }

        return this;
    }

    @Override
    public void accept(final TagVisitor visitor) {
        visitor.visitCompound(this);
    }

    @Override
    public StreamTagVisitor.ValueResult accept(final StreamTagVisitor visitor) {
        for (Entry<String, Tag> entry : this.tags.entrySet()) {
            Tag value = entry.getValue();
            TagType<?> type = value.getType();
            StreamTagVisitor.EntryResult entryParseResult = visitor.visitEntry(type);
            switch (entryParseResult) {
                case HALT:
                    return StreamTagVisitor.ValueResult.HALT;
                case BREAK:
                    return visitor.visitContainerEnd();
                case SKIP:
                    break;
                default:
                    entryParseResult = visitor.visitEntry(type, entry.getKey());
                    switch (entryParseResult) {
                        case HALT:
                            return StreamTagVisitor.ValueResult.HALT;
                        case BREAK:
                            return visitor.visitContainerEnd();
                        case SKIP:
                            break;
                        default:
                            StreamTagVisitor.ValueResult valueResult = value.accept(visitor);
                            switch (valueResult) {
                                case HALT:
                                    return StreamTagVisitor.ValueResult.HALT;
                                case BREAK:
                                    return visitor.visitContainerEnd();
                            }
                    }
            }
        }

        return visitor.visitContainerEnd();
    }

    public <T> void store(final String name, final Codec<T> codec, final T value) {
        this.store(name, codec, NbtOps.INSTANCE, value);
    }

    public <T> void storeNullable(final String name, final Codec<T> codec, final @Nullable T value) {
        if (value != null) {
            this.store(name, codec, value);
        }
    }

    public <T> void store(final String name, final Codec<T> codec, final DynamicOps<Tag> ops, final T value) {
        this.put(name, codec.encodeStart(ops, value).getOrThrow());
    }

    public <T> void storeNullable(final String name, final Codec<T> codec, final DynamicOps<Tag> ops, final @Nullable T value) {
        if (value != null) {
            this.store(name, codec, ops, value);
        }
    }

    public <T> void store(final MapCodec<T> codec, final T value) {
        this.store(codec, NbtOps.INSTANCE, value);
    }

    public <T> void store(final MapCodec<T> codec, final DynamicOps<Tag> ops, final T value) {
        this.merge((CompoundTag)codec.encoder().encodeStart(ops, value).getOrThrow());
    }

    public <T> Optional<T> read(final String name, final Codec<T> codec) { // Paper - option to read via codec without logging errors - diff on change
        return this.read(name, codec, NbtOps.INSTANCE);
    }

    public <T> Optional<T> read(final String name, final Codec<T> codec, final DynamicOps<Tag> ops) { // Paper - option to read via codec without logging errors - diff on change
        Tag tag = this.get(name);
        return tag == null
            ? Optional.empty()
            : codec.parse(ops, tag).resultOrPartial(error -> LOGGER.error("Failed to read field ({}={}): {}", name, tag, error));
    }

    public <T> Optional<T> read(final MapCodec<T> codec) { // Paper - option to read via codec without logging errors - diff on change
        return this.read(codec, NbtOps.INSTANCE);
    }

    public <T> Optional<T> read(final MapCodec<T> codec, final DynamicOps<Tag> ops) { // Paper - option to read via codec without logging errors - diff on change
        return codec.decode(ops, ops.getMap(this).getOrThrow()).resultOrPartial(error -> LOGGER.error("Failed to read value ({}): {}", this, error));
    }

    // Paper start - option to read via codec without logging errors
    // The below methods are 1 to 1 copies of the above-defined read methods without the logging part.
    // Copying was chosen over overloading the read methods as a boolean parameter to mark a method as quiet
    // is not intuitive and would require even more overloads.
    // Not a lot of diff in these methods is expected
    public <T> Optional<T> readQuiet(String name, Codec<T> codec) {
        return this.readQuiet(name, codec, NbtOps.INSTANCE);
    }

    public <T> Optional<T> readQuiet(String name, Codec<T> codec, DynamicOps<Tag> ops) {
        Tag tag = this.get(name);
        return tag == null
            ? Optional.empty()
            : codec.parse(ops, tag).resultOrPartial();
    }

    public <T> Optional<T> readQuiet(MapCodec<T> codec) {
        return this.readQuiet(codec, NbtOps.INSTANCE);
    }

    public <T> Optional<T> readQuiet(MapCodec<T> codec, DynamicOps<Tag> ops) {
        return codec.decode(ops, ops.getMap(this).getOrThrow()).resultOrPartial();
    }
    // Paper end - option to read via codec without logging errors
}
