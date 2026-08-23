package hr.hrg.dialog.core.perf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness guard for the benchmark-only pair variant, so the benchmarks
 * compare a verified implementation. Mirrors the coverage of
 * {@code hr.hrg.dialog.core.JeaiiiFastWriterTest} / {@code JeaiiiFastWriterLongTest}
 * at a lighter sampling density.
 */
class JeaiiiPairsWriterTest {

    private static String writeInt(int value) {
        byte[] buf = new byte[16];
        int len = JeaiiiPairsWriter.writeIntToBytes(buf, 0, value);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private static String writeLong(long value) {
        byte[] buf = new byte[32];
        int len = JeaiiiPairsWriter.writeLongToBytes(buf, 0, value);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(ints = {
        0, 1, 9, 10, 99, 100, 999, 1000, 9999, 10000, 99999, 100000, 999999,
        1000000, 9999999, 10000000, 99999999, 100000000, 999999999,
        1000000000, Integer.MAX_VALUE, Integer.MIN_VALUE,
        -1, -9, -10, -99, -100, -999, -1000, -9999, -100000, -999999,
        -1000000, -9999999, -10000000, -99999999, -100000000, -999999999
    })
    void intBoundaries(int value) {
        assertEquals(Integer.toString(value), writeInt(value), "value=" + value);
    }

    @ParameterizedTest
    @ValueSource(longs = {
        0, 1, 9, 10, 99, 100, 999, 1000, 9999, 10000, 99999, 100000, 999999,
        1000000, 9999999, 10000000, 99999999, 100000000, 999999999,
        1000000000L, 9999999999L, 10000000000L, 99999999999L, 100000000000L,
        999999999999L, 1000000000000L, 9999999999999L, 10000000000000L,
        99999999999999L, 100000000000000L, 999999999999999L, 1000000000000000L,
        9999999999999999L, 10000000000000000L, 99999999999999999L,
        100000000000000000L, 999999999999999999L, 1000000000000000000L,
        Long.MAX_VALUE, Long.MIN_VALUE,
        -1, -9, -10, -99, -100, -999, -1000, -9999, -100000, -999999,
        -1000000, -9999999, -10000000, -99999999, -100000000, -999999999,
        -1000000000L, -9999999999L, -10000000000L, -999999999999999999L
    })
    void longBoundaries(long value) {
        assertEquals(Long.toString(value), writeLong(value), "value=" + value);
    }

    @Test
    void intExhaustiveSmall() {
        for (int i = 0; i <= 100_000; i++) {
            assertEquals(Integer.toString(i), writeInt(i), "value=" + i);
        }
    }

    @Test
    void longExhaustiveSmall() {
        for (long i = 0; i <= 100_000; i++) {
            assertEquals(Long.toString(i), writeLong(i), "value=" + i);
        }
    }

    @Test
    void randomInt() {
        Random rnd = new Random(0xABCDL);
        for (int i = 0; i < 500_000; i++) {
            int v = rnd.nextInt();
            assertEquals(Integer.toString(v), writeInt(v), "value=" + v);
        }
    }

    @Test
    void randomLong() {
        Random rnd = new Random(0xDCBAL);
        for (int i = 0; i < 500_000; i++) {
            long v = rnd.nextLong();
            assertEquals(Long.toString(v), writeLong(v), "value=" + v);
        }
    }
}
