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

    private void grow(int need) {
        int newCap = buf.length * 2;
        while (newCap < need) {
            newCap *= 2;
        }
        buf = Arrays.copyOf(buf, newCap);
    }
}
