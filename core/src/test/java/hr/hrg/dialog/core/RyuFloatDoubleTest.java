package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

import hr.hrg.dialog.ryu.RyuDouble;
import hr.hrg.dialog.ryu.RyuFloat;

/**
 * Verifies the Ryu port ({@link RyuFloat}, {@link RyuDouble}) against the JDK
 * reference formatting:
 * <ul>
 *   <li>the output must be byte-for-byte identical to {@link Float#toString}
 *       / {@link Double#toString} (the canonical ryu-java implementation
 *       follows Java's formatting semantics exactly)</li>
 *   <li>the output must round-trip back to the original value</li>
 * </ul>
 */
class RyuFloatDoubleTest {

    private static String fmtFloat(float f) {
        byte[] buf = new byte[16];
        int len = RyuFloat.writeFloat(f, buf, 0);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private static String fmtDouble(double d) {
        byte[] buf = new byte[25];
        int len = RyuDouble.writeDouble(d, buf, 0);
        return new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    // ==========================================================================
    //  Exact match with Java's toString
    // ==========================================================================

    @Test
    void floatMatchesJavaToString_boundaryAndFormattingCases() {
        float[] values = {
            0.0f, -0.0f,
            1.0f, -1.0f, 0.5f, 123.456f, -9876.54f,
            // decimal/scientific threshold at 1E-3 and 1E7
            1e-3f, 1e-2f, 1e-1f, 1e6f, 1e7f, 1e8f,
            1e20f, 1e-5f, 1e-10f,
            Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_NORMAL,
            0.1f, 0.2f, 1.0f / 3.0f, 3.1415927f, 2.7182818f,
            1.4E-45f, 1.17549435E-38f, 3.4028235E38f,
        };
        for (float f : values) {
            assertEquals(Float.toString(f), fmtFloat(f), "float toString mismatch for " + Float.toString(f));
        }
    }

    @Test
    void floatMatchesJavaToString_randomBits() {
        Random rnd = new Random(0x1234);
        for (int i = 0; i < 300_000; i++) {
            float f = Float.intBitsToFloat(rnd.nextInt());
            if (!Float.isFinite(f)) {
                continue;
            }
            assertEquals(Float.toString(f), fmtFloat(f),
                    "float toString mismatch for bits " + Integer.toHexString(Float.floatToRawIntBits(f)));
        }
    }

    @Test
    void doubleMatchesJavaToString_boundaryAndFormattingCases() {
        double[] values = {
            0.0, -0.0,
            1.0, -1.0, 0.5, 123.456, -9876.54, 3.141592653589793,
            // decimal/scientific threshold at 1E-3 and 1E7
            1e-3, 1e-2, 1e-1, 1e6, 1e7, 1e8,
            1.23456789e100, -9.87654321e-200, 1e-10, 1e20,
            Double.MAX_VALUE, Double.MIN_VALUE, Double.MIN_NORMAL,
            0.1, 0.2, 1.0 / 3.0, 2.718281828459045, 6.02214076e23,
            4.9E-324, 2.2250738585072014E-308, 1.7976931348623157E308,
        };
        for (double d : values) {
            assertEquals(Double.toString(d), fmtDouble(d), "double toString mismatch for " + Double.toString(d));
        }
    }

    @Test
    void doubleMatchesJavaToString_randomBits() {
        Random rnd = new Random(0x5678);
        for (int i = 0; i < 300_000; i++) {
            double d = Double.longBitsToDouble(rnd.nextLong());
            if (!Double.isFinite(d)) {
                continue;
            }
            assertEquals(Double.toString(d), fmtDouble(d),
                    "double toString mismatch for bits " + Long.toHexString(Double.doubleToRawLongBits(d)));
        }
    }

    // ==========================================================================
    //  Round-trip
    // ==========================================================================

    @Test
    void floatRoundTrip_randomBits() {
        Random rnd = new Random(0xABCD);
        for (int i = 0; i < 300_000; i++) {
            float f = Float.intBitsToFloat(rnd.nextInt());
            if (!Float.isFinite(f)) {
                continue;
            }
            float back = Float.parseFloat(fmtFloat(f));
            assertEquals(f, back, "float round trip failed for " + f);
        }
    }

    @Test
    void doubleRoundTrip_randomBits() {
        Random rnd = new Random(0xEF01);
        for (int i = 0; i < 300_000; i++) {
            double d = Double.longBitsToDouble(rnd.nextLong());
            if (!Double.isFinite(d)) {
                continue;
            }
            double back = Double.parseDouble(fmtDouble(d));
            assertEquals(d, back, "double round trip failed for " + d);
        }
    }

    // ==========================================================================
    //  Non-finite handling of the standalone Ryu API
    // ==========================================================================

    @Test
    void nonFiniteValues() {
        assertEquals("NaN", fmtFloat(Float.NaN));
        assertEquals("Infinity", fmtFloat(Float.POSITIVE_INFINITY));
        assertEquals("-Infinity", fmtFloat(Float.NEGATIVE_INFINITY));
        assertEquals("NaN", fmtDouble(Double.NaN));
        assertEquals("Infinity", fmtDouble(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", fmtDouble(Double.NEGATIVE_INFINITY));
    }

    @Test
    void zeroValues() {
        assertEquals("0.0", fmtFloat(0.0f));
        assertEquals("-0.0", fmtFloat(-0.0f));
        assertEquals("0.0", fmtDouble(0.0));
        assertEquals("-0.0", fmtDouble(-0.0));
    }

    @Test
    void bufferBoundCheck() {
        // Longest float/double representations must fit in the documented buffer sizes.
        byte[] fBuf = new byte[16];
        byte[] dBuf = new byte[25];
        for (int i = 0; i < 100_000; i++) {
            // any write must not throw and must stay within bounds
            int flen = RyuFloat.writeFloat(Float.intBitsToFloat(i * 7919), fBuf, 0);
            assertTrue(flen <= 16, "float length " + flen);
            int dlen = RyuDouble.writeDouble(Double.longBitsToDouble(i * 7919L), dBuf, 0);
            assertTrue(dlen <= 25, "double length " + dlen);
        }
    }

    /**
     * The Ryu writers must not allocate on the formatting path (the whole
     * point of replacing {@link Float#toString}/{@link Double#toString}).
     */
    @Test
    void writeFloatAndDoubleDoNotAllocate() {
        com.sun.management.ThreadMXBean tmb =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        byte[] fBuf = new byte[16];
        byte[] dBuf = new byte[25];

        // Warm up so the JIT compiles both writers before measuring
        long acc = 0;
        for (int i = 0; i < 50000; i++) {
            acc += RyuFloat.writeFloat((float) i, fBuf, 0);
            acc += RyuDouble.writeDouble((double) i / 7, dBuf, 0);
        }

        long before = tmb.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 200000; i++) {
            acc += RyuFloat.writeFloat((float) i, fBuf, 0);
            acc += RyuDouble.writeDouble((double) i / 7, dBuf, 0);
        }
        long after = tmb.getCurrentThreadAllocatedBytes();

        long perCall = (after - before) / 400000;
        assertTrue(acc != 0, "sanity: accumulator must be non-zero");
        assertEquals(0, perCall,
                "Ryu writers must not allocate per call; measured " + perCall + " bytes/call");
    }
}
