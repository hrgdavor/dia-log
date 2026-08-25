package hr.hrg.dialog.core;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Reusable, fixed-capacity in-memory output stream with cursor-locality write
 * helpers.
 * <p>
 * Merged from {@code CursorBuffer} (generic cursor-locality abstraction) and
 * {@code ReusableByteArrayOutputStream} (sink/IO). A single class that owns a
 * fixed-size backing {@code byte[]} for one event/batch and exposes
 * direct-cursor primitives so writers can pull {@code buf}/{@code pos} into stack
 * locals for the whole hot loop — C2 keeps them in registers.
 * <p>
 * The backing array is allocated once at a fixed capacity and never reallocates.
 * When a write would exceed capacity it throws {@link BufferFullException}
 * instead of growing, so the event assembly can replace an overflowing value with
 * the {@code "V2BIG"} placeholder and keep going. {@link #reset()} reuses the
 * array across events; it never shrinks.
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
     * Creates a buffer with the given fixed initial capacity (never reallocates).
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
            throw new BufferFullException();
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
        // All-or-nothing: check before any copy, so an over-long write leaves the
        // buffer untouched (the caller reverts its cursor on the thrown signal).
        if (pos + len > buf.length) {
            throw new BufferFullException();
        }
        System.arraycopy(b, off, buf, pos, len);
        this.pos = pos + len;
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

    // =========================================================================
    // Cursor-locality helpers: writePackedLE / putPackedLE (merged from CursorBuffer).
    // =========================================================================

    /**
     * Appends one byte. Throws {@link BufferFullException} (no-grow) if the
     * buffer is full — the caller reverts its cursor and finalizes the event.
     */
    public void writeByte(int b) {
        byte[] buf = this.buf;
        int pos = this.pos;
        if (pos >= buf.length) {
            throw new BufferFullException();
        }
        buf[pos] = (byte) b;
        this.pos = pos + 1;
    }

    /**
     * Bulk copy. All-or-nothing: if the copy would overflow the fixed capacity,
     * throws {@link BufferFullException} without writing any bytes, so the
     * caller's cursor reverts cleanly.
     */
    public void writeRaw(byte[] src, int off, int len) {
        byte[] buf = this.buf;
        int pos = this.pos;
        if (pos + len > buf.length) {
            throw new BufferFullException();
        }
        System.arraycopy(src, off, buf, pos, len);
        this.pos = pos + len;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..8). */
    public void writeLongPrefixLE(long value, int n) {
        if (n < 0 || n > 8) {
            throw new IllegalArgumentException("n must be in 0..8: " + n);
        }
        byte[] buf = this.buf;
        int pos = this.pos;
        if (pos + n > buf.length) {
            throw new BufferFullException();
        }
        if (n == 8) {
            LE_LONG.set(buf, pos, value);
            this.pos = pos + 8;
            return;
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
            throw new BufferFullException();
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

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..8). */
    public void writePackedLE(long value, int n) {
        byte[] buf = this.buf;
        int pos = this.pos;
        if (pos + n > buf.length) {
            throw new BufferFullException();
        }
        if (n == 8) {
            LE_LONG.set(buf, pos, value);
            this.pos = pos + 8;
            return;
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
        if (pos + n > buf.length) {
            throw new BufferFullException();
        }
        if (n == 4) {
            LE_INT.set(buf, pos, value);
            this.pos = pos + 4;
            return;
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
     * earlier word already wrote. A misuse (offset out of bounds) surfaces as the
     * JDK's {@link ArrayIndexOutOfBoundsException}, which is acceptable and
     * defensive.
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
}
