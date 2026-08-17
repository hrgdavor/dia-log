package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for {@link Wyhash64}: empty input, single byte/char, large input,
 * seed 0 vs non-zero, and cross-input-type consistency.
 * (Planned coverage item from plans/analysis-report.md §11.)
 */
class Wyhash64EdgeCaseTest {

    @Test
    void emptyByteArray_isDeterministic() {
        long h1 = Wyhash64.hash(0, new byte[0]);
        long h2 = Wyhash64.hash(0, new byte[0]);
        assertEquals(h1, h2);
    }

    @Test
    void emptyInput_agreesAcrossTypes() {
        long fromString = Wyhash64.hash(0, "");
        assertEquals(fromString, Wyhash64.hash(0, new byte[0]));
        assertEquals(fromString, Wyhash64.hash(0, new char[0]));
    }

    @Test
    void singleByte_isDeterministic() {
        byte[] data = {42};
        long h1 = Wyhash64.hash(0, data);
        long h2 = Wyhash64.hash(0, data);
        assertEquals(h1, h2);
    }

    @Test
    void singleLatin1Char_matchesSingleByte() {
        assertEquals(
                Wyhash64.hash(0, new byte[]{'x'}),
                Wyhash64.hash(0, new char[]{'x'}));
    }

    @Test
    void seedZero_vsNonZeroSeed_differ() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(Wyhash64.hash(0, data), Wyhash64.hash(1, data));
    }

    @Test
    void largeInput_isDeterministic() {
        byte[] big = new byte[1_000_000];
        new Random(42).nextBytes(big);

        long h1 = Wyhash64.hash(0, big);
        long h2 = Wyhash64.hash(0, big);
        assertEquals(h1, h2, "large input must hash deterministically");
    }

    @Test
    void largeLatin1String_matchesBytesAndChars() {
        StringBuilder sb = new StringBuilder(100_000);
        Random rnd = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        String s = sb.toString();

        long fromString = Wyhash64.hash(0, s);
        assertEquals(fromString, Wyhash64.hash(0, s.toCharArray()));
        assertEquals(fromString, Wyhash64.hash(0, s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void unicodeString_matchesCharArray() {
        String s = "héllo wörld 🚀 Ñoño";
        assertEquals(Wyhash64.hash(0, s), Wyhash64.hash(0, s.toCharArray()));
    }

    @Test
    void byteBuffer_matchesByteArray() {
        byte[] data = "buffer test".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                Wyhash64.hash(0, data),
                Wyhash64.hash(0, ByteBuffer.wrap(data)));
    }
}
