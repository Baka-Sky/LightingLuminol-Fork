package net.minecraft.world.level.chunk;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.IdMap;
import net.minecraft.network.FriendlyByteBuf;

public interface Palette<T> extends ca.spottedleaf.moonrise.patches.fast_palette.FastPalette<T> { // Paper - optimise palette reads
    int idFor(T value, PaletteResize<T> resizeHandler);

    boolean maybeHas(Predicate<T> predicate);

    T valueFor(int index);

    void read(FriendlyByteBuf buffer, IdMap<T> globalMap);

    void write(FriendlyByteBuf buffer, IdMap<T> globalMap);

    int getSerializedSize(IdMap<T> globalMap);

    int getSize();

    Palette<T> copy();

    interface Factory {
        <A> Palette<A> create(int bits, List<A> paletteEntries);
    }
}
