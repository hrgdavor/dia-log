package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Ports the test cases from Zig's {@code std/hash/wyhash.zig} (the reference
 * variant this port aims to match) into JUnit, so the Java implementation is
 * verified against the exact same expectations:
 * <ul>
 *   <li>the canonical wyhash test vectors (from wyhash's test_vector.cpp)</li>
 *   <li>the SMHasher verification code</li>
 *   <li>the iterative-API verifier (incremental updates must stay idempotent
 *       and match the single-shot hash at every cumulative length)</li>
 *   <li>the "iterative maintains last sixteen" case (a full 48-byte block
 *       followed by every tail length 1..17)</li>
 * </ul>
 * Reference: {@code lib/std/hash/wyhash.zig} and {@code lib/std/hash/verify.zig}
 * in the Zig standard library.
 */
class Wyhash64ZigCompatibilityTest {

    private static final long[] SEEDS = {0, 1, 2, 3, 4, 5, 6};
    private static final long[] EXPECTED = {
        0x409638ee2bde459L,
        0xa8412d091b5fe0a9L,
        0x32dd92e4b2915153L,
        0x8619124089a3a16bL,
        0x7a43afb61d7f5f40L,
        0xff42329b90e50d58L,
        0xc39cab13b115aad3L,
    };
    private static final String[] INPUTS = {
        "",
        "a",
        "abc",
        "message digest",
        "abcdefghijklmnopqrstuvwxyz",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
        "12345678901234567890123456789012345678901234567890123456789012345678901234567890",
    };

    // ==========================================================================
    //  Zig "test vectors" — canonical wyhash values, checked for every overload
    // ==========================================================================

    @Test
    void zigTestVectors_singleShotBytes() {
        for (int i = 0; i < INPUTS.length; i++) {
            byte[] bytes = INPUTS[i].getBytes(StandardCharsets.ISO_8859_1);
            assertEquals(EXPECTED[i], Wyhash64.hash(SEEDS[i], bytes),
                    "hash(byte[]) vector " + i + " (seed " + SEEDS[i] + ")");
        }
    }

    @Test
    void zigTestVectors_singleShotString() {
        for (int i = 0; i < INPUTS.length; i++) {
            assertEquals(EXPECTED[i], Wyhash64.hash(SEEDS[i], INPUTS[i]),
                    "hash(String) vector " + i + " (seed " + SEEDS[i] + ")");
        }
    }

    @Test
    void zigTestVectors_singleShotChars() {
        for (int i = 0; i < INPUTS.length; i++) {
            assertEquals(EXPECTED[i], Wyhash64.hash(SEEDS[i], INPUTS[i].toCharArray()),
                    "hash(char[]) vector " + i + " (seed " + SEEDS[i] + ")");
        }
    }

    @Test
    void zigTestVectors_streaming() {
        for (int i = 0; i < INPUTS.length; i++) {
            byte[] bytes = INPUTS[i].getBytes(StandardCharsets.ISO_8859_1);
            Wyhash64.Streaming st = new Wyhash64.Streaming(SEEDS[i]);
            st.update(bytes);
            assertEquals(EXPECTED[i], st.finalHash(),
                    "streaming vector " + i + " (seed " + SEEDS[i] + ")");
        }
    }

    // ==========================================================================
    //  Zig "smhasher" — SMHasher-style verification code
    // ==========================================================================

    /**
     * Port of Zig's {@code verify.smhasher}: hash prefixes {0}, {0,1}, {0,1,2},
     * ... up to 255 bytes with seed 256-N, concatenate the little-endian
     * 64-bit results, hash the 2048-byte blob with seed 0, and take the low
     * 32 bits. Expected verification code: 0xBD5E840C.
     */
    @Test
    void zigSmhasherVerificationCode() {
        byte[] buf = new byte[256];
        byte[] bufAll = new byte[256 * 8];
        for (int i = 0; i < 256; i++) {
            buf[i] = (byte) i;
            long h = Wyhash64.hash(256 - i, buf, 0, i);
            for (int k = 0; k < 8; k++) {
                bufAll[i * 8 + k] = (byte) (h >>> (8 * k));
            }
        }
        long ver = Wyhash64.hash(0, bufAll);
        assertEquals(0xBD5E840CL, ver & 0xFFFFFFFFL, "SMHasher verification code");
    }

    // ==========================================================================
    //  Zig "iterative api" — incremental updates stay idempotent and match
    //  the single-shot hash at every cumulative length (1, 3, 6, 10, ... 496)
    // ==========================================================================

    @Test
    void zigIterativeApi() {
        // Sum(1..31) = 496; the verifier feeds cumulative prefixes of a
        // zero-filled 528-byte buffer.
        byte[] buf = new byte[528];
        int len = 0;
        long seed = 0;

        Wyhash64.Streaming hasher = new Wyhash64.Streaming(seed);
        for (int i = 1; i < 32; i++) {
            long r = Wyhash64.hash(seed, buf, 0, len + i);
            hasher.update(buf, len, i);
            long f1 = hasher.finalHash();
            long f2 = hasher.finalHash();
            assertEquals(f1, f2, "iterative hash must be idempotent at step " + i);
            assertEquals(r, f1, "iterative hash must match direct hash at step " + i);
            len += i;
        }
    }

    // ==========================================================================
    //  Zig "iterative maintains last sixteen" — a full 48-byte block followed
    //  by every tail length 1..17 must hash identically to the single-shot
    // ==========================================================================

    @Test
    void zigIterativeMaintainsLastSixteen() {
        // Zig: input = "Z" ** 48 ++ "01234567890abcdefg" (66 bytes), payload
        // lengths 66..50, i.e. a full 48-byte block followed by every tail
        // length 2..18.
        byte[] tail = "01234567890abcdefg".getBytes(StandardCharsets.ISO_8859_1);
        byte[] input = new byte[48 + tail.length];
        Arrays.fill(input, 0, 48, (byte) 'Z');
        System.arraycopy(tail, 0, input, 48, tail.length);

        long seed = 0;
        for (int i = 0; i < 17; i++) {
            byte[] payload = Arrays.copyOf(input, input.length - i);
            long nonIterative = Wyhash64.hash(seed, payload);

            Wyhash64.Streaming wh = new Wyhash64.Streaming(seed);
            wh.update(payload);
            assertEquals(nonIterative, wh.finalHash(),
                    "iterative must maintain last sixteen for length " + payload.length);
        }
    }
}
