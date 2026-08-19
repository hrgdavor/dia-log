package hr.hrg.dialog.core;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Reusable direct-assembly cursor over a {@link ReusableByteArrayOutputStream}
 * — T4 option 2, the full "writer owns the buffer" design.
 *
 * <p>Ported from Apache Fory commit 585eb16f ("feat(java): optimize json
 * perf", PR #3871), {@code Utf8JsonWriter}'s {@code @Internal getBuffer() /
 * getPosition() / setPosition() / grow()} pattern: the event writer keeps
 * {@code byte[] buf} and {@code int pos} live for the whole event, performs
 * inlined capacity checks ({@link #ensure(int)}), and publishes the cursor
 * once via {@link #publish()}. Stream-only delegations (jackson, raw values,
 * stack-trace writers) publish first, write through {@link #target()}, then
 * {@link #resync()}.
 *
 * <p>One cursor per writer instance, reused across events via
 * {@link #reset(ReusableByteArrayOutputStream)} — no per-event allocation.
 * {@link NotThreadSafe}, exactly like the writers that own it.
 */
@NotThreadSafe
public final class DirectJsonBuffer {

    private ReusableByteArrayOutputStream out;
    private byte[] buf;
    private int pos;

    /** Re-points this cursor at {@code out} and re-reads its buffer/position. */
    public void reset(ReusableByteArrayOutputStream out) {
        this.out = out;
        this.buf = out.buffer();
        this.pos = out.position();
    }

    /** The underlying buffer, for delegations that require an {@code OutputStream}. */
    public ReusableByteArrayOutputStream target() {
        return out;
    }

    /** Current cursor position (bytes written so far). */
    public int position() {
        return pos;
    }

    /** Advances the cursor by {@code n} bytes (after absolute-offset stores). */
    public void advance(int n) {
        pos += n;
    }

    /** Publishes the cursor to the underlying buffer. */
    public void publish() {
        out.setPosition(pos);
    }

    /** Re-reads buffer/position after the underlying stream was written directly. */
    public void resync() {
        pos = out.position();
        buf = out.buffer();
    }

    /**
     * Inlined capacity check (T3): the common no-grow case is one compare; only
     * the cold path publishes the cursor, grows the backing array and re-reads it.
     */
    public void ensure(int additional) {
        if (pos + additional > buf.length) {
            out.setPosition(pos);
            out.grow(pos + additional);
            buf = out.buffer();
        }
    }

    /** Appends one byte with an inlined capacity check. */
    public void writeByte(int b) {
        ensure(1);
        buf[pos++] = (byte) b;
    }

    /** Bulk copy with an inlined capacity check. */
    public void writeRaw(byte[] src, int off, int len) {
        ensure(len);
        System.arraycopy(src, off, buf, pos, len);
        pos += len;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..8). */
    public void writePackedLE(long value, int n) {
        ensure(n);
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
            default: throw new IllegalArgumentException("n must be in 0..8: " + n);
        }
        pos += n;
    }

    /** Stores the low {@code n} bytes of {@code value} little-endian (n in 0..4). */
    public void writePackedLE(int value, int n) {
        ensure(n);
        switch (n) {
            case 4: buf[pos + 3] = (byte) (value >>> 24);
            case 3: buf[pos + 2] = (byte) (value >>> 16);
            case 2: buf[pos + 1] = (byte) (value >>> 8);
            case 1: buf[pos] = (byte) value;
            case 0: break;
            default: throw new IllegalArgumentException("n must be in 0..4: " + n);
        }
        pos += n;
    }

    /**
     * Stores the low {@code n} bytes of {@code value} little-endian at the
     * absolute {@code offset} (which must already be within the ensured
     * capacity) without moving the cursor — used for the T2 overlapping-store
     * patterns, where the last word is stored at {@code start + (len - 8)}
     * over bytes an earlier word already wrote.
     */
    public void putPackedLE(int offset, long value, int n) {
        switch (n) {
            case 8: buf[offset + 7] = (byte) (value >>> 56);
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
