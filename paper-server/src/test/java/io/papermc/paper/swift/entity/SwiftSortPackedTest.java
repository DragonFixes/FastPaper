package io.papermc.paper.swift.entity;

import it.unimi.dsi.fastutil.longs.LongArrays;
import java.util.Random;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FastPaper - the packed single-array sort orders positions exactly like the pair sort on (distance bits,
 * position): random distances, many exact ties, distances that differ only in the bits the packing truncates,
 * and the largest list the packed path accepts.
 */
@Normal
class SwiftSortPackedTest {

    private static long[] pairOrder(final long[] keysIn) {
        final long[] keys = keysIn.clone();
        final long[] index = new long[keys.length];
        for (int i = 0; i < index.length; i++) {
            index[i] = i;
        }
        LongArrays.quickSort(keys, index);
        return index;
    }

    private static void assertSameOrder(final long[] keys, final String what) {
        final long[] packed = SwiftSort.packedOrder(keys);
        final long[] pair = pairOrder(keys);
        for (int i = 0; i < keys.length; i++) {
            assertEquals(pair[i], packed[i] & SwiftSort.INDEX_MASK, what + ", size " + keys.length + ", position " + i);
        }
    }

    @Test
    void randomDistances() {
        final Random random = new Random(0x5013L);
        for (int t = 0; t < 2000; t++) {
            final long[] keys = new long[1 + random.nextInt(300)];
            for (int i = 0; i < keys.length; i++) {
                final double dx = random.nextDouble() * 32 - 16;
                final double dz = random.nextDouble() * 32 - 16;
                keys[i] = Double.doubleToRawLongBits(dx * dx + dz * dz);
            }
            assertSameOrder(keys, "random");
        }
    }

    @Test
    void exactTies() {
        final Random random = new Random(7);
        for (int t = 0; t < 2000; t++) {
            final long[] keys = new long[1 + random.nextInt(300)];
            for (int i = 0; i < keys.length; i++) {
                // half-block grid around the body, as in trading-hall cells: many equal distances, and 0
                final double dx = (random.nextInt(9) - 4) * 0.5;
                final double dz = (random.nextInt(9) - 4) * 0.5;
                keys[i] = Double.doubleToRawLongBits(dx * dx + dz * dz);
            }
            assertSameOrder(keys, "ties");
        }
    }

    @Test
    void differencesOnlyInTruncatedBits() {
        final Random random = new Random(11);
        final long base = Double.doubleToRawLongBits(100.0);
        for (int t = 0; t < 2000; t++) {
            final long[] keys = new long[1 + random.nextInt(300)];
            for (int i = 0; i < keys.length; i++) {
                // below, inside and just above the truncated low bits
                keys[i] = base + random.nextInt(1 << (SwiftSort.INDEX_BITS + 1));
            }
            assertSameOrder(keys, "low bits");
        }
    }

    @Test
    void largestPackedList() {
        final Random random = new Random(13);
        final long[] keys = new long[SwiftSort.PACKED_MAX];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = Double.doubleToRawLongBits(random.nextInt(64) * 0.25);
        }
        assertSameOrder(keys, "largest");
    }
}
