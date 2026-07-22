package net.minecraft.nbt;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.RecordBuilder.AbstractStringBuilder;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

public class NbtOps implements DynamicOps<Tag> {
    public static final NbtOps INSTANCE = new NbtOps();

    private NbtOps() {
    }

    @Override
    public Tag empty() {
        return EndTag.INSTANCE;
    }

    @Override
    public Tag emptyList() {
        return new ListTag();
    }

    @Override
    public Tag emptyMap() {
        return new CompoundTag();
    }

    @Override
    public <U> U convertTo(final DynamicOps<U> outOps, final Tag input) {
        return (U)(switch (input) {
            case EndTag ignored -> outOps.empty();
            case ByteTag(byte value) -> outOps.createByte(value);
            case ShortTag(short value) -> outOps.createShort(value);
            case IntTag(int value) -> outOps.createInt(value);
            case LongTag(long value) -> outOps.createLong(value);
            case FloatTag(float value) -> outOps.createFloat(value);
            case DoubleTag(double value) -> outOps.createDouble(value);
            case ByteArrayTag byteArrayTag -> outOps.createByteList(ByteBuffer.wrap(byteArrayTag.getAsByteArray()));
            case StringTag(String value) -> outOps.createString(value);
            case ListTag listTag -> this.convertList(outOps, listTag);
            case CompoundTag compoundTag -> this.convertMap(outOps, compoundTag);
            case IntArrayTag intArrayTag -> outOps.createIntList(Arrays.stream(intArrayTag.getAsIntArray()));
            case LongArrayTag longArrayTag -> outOps.createLongList(Arrays.stream(longArrayTag.getAsLongArray()));
            default -> throw new MatchException(null, null);
        });
    }

    @Override
    public DataResult<Number> getNumberValue(final Tag input) {
        return input.asNumber().map(DataResult::success).orElseGet(() -> DataResult.error(() -> "Not a number"));
    }

    @Override
    public Tag createNumeric(final Number i) {
        return DoubleTag.valueOf(i.doubleValue());
    }

    @Override
    public Tag createByte(final byte value) {
        return ByteTag.valueOf(value);
    }

    @Override
    public Tag createShort(final short value) {
        return ShortTag.valueOf(value);
    }

    @Override
    public Tag createInt(final int value) {
        return IntTag.valueOf(value);
    }

    @Override
    public Tag createLong(final long value) {
        return LongTag.valueOf(value);
    }

    @Override
    public Tag createFloat(final float value) {
        return FloatTag.valueOf(value);
    }

    @Override
    public Tag createDouble(final double value) {
        return DoubleTag.valueOf(value);
    }

    @Override
    public DataResult<Boolean> getBooleanValue(final Tag input) {
        return this.getNumberValue(input).map(value -> value.doubleValue() != 0.0);
    }

    @Override
    public Tag createBoolean(final boolean value) {
        return ByteTag.valueOf(value);
    }

    @Override
    public DataResult<String> getStringValue(final Tag input) {
        return input instanceof StringTag(String value) ? DataResult.success(value) : DataResult.error(() -> "Not a string");
    }

    @Override
    public Tag createString(final String value) {
        return StringTag.valueOf(value);
    }

    @Override
    public DataResult<Tag> mergeToList(final Tag list, final Tag value) {
        return createCollector(list)
            .map(collector -> DataResult.success(collector.accept(value).result()))
            .orElseGet(() -> DataResult.error(() -> "mergeToList called with not a list: " + list, list));
    }

    @Override
    public DataResult<Tag> mergeToList(final Tag list, final List<Tag> values) {
        return createCollector(list)
            .map(collector -> DataResult.success(collector.acceptAll(values).result()))
            .orElseGet(() -> DataResult.error(() -> "mergeToList called with not a list: " + list, list));
    }

    @Override
    public DataResult<Tag> mergeToMap(final Tag map, final Tag key, final Tag value) {
        if (!(map instanceof CompoundTag) && !(map instanceof EndTag)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        } else if (key instanceof StringTag(String stringKey)) {
            CompoundTag output = map instanceof CompoundTag tag ? tag.shallowCopy() : new CompoundTag();
            output.put(stringKey, value);
            return DataResult.success(output);
        } else {
            return DataResult.error(() -> "key is not a string: " + key, map);
        }
    }

    @Override
    public DataResult<Tag> mergeToMap(final Tag map, final MapLike<Tag> values) {
        if (!(map instanceof CompoundTag) && !(map instanceof EndTag)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        }

        Iterator<Pair<Tag, Tag>> valuesIterator = values.entries().iterator();
        if (!valuesIterator.hasNext()) {
            return map == this.empty() ? DataResult.success(this.emptyMap()) : DataResult.success(map);
        }

        CompoundTag output = map instanceof CompoundTag tag ? tag.shallowCopy() : new CompoundTag();
        List<Tag> missed = new ArrayList<>();
        valuesIterator.forEachRemaining(entry -> {
            Tag key = entry.getFirst();
            if (key instanceof StringTag(String stringKey)) {
                output.put(stringKey, entry.getSecond());
            } else {
                missed.add(key);
            }
        });
        return !missed.isEmpty() ? DataResult.error(() -> "some keys are not strings: " + missed, output) : DataResult.success(output);
    }

