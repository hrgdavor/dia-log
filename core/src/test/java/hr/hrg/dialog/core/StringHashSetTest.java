package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StringHashSet}: dedup semantics, growth/rehash, reuse via
 * {@code clear()}, null-key handling, pre-sizing, and steady-state
 * zero-allocation behavior.
 */
class StringHashSetTest {

    // ==========================================================================
    //  Dedup semantics
    // ==========================================================================

    @Test
    void add_returnsTrueForNew_falseForDuplicate() {
        StringHashSet set = new StringHashSet();
        assertTrue(set.add("alpha"));
        assertTrue(set.add("beta"));
        assertFalse(set.add("alpha"), "re-adding an equal key must report duplicate");
        assertFalse(set.add("beta"));
        assertEquals(2, set.size());
    }

    @Test
    void add_sameReference_isDuplicate() {
        StringHashSet set = new StringHashSet();
        String key = "shared";
        assertTrue(set.add(key));
        assertFalse(set.add(key), "re-adding the very same reference must be a duplicate");
        assertFalse(set.add(new String("shared")), "equal content must be a duplicate too");
    }

    @Test
    void contains_findsAddedKeys() {
        StringHashSet set = new StringHashSet();
        set.add("gamma");
        set.add("delta");
        assertTrue(set.contains("gamma"));
        assertTrue(set.contains("delta"));
        assertTrue(set.contains(new String("gamma")), "contains must match by content, not identity");
    }

    @Test
    void contains_absentKeys_returnFalse() {
        StringHashSet set = new StringHashSet();
        set.add("present");
        assertFalse(set.contains("absent"));
        assertFalse(set.contains(""));
        assertFalse(set.contains("presentX"));
    }

    @Test
    void emptyString_isAKey() {
        StringHashSet set = new StringHashSet();
        assertTrue(set.add(""));
        assertFalse(set.add(""));
        assertTrue(set.contains(""));
        assertEquals(1, set.size());
    }

    @Test
    void nullKey_supported() {
        StringHashSet set = new StringHashSet();
        assertTrue(set.add(null));
        assertFalse(set.add(null), "null key must be deduplicated");
        assertTrue(set.contains(null));
        assertEquals(1, set.size());
        set.add("real");
        assertEquals(2, set.size());
        assertTrue(set.contains(null));
    }

    @Test
    void size_countsDistinctOnly() {
        StringHashSet set = new StringHashSet();
        set.add("a");
        set.add("a");
        set.add("b");
        set.add(null);
        set.add(null);
        set.add("c");
        assertEquals(4, set.size());
        assertFalse(set.isEmpty());
    }

    @Test
    void defaultConstructor_isEmpty() {
        StringHashSet set = new StringHashSet();
        assertTrue(set.isEmpty());
        assertEquals(0, set.size());
        assertFalse(set.contains("anything"));
        assertFalse(set.contains(null));
    }

    // ==========================================================================
    //  clear() and reuse
    // ==========================================================================

    @Test
    void clear_resetsSet() {
        StringHashSet set = new StringHashSet();
        set.add("one");
        set.add("two");
        set.add(null);
        set.clear();
        assertTrue(set.isEmpty());
        assertEquals(0, set.size());
        assertFalse(set.contains("one"));
        assertFalse(set.contains(null));
    }

    @Test
    void reuse_afterClear_acceptsSameKeysAgain() {
        StringHashSet set = new StringHashSet();
        String[] keys = {"k0", "k1", "k2", "k3", "k4"};

        for (int round = 0; round < 1000; round++) {
            for (String key : keys) {
                assertTrue(set.add(key), "round " + round + ": fresh key must be accepted after clear");
            }
            assertEquals(keys.length, set.size());
            set.clear();
            assertTrue(set.isEmpty());
        }
    }

    @Test
    void clear_doesNotShrink_reuseKeepsCapacity() {
        StringHashSet set = new StringHashSet();
        for (int i = 0; i < 1000; i++) {
            set.add("key-" + i);
        }
        assertEquals(1000, set.size());
        set.clear();
        // No reallocation happened during clear; adding the same volume again
        // must not trigger growth-related allocations beyond the first round.
        for (int i = 0; i < 1000; i++) {
            assertTrue(set.add("key-" + i));
        }
        assertEquals(1000, set.size());
    }

    // ==========================================================================
    //  Growth / rehash
    // ==========================================================================

    @Test
    void grows_whenCapacityExceeded_allKeysSurvive() {
        StringHashSet set = new StringHashSet();
        int count = 5000;
        for (int i = 0; i < count; i++) {
            assertTrue(set.add("key-" + i), "key " + i + " must be new");
        }
        assertEquals(count, set.size());
        for (int i = 0; i < count; i++) {
            assertTrue(set.contains("key-" + i), "key " + i + " must survive growth");
        }
    }

