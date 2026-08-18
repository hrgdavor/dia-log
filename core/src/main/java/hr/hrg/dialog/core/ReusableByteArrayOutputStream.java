package hr.hrg.dialog.core;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Reusable, grow-only in-memory {@link OutputStream} for buffering one log event
 * before a single bulk flush to the real stream.
 * <p>
 * The backing {@code byte[]} is allocated once (default 1&nbsp;MiB, matching the
 * largest reasonable event) and <b>never shrinks</b>: {@link #reset()} reuses it
 * for the next event, and it only grows when an event exceeds the current capacity
 * (doubling, converging to the longest event ever written). Steady-state events
 * allocate nothing — no per-event buffer, no growth.
 * <p>
 * Intended use (see {@code JsonAppender.writeOut}): reset, let the writer fill the
 * buffer, then {@link #writeTo(OutputStream)} the whole event to the real stream in
 * one bulk write, and reset again. This avoids hundreds of tiny writes per event to
 * file/network streams.
 * <p>
 * <b>Thread safety:</b> not thread-safe. In the appenders the buffer is protected by
 * logback's per-appender guard (logback 1.5.x {@code AppenderBase.doAppend} is
 * {@code synchronized}), so one instance per appender is safe.
 *
 * @see StringByteExtractor
 * @see JsonNumberWriter
 */
@NotThreadSafe
public final class ReusableByteArrayOutputStream extends OutputStream {

    /** Default capacity: 1 MiB — large enough for any realistic event, small enough to be cheap. */
    public static final int DEFAULT_CAPACITY = 1 << 20;

    private byte[] buf;
    private int count;

    /** Creates a buffer with {@link #DEFAULT_CAPACITY}. */
    public ReusableByteArrayOutputStream() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a buffer with the given initial capacity (grows by doubling if an
     * event ever exceeds it).
     */
    public ReusableByteArrayOutputStream(int initialCapacity) {
        if (initialCapacity <= 0) {
            throw new IllegalArgumentException("initialCapacity must be positive: " + initialCapacity);
        }
        buf = new byte[initialCapacity];
    }

    @Override
    public void write(int b) {
        if (count == buf.length) {
            grow(count + 1);
        }
        buf[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        if (len == 0) {
            return;
        }
        int need = count + len;
        if (need > buf.length) {
            grow(need);
        }
        System.arraycopy(b, off, buf, count, len);
        count = need;
    }

    /** Reuses the current buffer for the next event (no reallocation, no shrink). */
    public void reset() {
        count = 0;
    }

    /** Number of bytes currently buffered. */
    public int size() {
        return count;
    }

    /**
     * The backing array (may be larger than {@link #size()}). Exposed for a
     * zero-copy bulk flush — do not mutate; only bytes {@code [0, size())} are valid.
     */
    public byte[] buffer() {
        return buf;
    }

    /** Bulk-flushes the buffered bytes to {@code out} with a single write. */
    public void writeTo(OutputStream out) throws IOException {
        out.write(buf, 0, count);
    }

    // =========================================================================
    // Direct-buffer API ("writer owns the buffer" fast path).
    //
    // Ported from Apache Fory's Utf8JsonWriter design (commit 585eb16f,
    // "feat(java): optimize json perf", PR #3871): the writer reads the backing
    // array + cursor, performs local capacity checks (T3), and publishes the
    // cursor once with setPosition. Every helper below uses an inlined
    // `position + n > buffer.length` check so the common no-grow case is a
    // single compare + branch with no virtual call, and only the cold path
    // computes the absolute capacity.
    // =========================================================================

    /** Current byte count (direct-cursor read). */
    public int position() {
        return count;
    }

    /**
     * Direct-cursor write: {@code pos} must be in {@code [0, buffer().length]}.
     * Callers that stored bytes into {@link #buffer()} publish them here.
     */
    public void setPosition(int pos) {
        if (pos < 0 || pos > buf.length) {
            throw new IndexOutOfBoundsException("position " + pos + " of " + buf.length);
        }
        count = pos;
    }

    /**
     * Appends the low {@code n} bytes of {@code value} little-endian
     * ({@code n} in 0..8) with a single inlined capacity check. Packs up to
     * eight bytes (e.g. a precomputed field-name prefix) into one store group.
     */
    public void writeLongPrefixLE(long value, int n) {
        if (n < 0 || n > 8) {
            throw new IllegalArgumentException("n must be in 0..8: " + n);
        }
        int pos = count;
        if (pos + n > buf.length) {
            grow(pos + n);
        }
        switch (n) {
            case 8: buf[pos + 7] = (byte) (value >>> 56);
            case 7: buf[pos + 6] = (byte) (value >>> 48);
            case 6: buf[pos + 5] = (byte) (value >>> 40);
            case 5: buf[pos + 4] = (byte) (value >>> 32);
            case 4: buf[pos + 3] = (byte) (value >>> 24);
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new AssertionError("unreachable");
        }
        count = pos + n;
    }

    /**
     * Appends the low {@code n} bytes of {@code value} little-endian
     * ({@code n} in 0..4) with a single inlined capacity check.
     */
    public void writeIntPrefixLE(int value, int n) {
        if (n < 0 || n > 4) {
            throw new IllegalArgumentException("n must be in 0..4: " + n);
        }
        int pos = count;
        if (pos + n > buf.length) {
            grow(pos + n);
        }
        switch (n) {
            case 4: buf[pos + 3] = (byte) (value >>> 24);
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new AssertionError("unreachable");
        }
        count = pos + n;
    }

    /**
     * Bulk append with an inlined capacity check. Semantically identical to
     * {@link #write(byte[], int, int)} minus the virtual-call indirection and
     * the {@code len == 0} branch; used by direct-buffer writers.
     */
    public void writeRaw(byte[] src, int off, int len) {
        int pos = count;
        if (pos + len > buf.length) {
            grow(pos + len);
        }
        System.arraycopy(src, off, buf, pos, len);
        count = pos + len;
    }

    /**
     * Grows the backing array so it holds at least {@code need} bytes
     * (absolute). Package-private so the same-package direct-buffer writers
     * (T4) can perform the inlined local capacity check + grow themselves.
     */
    void grow(int need) {
        int newCap = buf.length * 2;
        while (newCap < need) {
            newCap *= 2;
        }
        buf = Arrays.copyOf(buf, newCap);
    }
}
