package net.minecraft.world.level.chunk;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import org.apache.commons.lang3.Validate;
import org.jspecify.annotations.Nullable;

public class SingleValuePalette<T> implements Palette<T>, ca.spottedleaf.moonrise.patches.fast_palette.FastPalette<T> { // Paper - optimise palette reads
    private @Nullable T value;

    // Paper start - optimise palette reads
    private T[] rawPalette;

    @Override
    public final T[] moonrise$getRawPalette(final ca.spottedleaf.moonrise.patches.fast_palette.FastPaletteData<T> container) {
        if (this.rawPalette != null) {
            return this.rawPalette;
        }
        return this.rawPalette = (T[])new Object[] { this.value };
    }
    // Paper end - optimise palette reads

    public SingleValuePalette(final List<T> paletteEntries) {
        if (!paletteEntries.isEmpty()) {
            Validate.isTrue(paletteEntries.size() <= 1, "Can't initialize SingleValuePalette with %d values.", paletteEntries.size());
            this.value = paletteEntries.getFirst();
        }
    }

    public static <A> Palette<A> create(final int bits, final List<A> paletteEntries) {
        return new SingleValuePalette<>(paletteEntries);
    }

    @Override
    public int idFor(final T value, final PaletteResize<T> resizeHandler) {
        if (this.value != null && this.value != value) {
            return resizeHandler.onResize(1, value);
        }

        this.value = value;
        // Paper start - optimise palette reads
        if (this.rawPalette != null) {
            this.rawPalette[0] = value;
        }
        // Paper end - optimise palette reads
        return 0;
    }

    @Override
    public boolean maybeHas(final Predicate<T> predicate) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return predicate.test(this.value);
        }
    }

    @Override
    public T valueFor(final int index) {
        if (this.value != null && index == 0) {
            return this.value;
        } else {
            throw new IllegalStateException("Missing Palette entry for id " + index + ".");
        }
    }

    @Override
    public void read(final FriendlyByteBuf buffer, final IdMap<T> globalMap) {
        this.value = globalMap.byIdOrThrow(buffer.readVarInt());
        // Paper start - optimise palette reads
        if (this.rawPalette != null) {
            this.rawPalette[0] = this.value;
        }
        // Paper end - optimise palette reads
    }

    @Override
    public void write(final FriendlyByteBuf buffer, final IdMap<T> globalMap) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        }

        buffer.writeVarInt(globalMap.getId(this.value));
    }

    @Override
    public int getSerializedSize(final IdMap<T> globalMap) {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return VarInt.getByteSize(globalMap.getId(this.value));
        }
    }

    @Override
    public int getSize() {
        return 1;
    }

    @Override
    public Palette<T> copy() {
        if (this.value == null) {
            throw new IllegalStateException("Use of an uninitialized palette");
        } else {
            return this;
        }
    }
}