    @Test
    void grows_dedupStillAccurate() {
        StringHashSet set = new StringHashSet(4); // tiny start: forces many growth cycles
        Set<String> reference = new HashSet<>();
        Random rnd = new Random(0xC0FFEE);
        for (int i = 0; i < 2000; i++) {
            String key = "key-" + rnd.nextInt(500);
            boolean added = set.add(key);
            assertEquals(reference.add(key), added, "add parity at i=" + i);
        }
        assertEquals(reference.size(), set.size(), "distinct count parity");
        for (String key : reference) {
            assertTrue(set.contains(key), "key must survive growth: " + key);
        }
    }

    @Test
    void tinyInitialCapacity_stillWorks() {
        StringHashSet set = new StringHashSet(0);
        for (int i = 0; i < 50; i++) {
            set.add("v" + i);
        }
        assertEquals(50, set.size());
        assertTrue(set.contains("v49"));
        set.clear();
        assertTrue(set.isEmpty());
    }

    @Test
    void randomized_matchesJavaHashSet() {
        StringHashSet set = new StringHashSet(8);
        Set<String> reference = new HashSet<>();
        Random rnd = new Random(0xDED05L);

        for (int i = 0; i < 20000; i++) {
            String key;
            if (rnd.nextInt(10) == 0) {
                key = null;
            } else {
                key = "key-" + rnd.nextInt(3000);
            }
            boolean added = set.add(key);
            boolean refAdded = reference.add(key);
            assertEquals(refAdded, added, "add parity for key=" + key);
            assertEquals(reference.size(), set.size(), "size parity");
            if (i % 97 == 0) {
                String probe = rnd.nextInt(10) == 0 ? null : "key-" + rnd.nextInt(3000);
                assertEquals(reference.contains(probe), set.contains(probe),
                        "contains parity for key=" + probe);
            }
        }

        set.clear();
        reference.clear();
        assertEquals(reference.size(), set.size());
        assertTrue(set.isEmpty());
    }

    // ==========================================================================
    //  ensureCapacity
    // ==========================================================================

    @Test
    void ensureCapacity_presizes() {
        StringHashSet set = new StringHashSet();
        set.ensureCapacity(1000);
        for (int i = 0; i < 1000; i++) {
            assertTrue(set.add("pre-" + i));
        }
        assertEquals(1000, set.size());
        for (int i = 0; i < 1000; i++) {
            assertTrue(set.contains("pre-" + i));
        }
    }

    @Test
    void ensureCapacity_smallerThanCurrent_isNoOp() {
        StringHashSet set = new StringHashSet(256);
        for (int i = 0; i < 100; i++) {
            set.add("x" + i);
        }
        set.ensureCapacity(8); // must not shrink or corrupt
        assertEquals(100, set.size());
        assertTrue(set.contains("x99"));
    }

    // ==========================================================================
    //  Zero allocation in steady state
    // ==========================================================================

    /**
     * Verifies that the steady-state dedup loop — {@code add} of duplicate
     * keys, {@code contains}, and {@code clear} — allocates zero heap bytes
     * per round once the table is sized and the key hashes are cached
     * (mirrors the pattern in {@code Wyhash64StreamingTest#testFinalHashDoesNotAllocate}).
     */
    @Test
    void steadyState_addContainsClear_allocateNothing() {
        com.sun.management.ThreadMXBean tmb =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();

        String[] keys = new String[100];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "key-" + i;
        }
        StringHashSet set = new StringHashSet();

        // Warmup: JIT-compile add/contains/clear and cache every key's hashCode.
        for (int i = 0; i < 30000; i++) {
            for (String key : keys) {
                set.add(key);
            }
            for (String key : keys) {
                set.contains(key);
            }
            set.clear();
        }

        long acc = 0;
        long before = tmb.getCurrentThreadAllocatedBytes();
        int rounds = 50000;
        for (int i = 0; i < rounds; i++) {
            for (String key : keys) {
                acc += set.add(key) ? 1 : 0;
            }
            for (String key : keys) {
                acc += set.contains(key) ? 1 : 0;
            }
            set.clear();
        }
        long after = tmb.getCurrentThreadAllocatedBytes();

        long perRound = (after - before) / rounds;
        assertTrue(acc > 0, "sanity: accumulator must be non-zero");
        assertEquals(0, perRound,
                "steady-state dedup round must not allocate; measured " + perRound + " bytes/round");
    }
}
