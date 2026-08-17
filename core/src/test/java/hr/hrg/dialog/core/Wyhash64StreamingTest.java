package hr.hrg.dialog.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Wyhash64StreamingTest {

    @Test
    public void testSingleByteUpdateMatchesBulkHash() {
        long seed = 0x9E3779B97F4A7C15L;
        byte[] data = "single-byte-fast-path-validation-payload".getBytes(StandardCharsets.UTF_8);

        long expected = Wyhash64.hash(seed, data);

        Wyhash64.Streaming stream = new Wyhash64.Streaming(seed);
        for (byte b : data) {
            stream.updateByte(b);
        }

        assertEquals(expected, stream.finalHash(), "updateByte path must match bulk hash");
    }

    @Test
    public void testSingleByteMixedWithChunkedUpdateMatchesBulkHash() {
        long seed = 0x1234ABCD5678EF90L;
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }

        long expected = Wyhash64.hash(seed, data);

        Wyhash64.Streaming stream = new Wyhash64.Streaming(seed);
        for (int i = 0; i < 73; i++) {
            stream.updateByte(data[i]);
        }
        stream.update(data, 73, 101);
        for (int i = 174; i < data.length; i++) {
            stream.updateByte(data[i]);
        }

        assertEquals(expected, stream.finalHash(), "Mixed updateByte + chunked update must match bulk hash");
    }

    @Test
    public void testSingleByteUpdateAfterResetMatchesBulkHash() {
        long seed = 0xCAFEBABE12345678L;

        byte[] first = "first-payload-for-reset-check".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-payload-with-different-length-and-content".getBytes(StandardCharsets.UTF_8);

        long expectedFirst = Wyhash64.hash(seed, first);
        long expectedSecond = Wyhash64.hash(seed, second);

        Wyhash64.Streaming stream = new Wyhash64.Streaming(seed);

        for (byte b : first) {
            stream.updateByte(b);
        }
        assertEquals(expectedFirst, stream.finalHash(), "First updateByte hash must match bulk hash");

        stream.reset(seed);
        for (byte b : second) {
            stream.updateByte(b);
        }
        assertEquals(expectedSecond, stream.finalHash(), "updateByte after reset must match bulk hash");
    }

    @Test
    public void testStreamingWithScratchReuse() {
        long seed = 42L;

        // We need totalLen > 16, but the final chunk in the buffer to be < 16.
        // A total length of 20 bytes is perfect: 
        // First 16 bytes pass through or fill up, leaving a 4-byte tail frame (< 16).
        byte[] data1 = "This is a 20-byte st".getBytes(StandardCharsets.UTF_8); // 20 bytes
        byte[] data2 = "Different 20-byte string".substring(0, 20).getBytes(StandardCharsets.UTF_8); // 20 bytes

        // 1. Get the ground truth using the reliable bulk hashing method
        long expectedHash1 = Wyhash64.hash(seed, data1);
        long expectedHash2 = Wyhash64.hash(seed, data2);

        // 2. Test Stream 1
        Wyhash64.Streaming stream1 = new Wyhash64.Streaming(seed);
        stream1.update(data1);
        long streamHash1 = stream1.finalHash();

        // 3. Test Stream 2 (This checks if data1 leaked into data2 via a shared/reused scratch state)
        Wyhash64.Streaming stream2 = new Wyhash64.Streaming(seed);
        stream2.update(data2);
        long streamHash2 = stream2.finalHash();

        // Assertions
        assertEquals(expectedHash1, streamHash1, "Stream 1 hash failed to match bulk hash!");
        assertEquals(expectedHash2, streamHash2, "Stream 2 hash failed to match bulk hash! Potential scratch leak.");
    }

    @Test
    public void testChunkedStreamingMatchesBulk() {
        long seed = 12345L;
        // 50 bytes total ensures we hit the > 48 byte parallel loops AND leave a small remaining tail
        byte[] fullData = " de_sabotage_your_code_with_proper_unit_tests_1234".getBytes(StandardCharsets.UTF_8); 
        
        long expectedHash = Wyhash64.hash(seed, fullData);

        // Feed data in messy, unpredictable chunks to aggressively force the streaming buffer shifts
        Wyhash64.Streaming stream = new Wyhash64.Streaming(seed);
        stream.update(fullData, 0, 10);
        stream.update(fullData, 10, 15);
        stream.update(fullData, 25, 20);
        stream.update(fullData, 45, 5); // Final remaining 5 bytes (forces scratch logic)

        long streamHash = stream.finalHash();

        assertEquals(expectedHash, streamHash, "Chunked streaming failed to match bulk hash.");
    }

    /**
     * Exhaustively exercises finalHash() for every pending-tail size 1..16
     * (i.e. every {@code rem = 16 - bufLen} value) with totalLen > 16, across
     * three feeding styles:
     * <ul>
     *   <li>single bulk byte[] update — tail prefix copied explicitly into buf[48-rem..48)</li>
     *   <li>two byte[] updates — tail prefix retained naturally by the drain round</li>
     *   <li>byte-by-byte updateByte — tail prefix retained naturally</li>
     * </ul>
     */
    @Test
    public void testFinalHashEveryTailSizeMatchesBulk() {
        long seed = 0x123456789ABCDEFL;
        java.util.Random rnd = new java.util.Random(0xBEEF);

        for (int blocks = 1; blocks <= 3; blocks++) {
            for (int tail = 1; tail <= 16; tail++) {
                int total = blocks * 48 + tail;
                byte[] data = new byte[total];
                rnd.nextBytes(data);

                long expected = Wyhash64.hash(seed, data);

                // Flavor A: single bulk update — prefix copied explicitly
                Wyhash64.Streaming stA = new Wyhash64.Streaming(seed);
                stA.update(data);
                assertEquals(expected, stA.finalHash(),
                        "single update failed: blocks=" + blocks + " tail=" + tail);

                // Flavor B: two updates — prefix retained via drain round
                Wyhash64.Streaming stB = new Wyhash64.Streaming(seed);
                stB.update(data, 0, blocks * 48);
                stB.update(data, blocks * 48, tail);
                assertEquals(expected, stB.finalHash(),
                        "two-update failed: blocks=" + blocks + " tail=" + tail);

                // Flavor C: byte-by-byte — prefix retained via drain round
                Wyhash64.Streaming stC = new Wyhash64.Streaming(seed);
                for (byte b : data) {
                    stC.updateByte(b);
                }
                assertEquals(expected, stC.finalHash(),
                        "updateByte failed: blocks=" + blocks + " tail=" + tail);
            }
        }
    }

    /**
     * finalHash() when the stream length is an exact multiple of 48 and the
     * last update is a Latin-1 {@code char[]}: the final 48-char block must
     * stay in buf unrounded (bufLen == 48) so finalHash processes it in
     * 16-byte steps, matching the bulk byte[] hash.
     */
    @Test
    public void testFinalHashNoPendingTailLatin1MatchesBulk() {
        long seed = 0xABCDEF0123456789L;
        java.util.Random rnd = new java.util.Random(0x1234);

        for (int blocks = 1; blocks <= 3; blocks++) {
            int total = blocks * 48;
            byte[] data = new byte[total];
            rnd.nextBytes(data);
            char[] chars = new char[total];
            for (int i = 0; i < total; i++) {
                chars[i] = (char) (data[i] & 0xFF);
            }

            long expected = Wyhash64.hash(seed, data);

            // Whole stream as one Latin-1 char[] update
            Wyhash64.Streaming st = new Wyhash64.Streaming(seed);
            st.update(chars);
            assertEquals(expected, st.finalHash(),
                    "latin1 single char[] update failed: blocks=" + blocks);
        }
    }

    /**
     * finalHash() when a char[] drain fills the buffer to exactly 48 bytes
     * (bufLen == 48): the round must be deferred so the final block is
     * processed by finalHash in 16-byte steps, matching the bulk byte[] hash.
     */
    @Test
    public void testFinalHashNoPendingTailDrainMatchesBulk() {
        long seed = 0xDEADBEEFCAFEBABEL;
        java.util.Random rnd = new java.util.Random(0x5150);

        for (int blocks = 1; blocks <= 3; blocks++) {
            int total = blocks * 48;
            byte[] data = new byte[total];
            rnd.nextBytes(data);
            char[] chars = new char[total];
            for (int i = 0; i < total; i++) {
                chars[i] = (char) (data[i] & 0xFF);
            }

            long expected = Wyhash64.hash(seed, data);

            // 40 bytes via byte[] (bufLen=40), then the rest via char[]:
            // the drain fills buf[40..48) and rounds from buf, bufLen ends 0
            Wyhash64.Streaming st = new Wyhash64.Streaming(seed);
            st.update(data, 0, 40);
            st.update(chars, 40, total - 40);
            assertEquals(expected, st.finalHash(),
                    "drain-to-empty failed: blocks=" + blocks);
        }
    }

    /**
     * Verifies that {@link Wyhash64.Streaming#finalHash()} performs zero heap
     * allocation on the former scratch path (totalLen > 16 with a short pending
     * tail). Registers a regression for the removed {@code byte[16]} scratch
     * and the removed per-call {@code long[3]} state copy.
     */
    @Test
    public void testFinalHashDoesNotAllocate() {
        com.sun.management.ThreadMXBean tmb =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long seed = 7L;
        byte[] data = new byte[97]; // totalLen > 16, 1-byte tail: former scratch path
        new java.util.Random(1).nextBytes(data);

        Wyhash64.Streaming st = new Wyhash64.Streaming(seed);
        st.update(data);

        // Warm up so the JIT compiles finalHash before measuring
        long acc = 0;
        for (int i = 0; i < 30000; i++) {
            acc += st.finalHash();
        }

        long before = tmb.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 200000; i++) {
            acc += st.finalHash();
        }
        long after = tmb.getCurrentThreadAllocatedBytes();

        long perCall = (after - before) / 200000;
        assertTrue(acc != 0, "sanity: accumulator must be non-zero");
        assertEquals(0, perCall,
                "finalHash() must not allocate per call; measured " + perCall + " bytes/call");
    }

    /**
     * finalHash() with an empty buffer (rem == 16) after a UTF-16 char[]
     * update — the block is packed into buf and rounded from buf, so its last
     * 16 bytes are retained naturally at buf[32..48).
     */
    @Test
    public void testFinalHashNoPendingTailUtf16MatchesBulk() {
        long seed = 0xFEEDBEEF12345678L;
        java.util.Random rnd = new java.util.Random(0x77);

        for (int blocks = 1; blocks <= 2; blocks++) {
            int charCount = blocks * 24; // 48 bytes per block
            char[] chars = new char[charCount];
            for (int i = 0; i < charCount; i++) {
                chars[i] = (char) (0x100 + rnd.nextInt(0xF000)); // non-Latin-1 -> UTF-16
            }
            byte[] bytes = new byte[charCount * 2];
            for (int i = 0; i < charCount; i++) {
                bytes[i * 2] = (byte) (chars[i] & 0xFF);
                bytes[i * 2 + 1] = (byte) ((chars[i] >> 8) & 0xFF);
            }

            long expected = Wyhash64.hash(seed, bytes);

            Wyhash64.Streaming st = new Wyhash64.Streaming(seed);
            st.update(chars);
            assertEquals(expected, st.finalHash(),
                    "utf16 no-tail failed: blocks=" + blocks);
        }
    }
}
