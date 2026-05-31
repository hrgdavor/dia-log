package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Wyhash64}.
 */
class Wyhash64Test {

    // ---- Basic hash correctness ----

    @Test
    void hash_emptyInput_returnsConsistentValue() {
        byte[] empty = new byte[0];
        long h1 = Wyhash64.hash(0, empty);
        long h2 = Wyhash64.hash(0, empty);
        assertEquals(h1, h2, "Same input should produce same hash");
    }

    @Test
    void hash_nonEmptyInput_returnsNonZero() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        long hash = Wyhash64.hash(0, data);
        assertNotEquals(0, hash, "Hash of non-empty input should be non-zero");
    }

    @Test
    void hash_differentInputs_produceDifferentHashes() {
        byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] b = "world".getBytes(StandardCharsets.UTF_8);
        long h1 = Wyhash64.hash(0, a);
        long h2 = Wyhash64.hash(0, b);
        assertNotEquals(h1, h2, "Different inputs should produce different hashes");
    }

    // ---- Determinism ----

    @Test
    void hash_deterministic_sameInputSameOutput() {
        byte[] data = "deterministic test".getBytes(StandardCharsets.UTF_8);
        long h1 = Wyhash64.hash(42, data);
        long h2 = Wyhash64.hash(42, data);
        assertEquals(h1, h2);
    }

    // ---- Seed sensitivity ----

    @Test
    void hash_differentSeeds_produceDifferentHashes() {
        byte[] data = "same data".getBytes(StandardCharsets.UTF_8);
        long h1 = Wyhash64.hash(0, data);
        long h2 = Wyhash64.hash(1, data);
        assertNotEquals(h1, h2, "Different seeds should produce different hashes");
    }

    // ---- Offset and length ----

    @Test
    void hash_withOffsetAndLength() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        // Hash "world" (offset 6, length 5)
        long h1 = Wyhash64.hash(0, data, 6, 5);
        // Hash "world" directly
        long h2 = Wyhash64.hash(0, "world".getBytes(StandardCharsets.UTF_8));
        assertEquals(h1, h2, "Offset+length should match direct hash of the same substring");
    }

    @Test
    void hash_differentLengthsDifferentHashes() {
        byte[] data = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        long h1 = Wyhash64.hash(0, data, 0, 3);
        long h2 = Wyhash64.hash(0, data, 0, 7);
        assertNotEquals(h1, h2, "Different lengths should produce different hashes");
    }

    // ---- ByteBuffer overload ----

    @Test
    void hash_byteBuffer_matchesByteArray() {
        byte[] data = "bytebuffer test".getBytes(StandardCharsets.UTF_8);
        long h1 = Wyhash64.hash(0, data);

        ByteBuffer bb = ByteBuffer.wrap(data);
        long h2 = Wyhash64.hash(0, bb);

        assertEquals(h1, h2, "ByteBuffer and byte[] should produce same hash");
    }

    @Test
    void hash_byteBuffer_withOffsetAndLength() {
        byte[] data = "bytebuffer offset".getBytes(StandardCharsets.UTF_8);
        long h1 = Wyhash64.hash(0, data, 6, 8); // "buffer o"

        ByteBuffer bb = ByteBuffer.wrap(data);
        long h2 = Wyhash64.hash(0, bb, 6, 8);

        assertEquals(h1, h2, "ByteBuffer with offset should match byte[] with offset");
    }

    // ---- Streaming hash ----

    @Test
    void streaming_matchesBatchHash() {
        byte[] data = "streaming hash test data".getBytes(StandardCharsets.UTF_8);
        long batchHash = Wyhash64.hash(0, data);

        Wyhash64.Streaming streaming = new Wyhash64.Streaming(0);
        streaming.update(data);
        long streamHash = streaming.finalHash();

        assertEquals(batchHash, streamHash, "Streaming hash should match batch hash");
    }

    @Test
    void streaming_matchesBatchHash_chunked() {
        byte[] data = "chunked streaming hash test data for verification".getBytes(StandardCharsets.UTF_8);
        long batchHash = Wyhash64.hash(0, data);

        Wyhash64.Streaming streaming = new Wyhash64.Streaming(0);
        // Feed in chunks of varying sizes
        streaming.update(data, 0, 5);
        streaming.update(data, 5, 10);
        streaming.update(data, 15, data.length - 15);
        long streamHash = streaming.finalHash();

        assertEquals(batchHash, streamHash, "Chunked streaming should match batch hash");
    }

    @Test
    void streaming_byteByByte_matchesBatchHash() {
        byte[] data = "byte by byte streaming test".getBytes(StandardCharsets.UTF_8);
        long batchHash = Wyhash64.hash(0, data);

        Wyhash64.Streaming streaming = new Wyhash64.Streaming(0);
        for (byte b : data) {
            streaming.update(new byte[]{b});
        }
        long streamHash = streaming.finalHash();

        assertEquals(batchHash, streamHash, "Byte-by-byte streaming should match batch hash");
    }

    @Test
    void streaming_empty_matchesBatchHash() {
        long batchHash = Wyhash64.hash(0, new byte[0]);

        Wyhash64.Streaming streaming = new Wyhash64.Streaming(0);
        long streamHash = streaming.finalHash();

        assertEquals(batchHash, streamHash, "Empty streaming should match empty batch hash");
    }

    // ---- Practical use case: stack trace dedup ----

    @Test
    void hash_fingerprint_sameFrames_sameHash() {
        String fp1 = "com.example.MyClass.doStuff|com.example.Main.main";
        String fp2 = "com.example.MyClass.doStuff|com.example.Main.main";

        long h1 = Wyhash64.hash(0, fp1.getBytes(StandardCharsets.UTF_8));
        long h2 = Wyhash64.hash(0, fp2.getBytes(StandardCharsets.UTF_8));

        assertEquals(h1, h2, "Same fingerprint should produce same hash");
    }

    @Test
    void hash_fingerprint_differentFrames_differentHash() {
        String fp1 = "com.example.MyClass.doStuff|com.example.Main.main";
        String fp2 = "com.example.OtherClass.doStuff|com.example.Main.main";

        long h1 = Wyhash64.hash(0, fp1.getBytes(StandardCharsets.UTF_8));
        long h2 = Wyhash64.hash(0, fp2.getBytes(StandardCharsets.UTF_8));

        assertNotEquals(h1, h2, "Different fingerprints should produce different hashes");
    }

    // ---- Edge cases ----

    @Test
    void hash_singleByte() {
        byte[] single = new byte[]{42};
        long h = Wyhash64.hash(0, single);
        assertNotEquals(0, h);
    }

    @Test
    void hash_exactly16Bytes() {
        byte[] data = "1234567890123456".getBytes(StandardCharsets.UTF_8);
        assertEquals(16, data.length);
        long h = Wyhash64.hash(0, data);
        assertNotEquals(0, h);
    }

    @Test
    void hash_exactly48Bytes() {
        byte[] data = "123456789012345612345678901234561234567890123456".getBytes(StandardCharsets.UTF_8);
        assertEquals(48, data.length);
        long h = Wyhash64.hash(0, data);
        assertNotEquals(0, h);
    }

    @Test
    void hash_largeInput() {
        byte[] data = new byte[1024];
        java.util.Arrays.fill(data, (byte) 0xAB);
        long h = Wyhash64.hash(0, data);
        assertNotEquals(0, h);
    }
}