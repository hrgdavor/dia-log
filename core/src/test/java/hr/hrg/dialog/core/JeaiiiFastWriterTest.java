package hr.hrg.dialog.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JeaiiiFastWriterTest {

    private static String writeJeaiii(int value) {
        byte[] buf = new byte[16];
        int len = JeaiiiFastWriter.writeIntToBytes(buf, 0, value);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private static void check(int value) {
        String expected = String.valueOf(value);
        String actual = writeJeaiii(value);
        assertEquals(expected, actual, "value=" + value);
    }

    // Boundaries where the digit-count grouping changes, plus off-by-one
    // neighbors. These are where the reciprocal-multiplier / write2 logic is
    // most likely to be wrong (leading group vs. pair-group confusion).
    @ParameterizedTest
    @ValueSource(ints = {
        // 1-digit / 2-digit boundary
        0, 1, 2, 8, 9,
        // 2-digit / 3-digit boundary
        10, 11, 98, 99,
        // 3-digit / 4-digit boundary
        100, 101, 998, 999,
        // 4-digit / 5-digit boundary
        1000, 1001, 9998, 9999,
        // 5-digit / 6-digit boundary
        10000, 10001, 99998, 99999,
        // 6-digit / 7-digit boundary
        100000, 100001, 999998, 999999,
        // 7-digit / 8-digit boundary
        1000000, 1000001, 9999998, 9999999,
        // 8-digit / 9-digit boundary
        10000000, 10000001, 99999998, 99999999,
        // 9-digit / 10-digit boundary
        100000000, 100000001, 999999998, 999999999,
        // 10-digit top
        1000000000, 1073741824, Integer.MAX_VALUE,
        // negative mirrors
        -1, -2, -9, -10, -11, -99, -100, -101, -999,
        -1000, -9999, -10000, -99999, -100000, -999999,
        -1000000, -9999999, -10000000, -99999999,
        -100000000, -999999999, -1000000000, Integer.MIN_VALUE
    })
    void boundaryValues(int value) {
        check(value);
    }

    // Exhaustive sweep of the small-number paths where the reciprocal /
    // group logic differs. 0..100000 covers every digit-count up to 6 and all
    // the off-by-one reciprocals in the <1000000 branch.
    @Test
    void exhaustiveSmallRange() {
        for (int i = 0; i <= 100000; i++) {
            check(i);
        }
    }

    // Sweep the negatives in the same range (sign handling + the same paths).
    @Test
    void exhaustiveSmallRangeNegative() {
        for (int i = -100000; i < 0; i++) {
            check(i);
        }
    }

    @Nested
    @DisplayName("spot-checks beyond the exhaustive range")
    class SpotChecks {
        // Sample one value per decade above 100000 to catch any reciprocal
        // error in the 7..10 digit branches without iterating 2 billion ints.
        @ParameterizedTest
        @ValueSource(ints = {
            1001234, 5123456, 10123456, 99123456,
            101234567, 551234567, 1012345678, 2147483646,
            -1001234, -5123456, -10123456, -99123456,
            -101234567, -551234567, -1012345678, -2147483647
        })
        void sampledLargeValues(int value) {
            check(value);
        }
    }

    // Exhaustive sweep to 1,000,000 covers every value up to 7 digits, which
    // exercises the leading-pair + pair loop for all digit counts 3..7.
    @Test
    void exhaustiveToMillion() {
        for (int i = 0; i <= 1_000_000; i++) {
            check(i);
        }
    }

    @Test
    void exhaustiveToMillionNegative() {
        for (int i = -1_000_000; i < 0; i++) {
            check(i);
        }
    }

    // Deterministic pseudo-random sweep across the entire int range so the
    // 7..10 digit branches (and both extreme endpoints) get broad coverage.
    @Test
    void randomizedFullRange() {
        java.util.Random rnd = new java.util.Random(0x5EED_CAFEL);
        for (int i = 0; i < 2_000_000; i++) {
            check(rnd.nextInt());
        }
    }

    // Neighborhood of every decimal digit-count boundary (and both mirrors),
    // plus the int extremes and the 2^30 boundary where the leading pair of a
    // 10-digit number wraps around.
    @Test
    void decadeBoundaryWindows() {
        for (long p = 1; p <= 1_000_000_000L; p *= 10) {
            for (long d = -3; d <= 3; d++) {
                checkWithinIntRange(p + d);
                checkWithinIntRange(-(p + d));
                checkWithinIntRange(2 * p + d);
                checkWithinIntRange(-(2 * p + d));
            }
        }
        for (int v : new int[] {
            Integer.MAX_VALUE, Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 2,
            Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 2,
            1_073_741_824, 1_073_741_823, -1_073_741_824, -1_073_741_823
        }) {
            check(v);
        }
    }

    private static void checkWithinIntRange(long v) {
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            check((int) v);
        }
    }
}
