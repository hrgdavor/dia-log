package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JeaiiiFastWriterLongTest {

    private static String writeJeaiii(long value) {
        byte[] buf = new byte[32];
        int len = JeaiiiFastWriter.writeLongToBytes(buf, 0, value);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private static void check(long value) {
        assertEquals(Long.toString(value), writeJeaiii(value), "value=" + value);
    }

    // Every decimal digit-count boundary (10^0..10^18), both mirrors, plus the
    // int split boundary at 10^9 and the long extremes.
    @ParameterizedTest
    @ValueSource(longs = {
        0, 1, 2, 8, 9, 10, 11, 98, 99,
        100, 101, 998, 999,
        1000, 1001, 9998, 9999,
        10000, 10001, 99998, 99999,
        100000, 100001, 999998, 999999,
        1000000, 1000001, 9999998, 9999999,
        10000000, 10000001, 99999998, 99999999,
        100000000, 100000001, 999999998, 999999999,
        1000000000L, 1000000001L, 9999999998L, 9999999999L,
        10000000000L, 10000000001L, 99999999998L, 99999999999L,
        100000000000L, 100000000001L, 999999999998L, 999999999999L,
        1000000000000L, 1000000000001L, 9999999999998L, 9999999999999L,
        10000000000000L, 10000000000001L, 99999999999998L, 99999999999999L,
        100000000000000L, 100000000000001L, 999999999999998L, 999999999999999L,
        1000000000000000L, 1000000000000001L, 9999999999999998L, 9999999999999999L,
        10000000000000000L, 10000000000000001L, 99999999999999998L, 99999999999999999L,
        100000000000000000L, 100000000000000001L, 999999999999999998L, 999999999999999999L,
        1000000000000000000L, 1000000000000000001L,
        Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE - 2,
        Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MIN_VALUE + 2,
        -1, -2, -9, -10, -11, -99, -100, -101, -999,
        -1000, -9999, -10000, -99999, -100000, -999999,
        -1000000, -9999999, -10000000, -99999999,
        -100000000, -999999999, -1000000000L, -9999999999L,
        -10000000000L, -99999999999L, -100000000000L, -999999999999L,
        -100000000000000000L, -999999999999999999L, -1000000000000000000L
    })
    void boundaryValues(long value) {
        check(value);
    }

    // Exhaustive over the whole 1..9-digit range, where writeLongToBytes
    // delegates straight to the int path.
    @Test
    void exhaustiveSmall() {
        for (long i = 0; i <= 1_000_000; i++) {
            check(i);
        }
    }

    // Windows straddling the 10^9 split boundary exercise writePadded9 for a
    // full contiguous sweep of lo, plus the hi=9 (10-digit hi) transition.
    @Test
    void exhaustiveAroundSplitBoundary() {
        for (long i = 999_500_000L; i <= 1_000_500_000L; i++) {
            check(i);
        }
        for (long i = 9_999_500_000L; i <= 10_000_500_000L; i++) {
            check(i);
        }
    }

    // Deterministic sweep across the whole 64-bit range (19-digit values
    // included, both signs, plus Long.MIN_VALUE/MAX_VALUE).
    @Test
    void randomizedFullRange() {
        Random rnd = new Random(0x1234_5678_9ABCDEFL);
        for (int i = 0; i < 3_000_000; i++) {
            check(rnd.nextLong());
        }
    }

    // Dense neighborhood of every power of ten up to 10^18 (both mirrors), so
    // every leading-pair digit transition and the reciprocal rounding edges are
    // hit in the 10..19-digit branches.
    @Test
    void randomizedAroundPowersOfTen() {
        Random rnd = new Random(0xFEED_BEEFL);
        long p = 1;
        for (int k = 0; k <= 18; k++) {
            for (int j = 0; j < 2000; j++) {
                long delta = rnd.nextLong(2_000_000_000L) - 1_000_000_000L;
                long v = p + delta;
                if (v >= 0) {
                    check(v);
                }
                v = delta - p;
                if (v <= 0) {
                    check(v);
                }
            }
            p *= 10;
        }
    }
}
