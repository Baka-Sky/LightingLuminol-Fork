package net.minecraft.util;

import it.unimi.dsi.fastutil.objects.ObjectArrays;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.jspecify.annotations.Nullable;

public class SortedArraySet<T> extends AbstractSet<T> implements ca.spottedleaf.moonrise.patches.chunk_system.util.ChunkSystemSortedArraySet<T> { // Paper - rewrite chunk system
    private static final int DEFAULT_INITIAL_CAPACITY = 10;
    private final Comparator<T> comparator;
    private T[] contents;
    private int size;

    // Paper start - rewrite chunk system
    @Override
    public final boolean removeIf(final java.util.function.Predicate<? super T> filter) {
        // prev. impl used an iterator, which could be n^2 and creates garbage
        int i = 0;
        final int len = this.size;
        final T[] backingArray = this.contents;

        for (;;) {
            if (i >= len) {
                return false;
            }
            if (!filter.test(backingArray[i++])) {
                continue;
            }
            break;
        }

        // we only want to write back to backingArray if we really need to

        int lastIndex = i - 1; // this is where new elements are shifted to

        for (; i < len; ++i) {
            final T curr = backingArray[i];
            if (!filter.test(curr)) { // if test throws we're screwed
                backingArray[lastIndex++] = curr;
            }
        }

        // cleanup end
        Arrays.fill(backingArray, lastIndex, len, null);
        this.size = lastIndex;
        return true;
    }

    @Override
    public final T moonrise$replace(final T object) {
        final int index = this.findIndex(object);
        if (index >= 0) {
            final T old = this.contents[index];
            this.contents[index] = object;
            return old;
        } else {
            this.addInternal(object, getInsertionPosition(index));
            return object;
        }
    }

    @Override
    public final T moonrise$removeAndGet(final T object) {
        int i = this.findIndex(object);
        if (i >= 0) {
            final T ret = this.contents[i];
            this.removeInternal(i);
            return ret;
        } else {
            return null;
        }
    }

    @Override
    public final SortedArraySet<T> moonrise$copy() {
        final SortedArraySet<T> ret = SortedArraySet.create(this.comparator, 0);

        ret.size = this.size;
        ret.contents = Arrays.copyOf(this.contents, this.size);

        return ret;
    }

    @Override
    public Object[] moonrise$copyBackingArray() {
        return this.contents.clone();
    }
    // Paper end - rewrite chunk system

    private SortedArraySet(final int initialCapacity, final Comparator<T> comparator) {
        this.comparator = comparator;
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Initial capacity (" + initialCapacity + ") is negative");
        }

        this.contents = (T[])castRawArray(new Object[initialCapacity]);
    }

    public static <T extends Comparable<T>> SortedArraySet<T> create() {
        return create(10);
    }

    public static <T extends Comparable<T>> SortedArraySet<T> create(final int initialCapacity) {
        return new SortedArraySet<T>(initialCapacity, Comparator.naturalOrder());
    }

    public static <T> SortedArraySet<T> create(final Comparator<T> comparator) {
        return create(comparator, 10);
    }

    public static <T> SortedArraySet<T> create(final Comparator<T> comparator, final int initialCapacity) {
        return new SortedArraySet<>(initialCapacity, comparator);
    }

    private static <T> T[] castRawArray(final Object[] array) {
        return (T[])array;
    }

    private int findIndex(final T t) {
        return Arrays.binarySearch(this.contents, 0, this.size, t, this.comparator);
    }

    private static int getInsertionPosition(final int position) {
        return -position - 1;
    }

    @Override
    public boolean add(final T t) {
        int position = this.findIndex(t);
        if (position >= 0) {
            return false;
        }

        int pos = getInsertionPosition(position);
        this.addInternal(t, pos);
        return true;
    }

    private void grow(int capacity) {
        if (capacity > this.contents.length) {
            if (this.contents != ObjectArrays.DEFAULT_EMPTY_ARRAY) {
                capacity = Util.growByHalf(this.contents.length, capacity);
            } else if (capacity < 10) {
                capacity = 10;
            }

            Object[] t = new Object[capacity];
            System.arraycopy(this.contents, 0, t, 0, this.size);
            this.contents = (T[])castRawArray(t);
        }
    }

    private void addInternal(final T t, final int pos) {
        this.grow(this.size + 1);
        if (pos != this.size) {
            System.arraycopy(this.contents, pos, this.contents, pos + 1, this.size - pos);
        }

        this.contents[pos] = t;
        this.size++;
    }

    private void removeInternal(final int position) {
        this.size--;
        if (position != this.size) {
            System.arraycopy(this.contents, position + 1, this.contents, position, this.size - position);
        }

        this.contents[this.size] = null;
    }

    private T getInternal(final int position) {
        return this.contents[position];
    }

    public T addOrGet(final T t) {
        int position = this.findIndex(t);
        if (position >= 0) {
            return this.getInternal(position);
        }

        this.addInternal(t, getInsertionPosition(position));
        return t;
    }

    @Override
    public boolean remove(final Object o) {
        int position = this.findIndex((T)o);
        if (position >= 0) {
            this.removeInternal(position);
            return true;
        } else {
            return false;
        }
    }

    public @Nullable T get(final T t) {
        int position = this.findIndex(t);
        return position >= 0 ? this.getInternal(position) : null;
    }

    public T first() {
        return this.getInternal(0);
    }

    public T last() {
        return this.getInternal(this.size - 1);
    }

    @Override
    public boolean contains(final Object o) {
        int result = this.findIndex((T)o);
        return result >= 0;
    }

    @Override
    public Iterator<T> iterator() {
        return new SortedArraySet.ArrayIterator();
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public Object[] toArray() {
        return Arrays.copyOf(this.contents, this.size, Object[].class);
    }

    @Override
    public <U> U[] toArray(final U[] a) {
        if (a.length < this.size) {
            return (U[])Arrays.copyOf(this.contents, this.size, (Class<? extends T[]>)a.getClass());
        }

        System.arraycopy(this.contents, 0, a, 0, this.size);
        if (a.length > this.size) {
            a[this.size] = null;
        }

        return a;
    }

    @Override
    public void clear() {
        Arrays.fill(this.contents, 0, this.size, null);
        this.size = 0;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        } else {
            return o instanceof SortedArraySet<?> that && this.comparator.equals(that.comparator)
                ? this.size == that.size && Arrays.equals(this.contents, that.contents)
                : super.equals(o);
        }
    }

    private class ArrayIterator implements Iterator<T> {
        private int index;
        private int last = -1;

        @Override
        public boolean hasNext() {
            return this.index < SortedArraySet.this.size;
        }

        @Override
        public T next() {
            if (this.index >= SortedArraySet.this.size) {
                throw new NoSuchElementException();
            }

            this.last = this.index++;
            return SortedArraySet.this.contents[this.last];
        }

        @Override
        public void remove() {
            if (this.last == -1) {
                throw new IllegalStateException();
            }

            SortedArraySet.this.removeInternal(this.last);
            this.index--;
            this.last = -1;
        }
    }
}