    @Override
    public DataResult<Tag> mergeToMap(final Tag map, final Map<Tag, Tag> values) {
        if (!(map instanceof CompoundTag) && !(map instanceof EndTag)) {
            return DataResult.error(() -> "mergeToMap called with not a map: " + map, map);
        }

        if (values.isEmpty()) {
            return map == this.empty() ? DataResult.success(this.emptyMap()) : DataResult.success(map);
        }

        CompoundTag output = map instanceof CompoundTag tag ? tag.shallowCopy() : new CompoundTag();
        List<Tag> missed = new ArrayList<>();

        for (Entry<Tag, Tag> entry : values.entrySet()) {
            Tag key = entry.getKey();
            if (key instanceof StringTag(String stringKey)) {
                output.put(stringKey, entry.getValue());
            } else {
                missed.add(key);
            }
        }

        return !missed.isEmpty() ? DataResult.error(() -> "some keys are not strings: " + missed, output) : DataResult.success(output);
    }

    @Override
    public DataResult<Stream<Pair<Tag, Tag>>> getMapValues(final Tag input) {
        return input instanceof CompoundTag tag
            ? DataResult.success(tag.entrySet().stream().map(entry -> Pair.of(this.createString(entry.getKey()), entry.getValue())))
            : DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<Consumer<BiConsumer<Tag, Tag>>> getMapEntries(final Tag input) {
        return input instanceof CompoundTag tag ? DataResult.success(c -> {
            for (Entry<String, Tag> entry : tag.entrySet()) {
                c.accept(this.createString(entry.getKey()), entry.getValue());
            }
        }) : DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public DataResult<MapLike<Tag>> getMap(final Tag input) {
        return input instanceof CompoundTag tag ? DataResult.success(new MapLike<Tag>() {
            @Override
            public @Nullable Tag get(final Tag key) {
                if (key instanceof StringTag(String stringKey)) {
                    return tag.get(stringKey);
                } else {
                    throw new UnsupportedOperationException("Cannot get map entry with non-string key: " + key);
                }
            }

            @Override
            public @Nullable Tag get(final String key) {
                return tag.get(key);
            }

            @Override
            public Stream<Pair<Tag, Tag>> entries() {
                return tag.entrySet().stream().map(entry -> Pair.of(NbtOps.this.createString(entry.getKey()), entry.getValue()));
            }

            @Override
            public String toString() {
                return "MapLike[" + tag + "]";
            }
        }) : DataResult.error(() -> "Not a map: " + input);
    }

    @Override
    public Tag createMap(final Stream<Pair<Tag, Tag>> map) {
        CompoundTag tag = new CompoundTag();
        map.forEach(entry -> {
            Tag key = entry.getFirst();
            Tag value = entry.getSecond();
            if (key instanceof StringTag(String stringKey)) {
                tag.put(stringKey, value);
            } else {
                throw new UnsupportedOperationException("Cannot create map with non-string key: " + key);
            }
        });
        return tag;
    }

    @Override
    public DataResult<Stream<Tag>> getStream(final Tag input) {
        return input instanceof CollectionTag collection ? DataResult.success(collection.stream()) : DataResult.error(() -> "Not a list");
    }

    @Override
    public DataResult<Consumer<Consumer<Tag>>> getList(final Tag input) {
        return input instanceof CollectionTag collection ? DataResult.success(collection::forEach) : DataResult.error(() -> "Not a list: " + input);
    }

    @Override
    public DataResult<ByteBuffer> getByteBuffer(final Tag input) {
        return input instanceof ByteArrayTag array ? DataResult.success(ByteBuffer.wrap(array.getAsByteArray())) : DynamicOps.super.getByteBuffer(input);
    }

    @Override
    public Tag createByteList(final ByteBuffer input) {
        ByteBuffer wholeBuffer = input.duplicate().clear();
        byte[] bytes = new byte[input.capacity()];
        wholeBuffer.get(0, bytes, 0, bytes.length);
        return new ByteArrayTag(bytes);
    }

    @Override
    public DataResult<IntStream> getIntStream(final Tag input) {
        return input instanceof IntArrayTag array ? DataResult.success(Arrays.stream(array.getAsIntArray())) : DynamicOps.super.getIntStream(input);
    }

    @Override
    public Tag createIntList(final IntStream input) {
        return new IntArrayTag(input.toArray());
    }

    @Override
    public DataResult<LongStream> getLongStream(final Tag input) {
        return input instanceof LongArrayTag array ? DataResult.success(Arrays.stream(array.getAsLongArray())) : DynamicOps.super.getLongStream(input);
    }

    @Override
    public Tag createLongList(final LongStream input) {
        return new LongArrayTag(input.toArray());
    }

    @Override
    public Tag createList(final Stream<Tag> input) {
        return new ListTag(input.collect(Util.toMutableList()));
    }

    @Override
    public Tag remove(final Tag input, final String key) {
        if (input instanceof CompoundTag tag) {
            CompoundTag result = tag.shallowCopy();
            result.remove(key);
            return result;
        } else {
            return input;
        }
    }

    @Override
    public String toString() {
        return "NBT";
    }

    @Override
    public RecordBuilder<Tag> mapBuilder() {
        return new NbtOps.NbtRecordBuilder();
    }

    private static Optional<NbtOps.ListCollector> createCollector(final Tag tag) {
        if (tag instanceof EndTag) {
            return Optional.of(new NbtOps.GenericListCollector());
        }

        if (tag instanceof CollectionTag collection) {
            if (collection.isEmpty()) {
                return Optional.of(new NbtOps.GenericListCollector());
            }

            return switch (collection) {
                case ListTag list -> Optional.of(new NbtOps.GenericListCollector(list));
                case ByteArrayTag array -> Optional.of(new NbtOps.ByteListCollector(array.getAsByteArray()));
                case IntArrayTag array -> Optional.of(new NbtOps.IntListCollector(array.getAsIntArray()));
                case LongArrayTag array -> Optional.of(new NbtOps.LongListCollector(array.getAsLongArray()));
                default -> throw new MatchException(null, null);
            };
        } else {
            return Optional.empty();
        }
    }

    private static class ByteListCollector implements NbtOps.ListCollector {
        private final ByteArrayList values = new ByteArrayList();

        public ByteListCollector(final byte[] initialValues) {
            this.values.addElements(0, initialValues);
        }

        @Override
        public NbtOps.ListCollector accept(final Tag tag) {
            if (tag instanceof ByteTag byteTag) {
                this.values.add(byteTag.byteValue());
                return this;
            } else {
                return new NbtOps.GenericListCollector(this.values).accept(tag);
            }
        }

        @Override
        public Tag result() {
            return new ByteArrayTag(this.values.toByteArray());
        }
    }

    private static class GenericListCollector implements NbtOps.ListCollector {
        private final ListTag result = new ListTag();

        private GenericListCollector() {
        }

        private GenericListCollector(final ListTag initial) {
            this.result.addAll(initial);
        }

        public GenericListCollector(final IntArrayList initials) {
            initials.forEach(v -> this.result.add(IntTag.valueOf(v)));
        }

        public GenericListCollector(final ByteArrayList initials) {
            initials.forEach(v -> this.result.add(ByteTag.valueOf(v)));
        }

        public GenericListCollector(final LongArrayList initials) {
            initials.forEach(v -> this.result.add(LongTag.valueOf(v)));
        }

        @Override
        public NbtOps.ListCollector accept(final Tag tag) {
            this.result.add(tag);
            return this;
        }

        @Override
        public Tag result() {
            return this.result;
        }
    }

    private static class IntListCollector implements NbtOps.ListCollector {
        private final IntArrayList values = new IntArrayList();

        public IntListCollector(final int[] initialValues) {
            this.values.addElements(0, initialValues);
        }

        @Override
        public NbtOps.ListCollector accept(final Tag tag) {
            if (tag instanceof IntTag intTag) {
                this.values.add(intTag.intValue());
                return this;
            } else {
                return new NbtOps.GenericListCollector(this.values).accept(tag);
            }
        }

        @Override
        public Tag result() {
            return new IntArrayTag(this.values.toIntArray());
        }
    }

    private interface ListCollector {
        NbtOps.ListCollector accept(Tag t);

        default NbtOps.ListCollector acceptAll(final Iterable<Tag> tags) {
            NbtOps.ListCollector collector = this;

            for (Tag tag : tags) {
                collector = collector.accept(tag);
            }

            return collector;
        }

        Tag result();
    }

    private static class LongListCollector implements NbtOps.ListCollector {
        private final LongArrayList values = new LongArrayList();

        public LongListCollector(final long[] initialValues) {
            this.values.addElements(0, initialValues);
        }

        @Override
        public NbtOps.ListCollector accept(final Tag tag) {
            if (tag instanceof LongTag longTag) {
                this.values.add(longTag.longValue());
                return this;
            } else {
                return new NbtOps.GenericListCollector(this.values).accept(tag);
            }
        }

        @Override
        public Tag result() {
            return new LongArrayTag(this.values.toLongArray());
        }
    }

    private class NbtRecordBuilder extends AbstractStringBuilder<Tag, CompoundTag> {
        protected NbtRecordBuilder() {
            super(NbtOps.this);
        }

        @Override
        protected CompoundTag initBuilder() {
            return new CompoundTag();
        }

        @Override
        protected CompoundTag append(final String key, final Tag value, final CompoundTag builder) {
            builder.put(key, value);
            return builder;
        }

        @Override
        protected DataResult<Tag> build(final CompoundTag builder, final Tag prefix) {
            if (prefix == null || prefix == EndTag.INSTANCE) {
                return DataResult.success(builder);
            } else if (!(prefix instanceof CompoundTag compound)) {
                return DataResult.error(() -> "mergeToMap called with not a map: " + prefix, prefix);
            } else {
                CompoundTag result = compound.shallowCopy();

                for (Entry<String, Tag> entry : builder.entrySet()) {
                    result.put(entry.getKey(), entry.getValue());
                }

                return DataResult.success(result);
            }
        }
    }
}
