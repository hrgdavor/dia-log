package hr.hrg.dialog.core;

/**
 * Test/benchmark-only packed little-endian stores.
 *
 * <p>These are the <em>comparison</em> tail shapes exercised by {@code
 * PackedWordWriteBenchmark} and {@code WriteOpsPackedTest}: the generic
 * runtime-{@code n} store ({@link #writePackedLE(byte[], int, long, int)}) and
 * the compile-time-specialized {@code writePackedLE1..7} partial stores. The
 * production writer must <b>not</b> use them — the correct packed-long shape is
 * a full 8-byte {@link WriteOps#LE_LONG} store with a partial cursor advance
 * (see {@code doc/perf/03-packed-word-stores.md}). Keeping them out of {@link
 * WriteOps} ensures the shared facade only exposes the official stores.
 */
final class PackedWriteTestOps {

    private PackedWriteTestOps() {}

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..8); returns advanced pos. */
    public static int writePackedLE(byte[] buf, int pos, long value, int n) {
        if (n == 8) {
            WriteOps.LE_LONG.set(buf, pos, value);
            return pos + 8;
        }
        switch (n) {
            case 7: buf[pos + 6] = (byte) (value >>> 48);
            case 6: buf[pos + 5] = (byte) (value >>> 40);
            case 5: buf[pos + 4] = (byte) (value >>> 32);
            case 4: buf[pos + 3] = (byte) (value >>> 24);
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new IllegalArgumentException("n must be in 0..8: " + n);
        }
        return pos + n;
    }

    /** Stores the low byte of {@code value} little-endian at {@code pos}; returns {@code pos + 1}. */
    public static int writePackedLE1(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        return pos + 1;
    }

    /** Stores the low 2 bytes of {@code value} little-endian; returns {@code pos + 2}. */
    public static int writePackedLE2(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        return pos + 2;
    }

    /** Stores the low 3 bytes of {@code value} little-endian; returns {@code pos + 3}. */
    public static int writePackedLE3(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        return pos + 3;
    }

    /** Stores the low 4 bytes of {@code value} little-endian; returns {@code pos + 4}. */
    public static int writePackedLE4(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        return pos + 4;
    }

    /** Stores the low 5 bytes of {@code value} little-endian; returns {@code pos + 5}. */
    public static int writePackedLE5(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        buf[pos + 4] = (byte) (value >>> 32);
        return pos + 5;
    }

    /** Stores the low 6 bytes of {@code value} little-endian; returns {@code pos + 6}. */
    public static int writePackedLE6(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        buf[pos + 4] = (byte) (value >>> 32);
        buf[pos + 5] = (byte) (value >>> 40);
        return pos + 6;
    }

    /** Stores the low 7 bytes of {@code value} little-endian (skips the 8th); returns {@code pos + 7}. */
    public static int writePackedLE7(byte[] buf, int pos, long value) {
        buf[pos] = (byte) value;
        buf[pos + 1] = (byte) (value >>> 8);
        buf[pos + 2] = (byte) (value >>> 16);
        buf[pos + 3] = (byte) (value >>> 24);
        buf[pos + 4] = (byte) (value >>> 32);
        buf[pos + 5] = (byte) (value >>> 40);
        buf[pos + 6] = (byte) (value >>> 48);
        return pos + 7;
    }
}
