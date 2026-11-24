package io.papermc.paper.swift.entity;

import io.papermc.paper.swift.SwiftVerify;
import it.unimi.dsi.fastutil.longs.LongArrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.Entity;

/**
 * Swift - brain sensors sort nearby entities by distance on plain numbers instead of a comparator.
 * <p>
 * {@code NearestLivingEntitySensor} (villagers, piglins, axolotls, frogs ... every 20 ticks) and
 * {@code NearestItemSensor} sort everything within follow range with
 * {@code list.sort(Comparator.comparingDouble(body::distanceToSqr))}: TimSort computes two distances on every
 * comparison, through a lambda and a boxed comparator. In a crowd of villagers that sort was about 9% of all
 * entity work.
 * <p>
 * Here each distance is computed once, and the list is ordered by the pair (distance, position in the list),
 * sorting two primitive {@code long} arrays. A squared distance is never negative or NaN, and for those values
 * the raw bits of a double order exactly like {@code Double.compare} (+0.0 included; a sum of squares is never
 * -0.0). Ordering ties by list position is what the stable TimSort does, so the order is identical. Computing
 * each distance once also keeps the sort valid while other threads move entities (parallel ticking).
 * Disable with {@code -Dswift.sensorSort=false}; {@code -Dswift.verify=true} also sorts the vanilla way and
 * reports any difference.
 */
public final class SwiftSort {
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.sensorSort", "true"));

    /**
     * FastPaper - sort one packed array instead of two parallel ones. Each element holds the distance bits with
     * the low {@link #INDEX_BITS} bits replaced by its list position, so a plain {@code long[]} sort orders by
     * (truncated distance, position). Elements whose truncated distances are equal are then put in exact
     * (distance, position) order by an insertion pass over each such run; the full distances are still in
     * {@code keys}. The result is the same order as the pair sort. Disable with {@code -Dswift.sensorSortPacked=false}.
     */
    public static boolean PACKED = Boolean.parseBoolean(System.getProperty("swift.sensorSortPacked", "true")); // not final: tests compare both paths
    static final int INDEX_BITS = 12;
    static final long INDEX_MASK = (1L << INDEX_BITS) - 1;
    /** Lists longer than this have positions that do not fit in the index bits and use the pair sort. */
    public static final int PACKED_MAX = 1 << INDEX_BITS;

    private SwiftSort() {
    }

    /**
     * Returns the positions of {@code keys} (non-negative raw double bits) in ascending (key, position) order,
     * each in the low {@link #INDEX_BITS} bits of an element. {@code keys.length} must be at most {@link #PACKED_MAX}.
     */
    static long[] packedOrder(final long[] keys) {
        final int size = keys.length;
        final long[] packed = new long[size];
        for (int i = 0; i < size; i++) {
            packed[i] = (keys[i] & ~INDEX_MASK) | i;
        }
        java.util.Arrays.sort(packed);
        int runStart = 0;
        for (int i = 1; i <= size; i++) {
            if (i == size || (packed[i] & ~INDEX_MASK) != (packed[runStart] & ~INDEX_MASK)) {
                if (i - runStart > 1) {
                    fixRun(packed, keys, runStart, i);
                }
                runStart = i;
            }
        }
        return packed;
    }

    /** Insertion sort of {@code packed[from, to)} by (full key, position); the run is already in position order. */
    private static void fixRun(final long[] packed, final long[] keys, final int from, final int to) {
        for (int i = from + 1; i < to; i++) {
            final long value = packed[i];
            final long key = keys[(int) (value & INDEX_MASK)];
            int j = i - 1;
            // positions within the run ascend, so an equal key never moves: ties keep position order
            while (j >= from && keys[(int) (packed[j] & INDEX_MASK)] > key) {
                packed[j + 1] = packed[j];
                j--;
            }
            packed[j + 1] = value;
        }
    }

    /** {@code list.sort(Comparator.comparingDouble(body::distanceToSqr))}, same order. */
    public static <E extends Entity> void byDistance(final List<E> list, final Entity body) {
        final int size = list.size();
        if (size < 2) {
            return;
        }
        final List<E> expected;
        if (SwiftVerify.ENABLED && !io.papermc.paper.swift.async.SwiftAsync.parallel) {
            expected = new ArrayList<>(list);
            expected.sort(Comparator.comparingDouble(body::distanceToSqr));
        } else {
            expected = null;
        }
        final long[] keys = new long[size];
        final Object[] copy = list.toArray();
        for (int i = 0; i < size; i++) {
            keys[i] = Double.doubleToRawLongBits(body.distanceToSqr((Entity) copy[i]));
        }
        if (PACKED && size <= PACKED_MAX) {
            final long[] packed = packedOrder(keys);
            for (int i = 0; i < size; i++) {
                @SuppressWarnings("unchecked") final E e = (E) copy[(int) (packed[i] & INDEX_MASK)];
                list.set(i, e);
            }
        } else {
            final long[] index = new long[size];
            for (int i = 0; i < size; i++) {
                index[i] = i;
            }
            LongArrays.quickSort(keys, index);
            for (int i = 0; i < size; i++) {
                @SuppressWarnings("unchecked") final E e = (E) copy[(int) index[i]];
                list.set(i, e);
            }
        }
        if (expected != null) {
            SwiftVerify.checked();
            if (!expected.equals(list)) {
                SwiftVerify.mismatch("sensor sort", "sorting " + size + " entities around " + body + " gives a different order than the comparator sort");
            }
        }
    }
}
