package hr.hrg.dialog.core;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Resettable, allocation-minimizing hash set specialized for {@link String} keys.
 * <p>
 * The sole purpose is <b>key deduplication</b>: {@link #add(String)} returns
 * {@code true} when the key was not present before and {@code false} when it
 * already is. It intentionally implements no {@code java.util} interfaces
 * (no {@code Set}, no iterator, no entry view) to keep the API and the hot
 * paths as simple as possible.
 * </p>
 * <h2>Reuse / reset</h2>
 * <p>
 * {@link #clear()} does not shrink or reallocate anything — it only nulls the
 * occupied slots (releasing the key references for GC) and resets the size.
 * The same instance can be used for millions of dedup rounds (e.g. one set per
 * appender, cleared once per log event) without allocating after the initial
 * capacity has been reached. {@link #ensureCapacity(int)} pre-sizes the table
 * when the peak key count per round is known in advance.
 * </p>
 * <h2>Allocation behavior</h2>
 * <p>
 * Steady-state {@code add}/{@code contains}/{@code clear} allocate nothing:
 * no boxing, no per-call arrays, no entry objects. The only allocations are
 * the initial {@code String[]} table and the new table created when the set
 * grows (capacity doubles, entries rehashed). It grows only when needed —
 * never eagerly on {@code clear()}.
 * </p>
 * <h2>Hashing</h2>
 * <p>
 * Uses {@link String#hashCode()} with the standard
 * {@code h ^ (h >>> 16)} spread before masking against the power-of-two table.
 * This is deliberately <i>not</i> {@link Wyhash64#hash(long, String)}:
 * <ul>
 *   <li>{@code String.hashCode()} is <b>cached inside the String</b>, so
 *       re-checking an already-seen key (the common dedup case) costs no
 *       hash work at all, and re-hashing during growth is nearly free for
 *       keys whose hash was already computed.</li>
 *   <li>It is allocation-free on <b>every</b> JVM. The zero-allocation
 *       {@code Wyhash64} String path requires {@code --add-opens}; on JDK 25+
 *       (this project's target) it falls back to {@code toCharArray()}, i.e.
 *       one allocation per hash — unacceptable in a dedup hot path.</li>
 *   <li>Its distribution is the same one {@code java.util.HashMap} relies
 *       on in production; linear probing plus a 2/3 load factor keeps probe
 *       chains short for realistic key sets.</li>
 * </ul>
 * Equality checks use an identity fast path ({@code existing == key}) before
 * {@code String.equals}, so re-adding the very same reference is a quick hit.
 * </p>
 * <h2>Probing / semantics</h2>
 * <p>
 * Open addressing with linear probing; a {@code null} slot terminates a
 * probe chain. There is no removal API — for deduplication keys are never
 * removed individually, only reset wholesale via {@link #clear()}. {@code null}
 * is a supported key, tracked by a single flag.
 * </p>
 * <p>Not thread-safe: like {@link Wyhash64.Streaming}, one instance per
 * thread or externally synchronized.</p>
 */
@NotThreadSafe
public final class StringHashSet {

    /** Default initial table capacity (power of two). */
    public static final int DEFAULT_CAPACITY = 16;

    /** Minimum table capacity; avoids a degenerate threshold of 0. */
    private static final int MIN_CAPACITY = 4;

    /** Grow when {@code size} exceeds {@code capacity * MAX_LOAD_NUM / MAX_LOAD_DEN}. */
    private static final int MAX_LOAD_NUM = 2;
    private static final int MAX_LOAD_DEN = 3;

    /** Hash table; {@code null} marks an empty slot. Power-of-two length. */
    private String[] table;
    /** {@code table.length - 1}, used to mask hash codes into slot indices. */
    private int mask;
    /** Number of keys stored (including a possible {@code null} key). */
    private int size;
    /** Size at which the next {@link #add(String)} triggers {@link #grow()}. */
    private int threshold;
    /** Whether {@code null} has been added as a key. */
    private boolean hasNull;

    /** Creates an empty set with {@link #DEFAULT_CAPACITY}. */
    public StringHashSet() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates an empty set with a table large enough to hold roughly
     * {@code initialCapacity} keys before growing. The actual capacity is the
     * smallest power of two that satisfies the load-factor threshold.
     */
    public StringHashSet(int initialCapacity) {
        int cap = MIN_CAPACITY;
        while (cap < initialCapacity) {
            cap <<= 1;
        }
        table = new String[cap];
        mask = cap - 1;
        threshold = cap * MAX_LOAD_NUM / MAX_LOAD_DEN;
    }

    /**
     * Adds {@code key} if it is not already present.
     *
     * @return {@code true} if the key was newly added, {@code false} if it was
     *         already in the set (the dedup signal)
     */
    public boolean add(String key) {
        if (key == null) {
            if (hasNull) {
                return false;
            }
            hasNull = true;
            size++;
            return true;
        }
        int idx = hash(key) & mask;
        while (true) {
            String existing = table[idx];
            if (existing == null) {
                table[idx] = key;
                size++;
                if (size > threshold) {
                    grow();
                }
                return true;
            }
            if (existing == key || existing.equals(key)) {
                return false;
            }
            idx = (idx + 1) & mask;
        }
    }

    /**
     * Returns {@code true} if {@code key} is present.
     */
    public boolean contains(String key) {
        if (key == null) {
            return hasNull;
        }
        int idx = hash(key) & mask;
        while (true) {
            String existing = table[idx];
            if (existing == null) {
                return false;
            }
            if (existing == key || existing.equals(key)) {
                return true;
            }
            idx = (idx + 1) & mask;
        }
    }

    /**
     * Removes all keys, reusing the current table (no allocation, no shrink).
     * Occupied slots are nulled so the previously stored keys become
     * garbage-collectable, making the instance ready for the next dedup round.
     */
    public void clear() {
        String[] t = table;
        for (int i = 0; i < t.length; i++) {
            t[i] = null;
        }
        size = 0;
        hasNull = false;
    }

    /**
     * Ensures the table can hold {@code minCapacity} keys without growing.
     * Grows (allocating a new table and rehashing) only if needed; otherwise
     * this is a no-op.
     */
    public void ensureCapacity(int minCapacity) {
        if (minCapacity <= threshold) {
            return;
        }
        long needed = ((long) minCapacity * MAX_LOAD_DEN + MAX_LOAD_NUM - 1) / MAX_LOAD_NUM;
        int cap = table.length;
        while (cap < needed) {
            cap <<= 1;
        }
        if (cap > table.length) {
            growTo(cap);
        }
    }

    /**
     * Number of distinct keys currently in the set (a single {@code null}
     * key, if present, is counted).
     */
    public int size() {
        return size;
    }

    /** Returns {@code true} if the set contains no keys. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** Doubles the table capacity and rehashes all entries. */
    private void grow() {
        growTo(table.length << 1);
    }

    /** Rehashes all entries into a fresh table of {@code newCap} (power of two). */
    private void growTo(int newCap) {
        String[] old = table;
        String[] fresh = new String[newCap];
        int newMask = newCap - 1;
        for (String key : old) {
            if (key != null) {
                int idx = hash(key) & newMask;
                while (fresh[idx] != null) {
                    idx = (idx + 1) & newMask;
                }
                fresh[idx] = key;
            }
        }
        table = fresh;
        mask = newMask;
        threshold = newCap * MAX_LOAD_NUM / MAX_LOAD_DEN;
    }

    /**
     * Spreads the (cached) {@code String.hashCode()} so the high bits
     * participate in the power-of-two mask, mirroring
     * {@code java.util.HashMap}'s proven scatter.
     */
    private static int hash(String key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }
}
