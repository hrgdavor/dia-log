package hr.hrg.dialog.core;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Reusable, grow-only in-memory output stream with cursor-locality write helpers.
 * <p>
 * Merged from {@code CursorBuffer} (generic cursor-locality abstraction) and
 * {@code ReusableByteArrayOutputStream} (sink/IO). A single class that owns a
 * backing {@code byte[]} for one event/batch and exposes direct-cursor primitives
 * so writers can pull {@code buf}/{@code pos} into stack locals for the whole
 * hot loop — C2 keeps them in registers.
 * <p>
 * The backing array is allocated once and never shrinks: {@link #reset()} reuses
 * it, and it grows only when an event exceeds capacity (doubling). Hot-path
 * writers perform inlined capacity checks; the common no-grow case is a single
 * compare + branch with no virtual dispatch.
 * <p>
 * Thread safety: not thread-safe. Safe to share per stream/worker thread via
 * logback's {@code doAppend} guard, or wrap in your own synchronization.
 */
@NotThreadSafe
public class ReusableByteArrayOutputStream extends OutputStream {

    /** Default capacity: 1 MiB — large enough for any realistic event, small enough to be cheap. */
    public static final int DEFAULT_CAPACITY = 1 << 20;

    private static final VarHandle LE_LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /** Backing array (may be larger than the bytes in use). */
    public byte[] buf;
    /** Current write cursor (bytes written so far). */
    public int pos;

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

    // =========================================================================
    // OutputStream API (stream-mediated fallback for non-direct callers).
    // =========================================================================

    @Override
    public void write(int b) {
        byte[] buf = this.buf;
        int pos = this.pos;
        if (pos >= buf.length) {
            buf = grow(pos + 1);
        }
        buf[pos] = (byte) b;
        this.pos = pos + 1;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        if (len == 0) {
            return;
        }
        byte[] buf = this.buf;
        int pos = this.pos;
        int need = pos + len;
        if (need > buf.length) {
            buf = grow(need);
        }
        System.arraycopy(b, off, buf, pos, len);
        this.pos = need;
    }

    // =========================================================================
    // Stream state (size / buffer / reset / writeTo).
    // =========================================================================

    /** Reuses the current buffer for the next event (no reallocation, no shrink). */
    public void reset() {
        pos = 0;
    }

    /** Number of bytes currently buffered. */
    public int size() {
        return pos;
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
        out.write(buf, 0, pos);
    }

    // =========================================================================
    // Direct-cursor API (T4 "writer owns the buffer" fast path).
    // =========================================================================

    /** Current write cursor (direct-cursor read). */
    public int position() {
        return pos;
    }

    /**
     * Direct-cursor write: {@code pos} must be in {@code [0, buffer().length]}.
     * Callers that stored bytes into {@link #buffer()} publish them here.
     */
    public void setPosition(int pos) {
        if (pos < 0 || pos > buf.length) {
            throw new IndexOutOfBoundsException("position " + pos + " of " + buf.length);
        }
        this.pos = pos;
    }

    // =========================================================================
    // Cursor-locality helpers (merged from CursorBuffer).
    // =========================================================================

    /** Advances the cursor by {@code n} bytes (after absolute-offset stores). */
    public void advance(int n) {
        pos += n;
    }

    /** Publishes the cursor to the underlying sink (no-op — this class is its own sink). */
    public void publish() {}

    /** Re-reads buffer/position after the underlying stream was written directly (no-op). */
    public void resync() {}

    /**
     * Inlined capacity check: guarantees {@code pos + additional} bytes are
     * available. The common no-grow case is one compare; only the cold path
     * grows the backing array.
     */
    public void ensure(int additional) {
        int pos = this.pos;
        if (pos + additional > this.buf.length) {
            grow(pos + additional);
        }
    }

    /** Appends one byte with an inlined capacity check. */
    public void writeByte(int b) {
        byte[] buf = this.buf;
        int pos = this.pos;
        if (pos >= buf.length) {
            buf = grow(pos + 1);
        }
        buf[pos] = (byte) b;
        this.pos = pos + 1;
    }

    /** Bulk copy with an inlined capacity check. */
    public void writeRaw(byte[] src, int off, int len) {
        byte[] buf = this.buf;
        int pos = this.pos;
        int need = pos + len;
        if (need > buf.length) {
            buf = grow(need);
        }
        System.arraycopy(src, off, buf, pos, len);
        this.pos = need;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..8). */
    public void writeLongPrefixLE(long value, int n) {
        if (n < 0 || n > 8) {
            throw new IllegalArgumentException("n must be in 0..8: " + n);
        }
        byte[] buf = this.buf;
        int pos = this.pos;
        if (n == 8) {
            if (pos + 8 > buf.length) {
                buf = grow(pos + 8);
            }
            LE_LONG.set(buf, pos, value);
            this.pos = pos + 8;
            return;
        }
        if (pos + n > buf.length) {
            buf = grow(pos + n);
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
            default: throw new AssertionError("unreachable");
        }
        this.pos = pos + n;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..4). */
    public void writeIntPrefixLE(int value, int n) {
        if (n < 0 || n > 4) {
            throw new IllegalArgumentException("n must be in 0..4: " + n);
        }
        byte[] buf = this.buf;
        int pos = this.pos;
        if (pos + n > buf.length) {
            buf = grow(pos + n);
        }
        switch (n) {
            case 4: buf[pos + 3] = (byte) (value >>> 24);
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new AssertionError("unreachable");
        }
        this.pos = pos + n;
    }

    // =========================================================================
    // Cursor-locality helpers: writePackedLE / putPackedLE (merged from CursorBuffer).
    // =========================================================================

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..8). */
    public void writePackedLE(long value, int n) {
        byte[] buf = this.buf;
        int pos = this.pos;
        if (n == 8) {
            if (pos + 8 > buf.length) {
                buf = grow(pos + 8);
            }
            LE_LONG.set(buf, pos, value);
            this.pos = pos + 8;
            return;
        }
        if (pos + n > buf.length) {
            buf = grow(pos + n);
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
        this.pos = pos + n;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..4). */
    public void writePackedLE(int value, int n) {
        byte[] buf = this.buf;
        int pos = this.pos;
        if (n == 4) {
            if (pos + 4 > buf.length) {
                buf = grow(pos + 4);
            }
            LE_INT.set(buf, pos, value);
            this.pos = pos + 4;
            return;
        }
        if (pos + n > buf.length) {
            buf = grow(pos + n);
        }
        switch (n) {
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new IllegalArgumentException("n must be in 0..4: " + n);
        }
        this.pos = pos + n;
    }

    /**
     * Stores the low {@code n} bytes of {@code value} little-endian at the
     * absolute {@code offset} (which must already be within the ensured
     * capacity) without moving the cursor — used for overlapping-store patterns,
     * where the last word is stored at {@code start + (len - 8)} over bytes an
     * earlier word already wrote.
     */
    public void putPackedLE(int offset, long value, int n) {
        byte[] buf = this.buf;
        if (n == 8) {
            LE_LONG.set(buf, offset, value);
            return;
        }
        switch (n) {
            case 7: buf[offset + 6] = (byte) (value >>> 48);
            case 6: buf[offset + 5] = (byte) (value >>> 40);
            case 5: buf[offset + 4] = (byte) (value >>> 32);
            case 4: buf[offset + 3] = (byte) (value >>> 24);
            case 3: buf[offset + 2] = (byte) (value >>> 16);
            case 2: buf[offset + 1] = (byte) (value >>> 8);
            case 1: buf[offset] = (byte) value;
            case 0: break;
            default: throw new IllegalArgumentException("n must be in 0..8: " + n);
        }
    }

    // =========================================================================
    // Growth (package-private so direct-buffer writers can inline the check).
    // =========================================================================

    /**
     * Grows the backing array so it holds at least {@code need} bytes
     * (absolute). Public so direct-buffer writers (T4/T7, e.g.
     * {@code JsonLogWriter} in the logback module) can perform the inlined
     * local capacity check + grow themselves and keep the cursor in registers.
     * <p>
     * Returns the (possibly reallocated) backing array so callers can refresh
     * their {@code buf} local with the return value — the capacity is always
     * {@code buf.length}, so there is no separate {@code limit} to fetch.
     *
     * @return the backing array after growth (the new {@code buf})
     */
    public byte[] grow(int need) {
        int newCap = buf.length * 2;
        while (newCap < need) {
            newCap *= 2;
        }
        buf = Arrays.copyOf(buf, newCap);
        return buf;
    }
}
