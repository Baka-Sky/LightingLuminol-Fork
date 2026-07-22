package net.minecraft.util;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.ListBuilder;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import com.mojang.serialization.RecordBuilder.AbstractUniversalBuilder;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public class NullOps implements DynamicOps<Unit> {
    public static final NullOps INSTANCE = new NullOps();
    private static final MapLike<Unit> EMPTY_MAP = new MapLike<Unit>() {
        @Override
        public @Nullable Unit get(final Unit key) {
            return null;
        }

        @Override
        public @Nullable Unit get(final String key) {
            return null;
        }

        @Override
        public Stream<Pair<Unit, Unit>> entries() {
            return Stream.empty();
        }
    };

    private NullOps() {
    }

    @Override
    public <U> U convertTo(final DynamicOps<U> outOps, final Unit input) {
        return outOps.empty();
    }

    @Override
    public Unit empty() {
        return Unit.INSTANCE;
    }

    @Override
    public Unit emptyMap() {
        return Unit.INSTANCE;
    }

    @Override
    public Unit emptyList() {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createNumeric(final Number value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createByte(final byte value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createShort(final short value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createInt(final int value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createLong(final long value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createFloat(final float value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createDouble(final double value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createBoolean(final boolean value) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createString(final String value) {
        return Unit.INSTANCE;
    }

    @Override
    public DataResult<Number> getNumberValue(final Unit input) {
        return DataResult.success(0);
    }

    @Override
    public DataResult<Boolean> getBooleanValue(final Unit input) {
        return DataResult.success(false);
    }

    @Override
    public DataResult<String> getStringValue(final Unit input) {
        return DataResult.success("");
    }

    @Override
    public DataResult<Unit> mergeToList(final Unit input, final Unit value) {
        return DataResult.success(Unit.INSTANCE);
    }

    @Override
    public DataResult<Unit> mergeToList(final Unit input, final List<Unit> values) {
        return DataResult.success(Unit.INSTANCE);
    }

    @Override
    public DataResult<Unit> mergeToMap(final Unit input, final Unit key, final Unit value) {
        return DataResult.success(Unit.INSTANCE);
    }

    @Override
    public DataResult<Unit> mergeToMap(final Unit input, final Map<Unit, Unit> values) {
        return DataResult.success(Unit.INSTANCE);
    }

    @Override
    public DataResult<Unit> mergeToMap(final Unit input, final MapLike<Unit> values) {
        return DataResult.success(Unit.INSTANCE);
    }

    @Override
    public DataResult<Stream<Pair<Unit, Unit>>> getMapValues(final Unit input) {
        return DataResult.success(Stream.empty());
    }

    @Override
    public DataResult<Consumer<BiConsumer<Unit, Unit>>> getMapEntries(final Unit input) {
        return DataResult.success(consumer -> {});
    }

    @Override
    public DataResult<MapLike<Unit>> getMap(final Unit input) {
        return DataResult.success(EMPTY_MAP);
    }

    @Override
    public DataResult<Stream<Unit>> getStream(final Unit input) {
        return DataResult.success(Stream.empty());
    }

    @Override
    public DataResult<Consumer<Consumer<Unit>>> getList(final Unit input) {
        return DataResult.success(consumer -> {});
    }

    @Override
    public DataResult<ByteBuffer> getByteBuffer(final Unit input) {
        return DataResult.success(ByteBuffer.wrap(new byte[0]));
    }

    @Override
    public DataResult<IntStream> getIntStream(final Unit input) {
        return DataResult.success(IntStream.empty());
    }

    @Override
    public DataResult<LongStream> getLongStream(final Unit input) {
        return DataResult.success(LongStream.empty());
    }

    @Override
    public Unit createMap(final Stream<Pair<Unit, Unit>> map) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createMap(final Map<Unit, Unit> map) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createList(final Stream<Unit> input) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createByteList(final ByteBuffer input) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createIntList(final IntStream input) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit createLongList(final LongStream input) {
        return Unit.INSTANCE;
    }

    @Override
    public Unit remove(final Unit input, final String key) {
        return input;
    }

    @Override
    public RecordBuilder<Unit> mapBuilder() {
        return new NullOps.NullMapBuilder(this);
    }

    @Override
    public ListBuilder<Unit> listBuilder() {
        return new NullOps.NullListBuilder(this);
    }

    @Override
    public String toString() {
        return "Null";
    }

    private static final class NullListBuilder extends AbstractListBuilder<Unit, Unit> {
        public NullListBuilder(final DynamicOps<Unit> ops) {
            super(ops);
        }

        @Override
        protected Unit initBuilder() {
            return Unit.INSTANCE;
        }

        @Override
        protected Unit append(final Unit builder, final Unit value) {
            return builder;
        }

        @Override
        protected DataResult<Unit> build(final Unit builder, final Unit prefix) {
            return DataResult.success(builder);
        }
    }

    private static final class NullMapBuilder extends AbstractUniversalBuilder<Unit, Unit> {
        public NullMapBuilder(final DynamicOps<Unit> ops) {
            super(ops);
        }

        @Override
        protected Unit initBuilder() {
            return Unit.INSTANCE;
        }

        @Override
        protected Unit append(final Unit key, final Unit value, final Unit builder) {
            return builder;
        }

        @Override
        protected DataResult<Unit> build(final Unit builder, final Unit prefix) {
            return DataResult.success(prefix);
        }
    }
}
