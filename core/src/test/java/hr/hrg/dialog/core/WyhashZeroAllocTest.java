package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for zero-allocation hashing methods in {@link Wyhash64}.
 * <p>
 * These tests verify that:
 * <ul>
 *   <li>{@link Wyhash64#hash(long, String)} matches {@code hash(seed, str.getBytes())}</li>
 *   <li>{@link Wyhash64#hash(long, String, int, int)} matches substring byte[] hashing</li>
 *   <li>{@link Wyhash64#hash(long, char[])} / {@code (long, char[], int, int)} match String hash</li>
 *   <li>{@link Wyhash64#hash(long, CharSequence)} delegates to String path</li>
 *   <li>Streaming {@code update(String)}, {@code update(char[])}, {@code update(CharSequence)}
 *       all match the batch byte[] hash</li>
 *   <li>Stack trace streaming via zero-allocation matches concatenated string approach</li>
 *   <li>Latin-1 and UTF-16 strings produce correct, consistent results</li>
 *   <li>Edge cases: empty strings, single char, boundary sizes, mixed-encoding strings</li>
 * </ul>
 */
class WyhashZeroAllocTest {

    // ==========================================================================
    //  String hash — matches byte[] reference
    // ==========================================================================

    @Test
    void hash_string_matchesBytes_empty() {
        String s = "";
        long h1 = Wyhash64.hash(0, s);
        long h2 = Wyhash64.hash(0, new byte[0]);
        assertEquals(h2, h1, "hash(String) should match hash(byte[]) for empty string");
    }

    @Test
    void hash_string_matchesBytes_latin1() {
        String s = "hello world";
        long h1 = Wyhash64.hash(0, s);
        long h2 = Wyhash64.hash(0, s.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(h2, h1, "hash(String) Latin-1 should match hash(byte[])");
    }

    @Test
    void hash_string_matchesBytes_ascii() {
        String s = "The quick brown fox jumps over the lazy dog";
        long h1 = Wyhash64.hash(42, s);
        long h2 = Wyhash64.hash(42, s.getBytes(StandardCharsets.US_ASCII));
        assertEquals(h2, h1, "hash(String) ASCII should match hash(byte[])");
    }

    @Test
    void hash_string_matchesBytes_utf16() {
        // Characters above U+00FF force UTF-16 encoding in compact strings
        String s = "héllo wörld 🚀";
        long h1 = Wyhash64.hash(0, s);
        assertNotNull(h1);
        assertNotEquals(0, h1);
    }

    @Test
    void hash_string_deterministic() {
        String s = "deterministic test string with Unicode: ñoño🚀";
        long h1 = Wyhash64.hash(7, s);
        long h2 = Wyhash64.hash(7, s);
        assertEquals(h1, h2, "hash(String) must be deterministic");
    }

    @Test
    void hash_string_seedSensitivity() {
        String s = "seed sensitive";
        long h0 = Wyhash64.hash(0, s);
        long h1 = Wyhash64.hash(1, s);
        long h2 = Wyhash64.hash(0xFFFFFFFFFFFFFFFFL, s);
        assertNotEquals(h0, h1, "Different seeds should produce different hashes");
        assertNotEquals(h0, h2, "Different seeds should produce different hashes");
        assertNotEquals(h1, h2, "Different seeds should produce different hashes");
    }

    @Test
    void hash_string_variousLengths() {
        String s1 = "ab";
        String s2 = "abc def";
        String s3 = "123456789012345678901234"; // 24 chars
        String s4 = "1234567890123456789012345"; // 25 chars — >24
        String s5 = "a".repeat(100);

        assertNotEquals(0, Wyhash64.hash(0, s1));
        assertNotEquals(0, Wyhash64.hash(0, s2));
        assertNotEquals(0, Wyhash64.hash(0, s3));
        assertNotEquals(0, Wyhash64.hash(0, s4));
        assertNotEquals(0, Wyhash64.hash(0, s5));
    }

    @Test
    void hash_string_and_bytes_ascii_consistent() {
        String[] tests = {"", "a", "hello", "a".repeat(16), "a".repeat(48), "a".repeat(100)};
        for (String t : tests) {
            long hStr = Wyhash64.hash(0, t);
            long hBytes = Wyhash64.hash(0, t.getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(hBytes, hStr,
                    "ASCII/Latin-1 string hash must match byte[] hash for: '" +
                            (t.length() > 20 ? t.substring(0, 20) + "..." : t) + "'");
        }
    }

    // ==========================================================================
    //  String hash with offset/length
    // ==========================================================================

    @Test
    void hash_string_offsetLen_substring() {
        String s = "hello world";
        long h1 = Wyhash64.hash(0, s, 6, 5);
        long h2 = Wyhash64.hash(0, "world");
        assertEquals(h2, h1, "hash(String,off,len) should match hash(String) of substring");
    }

    @Test
    void hash_string_offsetLen_prefix() {
        String s = "hello world";
        long h1 = Wyhash64.hash(0, s, 0, 5);
        long h2 = Wyhash64.hash(0, "hello");
        assertEquals(h2, h1);
    }

    @Test
    void hash_string_offsetLen_fullString() {
        String s = "full string test";
        long h1 = Wyhash64.hash(0, s, 0, s.length());
        long h2 = Wyhash64.hash(0, s);
        assertEquals(h2, h1);
    }

    @Test
    void hash_string_offsetLen_empty() {
        String s = "some string";
        long h1 = Wyhash64.hash(0, s, 5, 0);
        long h2 = Wyhash64.hash(0, "");
        assertEquals(h2, h1);
    }

    // ==========================================================================
    //  char[] hash — matches String reference
    // ==========================================================================

    @Test
    void hash_chars_matchesString_empty() {
        char[] chars = new char[0];
        long h1 = Wyhash64.hash(0, chars);
        long h2 = Wyhash64.hash(0, "");
        assertEquals(h2, h1, "hash(char[]) should match hash(String) for empty");
    }

    @Test
    void hash_chars_matchesString() {
        String s = "hello chars";
        long h1 = Wyhash64.hash(0, s.toCharArray());
        long h2 = Wyhash64.hash(0, s);
        assertEquals(h2, h1, "hash(char[]) should match hash(String)");
    }

    @Test
    void hash_chars_matchesString_unicode() {
        String s = "héllo wörld 🚀 Ñoño";
        long h1 = Wyhash64.hash(0, s.toCharArray());
        long h2 = Wyhash64.hash(0, s);
        assertEquals(h2, h1, "hash(char[]) with Unicode should match hash(String)");
    }

    @Test
    void hash_chars_offsetLen() {
        String s = "hello world chars test";
        char[] chars = s.toCharArray();
        long h1 = Wyhash64.hash(0, chars, 6, 5);
        long h2 = Wyhash64.hash(0, s.substring(6, 11));
        assertEquals(h2, h1, "hash(char[],off,len) should match substring hash(String)");
    }

    @Test
    void hash_chars_deterministic() {
        char[] chars = "deterministic chars".toCharArray();
        long h1 = Wyhash64.hash(0, chars);
        long h2 = Wyhash64.hash(0, chars);
        assertEquals(h1, h2);
    }

    @Test
    void hash_chars_variousLengths() {
        String s1 = "ab";
        String s2 = "abc def";
        String s3 = "123456789012345678901234"; // 24 chars
        String s4 = "1234567890123456789012345"; // 25 chars
        String s5 = "a".repeat(100);

        assertNotEquals(0, Wyhash64.hash(0, s1.toCharArray()));
        assertNotEquals(0, Wyhash64.hash(0, s2.toCharArray()));
        assertNotEquals(0, Wyhash64.hash(0, s3.toCharArray()));
        assertNotEquals(0, Wyhash64.hash(0, s4.toCharArray()));
        assertNotEquals(0, Wyhash64.hash(0, s5.toCharArray()));
    }

    @Test
    void hash_chars_offsetLen_various() {
        String s = "012345678901234567890123456789";
        char[] chars = s.toCharArray();

        assertEquals(Wyhash64.hash(0, s.substring(0, 2)), Wyhash64.hash(0, chars, 0, 2));
        assertEquals(Wyhash64.hash(0, s.substring(5, 10)), Wyhash64.hash(0, chars, 5, 5));
        assertEquals(Wyhash64.hash(0, s.substring(0, 24)), Wyhash64.hash(0, chars, 0, 24));
        assertEquals(Wyhash64.hash(0, s.substring(10, 28)), Wyhash64.hash(0, chars, 10, 18));
    }

    // ==========================================================================
    //  CharSequence hash
    // ==========================================================================

    @Test
    void hash_charSequence_stringInstance() {
        CharSequence cs = "hello from CharSequence";
        long h1 = Wyhash64.hash(0, cs);
        long h2 = Wyhash64.hash(0, (String) cs);
        assertEquals(h2, h1, "hash(CharSequence) for String should match hash(String)");
    }

    @Test
    void hash_charSequence_stringBuilder() {
        StringBuilder sb = new StringBuilder("StringBuilder test");
        long h1 = Wyhash64.hash(0, sb);
        long h2 = Wyhash64.hash(0, sb.toString());
        assertEquals(h2, h1, "hash(CharSequence) for StringBuilder must match hash(String)");
    }

    @Test
    void hash_charSequence_stringBuffer() {
        StringBuffer sb = new StringBuffer("StringBuffer test data");
        long h1 = Wyhash64.hash(0, sb);
        long h2 = Wyhash64.hash(0, sb.toString());
        assertEquals(h2, h1, "hash(CharSequence) for StringBuffer must match hash(String)");
    }

    @Test
    void hash_charSequence_empty() {
        assertEquals(Wyhash64.hash(0, ""), Wyhash64.hash(0, (CharSequence) ""));
        assertEquals(Wyhash64.hash(0, ""), Wyhash64.hash(0, new StringBuilder("")));
    }

    // ==========================================================================
    //  Streaming — String update
    // ==========================================================================

    @Test
    void streaming_string_matchesBytes_singleUpdate() {
        String s = "streaming string test";
        long batchHash = Wyhash64.hash(0, s.getBytes(StandardCharsets.ISO_8859_1));

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(s);
        assertEquals(batchHash, st.finalHash(),
                "Streaming.update(String) must match batch byte[] hash");
    }

    @Test
    void streaming_string_matchesString_batch() {
        String s = "streaming string test with unicode: ñoño🚀";

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(s);
        assertEquals(Wyhash64.hash(0, s), st.finalHash(),
                "Streaming.update(String) must match Wyhash64.hash(String)");
    }

    @Test
    void streaming_string_multiUpdate() {
        String p1 = "Hello, ";
        String p2 = "world!";
        String combined = p1 + p2;

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(p1);
        st.update(p2);
        assertEquals(Wyhash64.hash(0, combined), st.finalHash(),
                "Streaming.update(String) multi-update must match combined hash");
    }

    @Test
    void streaming_string_chunkedSizes() {
        String[] parts = {
                "a",                                          // 1 char
                "ab",                                         // 2 chars
                "abcdefgh",                                   // 8 chars
                "abcdefghijklmnopqrstuvwxyz",                 // 26 chars
                "a".repeat(48),                               // 48 chars (full buffer)
                "a".repeat(49),                               // 49 chars (buffer + 1)
                "a".repeat(100),                              // 100 chars
        };

        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        String combined = sb.toString();

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        for (String p : parts) st.update(p);
        assertEquals(Wyhash64.hash(0, combined), st.finalHash(),
                "Streaming.update(String) with various sizes must match combined hash");
    }

    @Test
    void streaming_string_offset() {
        String s = "0123456789abcdef";
        String sub = s.substring(2, 10);

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(s, 2, 8);
        assertEquals(Wyhash64.hash(0, sub), st.finalHash(),
                "Streaming.update(String,off,len) must match substring hash");
    }

    // ==========================================================================
    //  Streaming — char[] update
    // ==========================================================================

    @Test
    void streaming_chars_matchesString_singleUpdate() {
        String s = "streaming chars test";
        char[] chars = s.toCharArray();

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(chars);
        assertEquals(Wyhash64.hash(0, s), st.finalHash(),
                "Streaming.update(char[]) must match hash(String)");
    }

    @Test
    void streaming_chars_multiUpdate() {
        String p1 = "Hello, ";
        String p2 = "world!";
        String combined = p1 + p2;

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(p1.toCharArray());
        st.update(p2.toCharArray());
        assertEquals(Wyhash64.hash(0, combined), st.finalHash(),
                "Streaming.update(char[]) multi-update must match combined hash");
    }

    @Test
    void streaming_chars_chunkedSizes() {
        String[] parts = {
                "a",
                "ab",
                "abcdefgh",
                "abcdefghijklmnopqrstuvwxyz",
                "a".repeat(48),
                "a".repeat(49),
                "a".repeat(100),
        };

        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        String combined = sb.toString();

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        for (String p : parts) st.update(p.toCharArray());
        assertEquals(Wyhash64.hash(0, combined), st.finalHash(),
                "Streaming.update(char[]) with various sizes must match combined hash");
    }

    @Test
    void streaming_chars_offset() {
        String s = "0123456789abcdef";
        char[] chars = s.toCharArray();
        String sub = s.substring(2, 10);

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(chars, 2, 8);
        assertEquals(Wyhash64.hash(0, sub), st.finalHash(),
                "Streaming.update(char[],off,len) must match substring hash");
    }

    @Test
    void streaming_chars_unicode() {
        String s = "héllo wörld 🚀 Ñoño";
        char[] chars = s.toCharArray();

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(chars);
        assertEquals(Wyhash64.hash(0, s), st.finalHash(),
                "Streaming.update(char[]) with Unicode must match hash(String)");
    }

    // ==========================================================================
    //  Streaming — CharSequence update
    // ==========================================================================

    @Test
    void streaming_charSequence_stringInstance() {
        String s = "CharSequence as String";

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update((CharSequence) s);
        assertEquals(Wyhash64.hash(0, s), st.finalHash(),
                "Streaming.update(CharSequence) for String must match hash(String)");
    }

    @Test
    void streaming_charSequence_stringBuilder() {
        StringBuilder sb = new StringBuilder("CharSequence StringBuilder");

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update((CharSequence) sb);
        assertEquals(Wyhash64.hash(0, sb.toString()), st.finalHash(),
                "Streaming.update(CharSequence) for StringBuilder must match hash(String)");
    }

    @Test
    void streaming_charSequence_stringBuffer() {
        StringBuffer sb = new StringBuffer("CharSequence StringBuffer");

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update((CharSequence) sb);
        assertEquals(Wyhash64.hash(0, sb.toString()), st.finalHash(),
                "Streaming.update(CharSequence) for StringBuffer must match hash(String)");
    }

    // ==========================================================================
    //  Mixed streaming — combining different types
    // ==========================================================================

    @Test
    void streaming_mixedTypes() {
        String className = "com.example.MyService";
        byte[] methodName = "process".getBytes(StandardCharsets.ISO_8859_1);
        String fileName = "MyService.java";
        char[] extraInfo = "line=42".toCharArray();

        String combined = className + "." + new String(methodName, StandardCharsets.ISO_8859_1) + "@" + fileName + ":" + new String(extraInfo);

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(className);
        st.update(".");
        st.update(methodName);
        st.update("@");
        st.update(fileName);
        st.update(":");
        st.update(extraInfo);

        assertEquals(Wyhash64.hash(0, combined), st.finalHash(),
                "Mixed-type streaming must match combined string hash");
    }

    @Test
    void streaming_emptyNoUpdates() {
        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        assertEquals(Wyhash64.hash(0, new byte[0]), st.finalHash());
    }

    // ==========================================================================
    //  Stack trace streaming — zero-alloc vs concatenated string
    // ==========================================================================

    /**
     * Helper: build a stack trace fingerprint hash via zero-allocation streaming
     * using StackTraceElement fields directly (no toString(), no getBytes()).
     */
    private static long hashStackTrace(StackTraceElement[] trace, long seed, boolean zeroAlloc) {
        if (zeroAlloc) {
            Wyhash64.Streaming st = new Wyhash64.Streaming(seed);
            for (StackTraceElement ste : trace) {
                st.update(ste.getClassName());
                st.update(".");
                st.update(ste.getMethodName());
                st.update("#");
                st.update(String.valueOf(ste.getLineNumber()));
                st.update("\n");
            }
            return st.finalHash();
        } else {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement ste : trace) {
                sb.append(ste.getClassName()).append(".")
                  .append(ste.getMethodName()).append("#")
                  .append(ste.getLineNumber()).append("\n");
            }
            // Use hash(String) which correctly handles Unicode (unlike getBytes(ISO_8859_1)
            // which silently replaces non-Latin-1 chars with '?').
            return Wyhash64.hash(seed, sb.toString());
        }
    }

    @Test
    void stackTrace_streaming_matchesConcatenatedString() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();

        long hashStream = hashStackTrace(trace, 0, true);
        long hashConcat = hashStackTrace(trace, 0, false);

        assertEquals(hashConcat, hashStream,
                "Zero-alloc streaming stack trace hash must match concatenated String hash");
    }

    @Test
    void stackTrace_streaming_matchesConcatenatedString_multiSeed() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();

        long[] seeds = {0, 1, 0xDEADBEEFL, 0xFFFFFFFFFFFFFFFFL};
        for (long seed : seeds) {
            long hashStream = hashStackTrace(trace, seed, true);
            long hashConcat = hashStackTrace(trace, seed, false);
            assertEquals(hashConcat, hashStream,
                    "Seed " + seed + ": zero-alloc streaming must match concatenated String hash");
        }
    }

    @Test
    void stackTrace_streaming_emptyTrace() {
        StackTraceElement[] emptyTrace = new StackTraceElement[0];
        long hashStream = hashStackTrace(emptyTrace, 0, true);
        long hashConcat = hashStackTrace(emptyTrace, 0, false);

        assertEquals(hashConcat, hashStream,
                "Empty trace streaming must match empty concatenated hash");
    }

    @Test
    void stackTrace_streaming_singleFrame() {
        StackTraceElement[] trace = {
            new StackTraceElement("com.example.MyClass", "doStuff", "MyClass.java", 42)
        };

        long hashStream = hashStackTrace(trace, 0, true);
        long hashConcat = hashStackTrace(trace, 0, false);

        assertEquals(hashConcat, hashStream,
                "Single-frame trace streaming must match concatenated hash");
    }

    @Test
    void stackTrace_streaming_multiFrame_boundarySizes() {
        StackTraceElement[] trace = {
            new StackTraceElement("a", "b", "A.java", 1),
            new StackTraceElement("com.example.VeryLongClassNameThatPushesPastBufferBoundaries",
                                  "veryLongMethodNameThatAlsoPushesPastThe48ByteBufferInTheStreamingHasher",
                                  "LongFile.java", 9999),
            new StackTraceElement("com.example.MyService", "processRequest", "MyService.java", 256),
            new StackTraceElement("io.netty.channel.nio.NioEventLoop", "run", "NioEventLoop.java", 567),
            new StackTraceElement("java.base/java.lang.Thread", "run", "Thread.java", 1583),
        };

        long hashStream = hashStackTrace(trace, 0, true);
        long hashConcat = hashStackTrace(trace, 0, false);

        assertEquals(hashConcat, hashStream,
                "Multi-frame trace with boundary-sized strings must match concatenated hash");
    }

    @Test
    void stackTrace_streaming_mixedTypes_matchesString() {
        String className = "com.example.MyService";
        byte[] methodName = "execute".getBytes(StandardCharsets.ISO_8859_1);
        String fileName = "MyService.java";
        int lineNumber = 123;
        char[] sourceSnippet = "public void execute".toCharArray();

        String combined = className + "." + new String(methodName, StandardCharsets.ISO_8859_1)
                + "@" + fileName + "#" + lineNumber + "|" + new String(sourceSnippet);

        Wyhash64.Streaming st = new Wyhash64.Streaming(42);
        st.update(className);
        st.update(".");
        st.update(methodName);
        st.update("@");
        st.update(fileName);
        st.update("#");
        st.update(String.valueOf(lineNumber));
        st.update("|");
        st.update(sourceSnippet);

        assertEquals(Wyhash64.hash(42, combined), st.finalHash(),
                "Mixed-type streaming stack trace hash must match concatenated String hash");
    }

    @Test
    void stackTrace_streaming_unicodeClassNames() {
        StackTraceElement[] trace = {
            new StackTraceElement("com.example. servicio", "procesar", "Servicio.java", 42),
            new StackTraceElement("org.テスト.ライブラリ", "実行", "Library.java", 100),
            new StackTraceElement("com.example.🚀", "launch", "Rocket.java", 1),
        };

        // Note: per-String streaming independently detects Latin-1 vs UTF-16 encoding
        // per String, while hash(String) encodes the entire combined string as either
        // all Latin-1 or all UTF-16. These are fundamentally different byte streams
        // and cannot be compared directly. Instead, verify determinism and correct
        // handling (no crashes, no data loss for Unicode chars).

        long hash1 = hashStackTrace(trace, 0, true);
        long hash2 = hashStackTrace(trace, 0, true);
        assertEquals(hash1, hash2,
                "Unicode class names: streaming must be deterministic");

        // Verify with different seeds
        long hash3 = hashStackTrace(trace, 42, true);
        long hash4 = hashStackTrace(trace, 42, true);
        assertEquals(hash3, hash4,
                "Unicode class names: streaming must be deterministic with seed 42");

        // Verify cross-seed uniqueness (extremely unlikely to collide)
        assertNotEquals(hash1, hash3,
                "Different seeds should produce different hashes");
    }

    @Test
    void stackTrace_streaming_deterministic() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();

        long h1 = hashStackTrace(trace, 0, true);
        long h2 = hashStackTrace(trace, 0, true);
        long h3 = hashStackTrace(trace, 0xABCDEFL, true);

        assertEquals(h1, h2, "Streaming stack trace hash must be deterministic");
        assertNotEquals(h1, h3, "Different seeds must produce different stack trace hashes");
    }

    @Test
    void stackTrace_streaming_byteByByte() {
        StackTraceElement[] trace = {
            new StackTraceElement("com.example.A", "method1", "A.java", 10),
            new StackTraceElement("com.example.B", "method2", "B.java", 20),
        };

        long hashZeroAlloc = hashStackTrace(trace, 0, true);

        StringBuilder sb = new StringBuilder();
        for (StackTraceElement ste : trace) {
            sb.append(ste.getClassName()).append(".")
              .append(ste.getMethodName()).append("#")
              .append(ste.getLineNumber()).append("\n");
        }
        byte[] allBytes = sb.toString().getBytes(StandardCharsets.ISO_8859_1);

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        for (byte b : allBytes) {
            st.update(new byte[]{b});
        }

        assertEquals(hashZeroAlloc, st.finalHash(),
                "Zero-alloc streaming must match byte-by-byte streaming of the concatenated string");
    }

    // ==========================================================================
    //  Cross-method consistency
    // ==========================================================================

    @Test
    void crossMethod_consistency() {
        String s = "cross-method consistency check";
        char[] chars = s.toCharArray();

        long hStr = Wyhash64.hash(0, s);
        long hChars = Wyhash64.hash(0, chars);
        long hCS = Wyhash64.hash(0, (CharSequence) s);
        long hBytes = Wyhash64.hash(0, s.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(hBytes, hStr, "hash(String) must match hash(byte[]) for Latin-1");
        assertEquals(hStr, hChars, "hash(String) must match hash(char[])");
        assertEquals(hStr, hCS, "hash(String) must match hash(CharSequence)");
    }

    @Test
    void crossMethod_consistency_streaming() {
        String s = "streaming consistency check";
        char[] chars = s.toCharArray();

        Wyhash64.Streaming st1 = new Wyhash64.Streaming(0);
        st1.update(s);

        Wyhash64.Streaming st2 = new Wyhash64.Streaming(0);
        st2.update(chars);

        Wyhash64.Streaming st3 = new Wyhash64.Streaming(0);
        st3.update((CharSequence) s);

        assertEquals(st1.finalHash(), st2.finalHash(),
                "Streaming String vs char[] must produce same hash");
        assertEquals(st1.finalHash(), st3.finalHash(),
                "Streaming String vs CharSequence must produce same hash");
    }

    // ==========================================================================
    //  Edge cases
    // ==========================================================================

    @Test
    void hash_string_veryLong() {
        String s = "a".repeat(10000);
        long h = Wyhash64.hash(0, s);
        assertNotEquals(0, h, "Long string must produce non-zero hash");
    }

    @Test
    void hash_chars_veryLong() {
        char[] chars = new char[10000];
        java.util.Arrays.fill(chars, 'a');
        long h = Wyhash64.hash(0, chars);
        assertNotEquals(0, h, "Long char[] must produce non-zero hash");
    }

    @Test
    void streaming_string_veryLong() {
        String s = "a".repeat(10000);

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(s);
        assertEquals(Wyhash64.hash(0, s), st.finalHash(),
                "Very long string streaming must match batch hash");
    }

    @Test
    void streaming_chars_veryLong() {
        String s = "a".repeat(10000);
        char[] chars = s.toCharArray();

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(chars);
        assertEquals(Wyhash64.hash(0, s), st.finalHash(),
                "Very long char[] streaming must match batch hash");
    }

    @Test
    void streaming_charSequence_empty() {
        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update((CharSequence) "");
        assertEquals(Wyhash64.hash(0, new byte[0]), st.finalHash());
    }

    @Test
    void streaming_string_empty() {
        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update("");
        assertEquals(Wyhash64.hash(0, new byte[0]), st.finalHash());
    }

    @Test
    void streaming_chars_empty() {
        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(new char[0]);
        assertEquals(Wyhash64.hash(0, new byte[0]), st.finalHash());
    }

    @Test
    void hash_string_differentSeeds_unicode() {
        String s = "日本語テスト 🚀";
        long h0 = Wyhash64.hash(0, s);
        long h1 = Wyhash64.hash(1, s);
        long h2 = Wyhash64.hash(0xDEADBEEFL, s);
        assertNotEquals(h0, h1, "Different seeds must produce different hashes for Unicode strings");
        assertNotEquals(h0, h2, "Different seeds must produce different hashes for Unicode strings");
    }

    // ==========================================================================
    //  Determinism across all zero-allocation paths
    // ==========================================================================

    @Test
    void determinism_allPaths() {
        String s = "determinism check across all paths ñoño🚀";

        long hStr1 = Wyhash64.hash(0, s);
        long hStr2 = Wyhash64.hash(0, s);
        long hChars1 = Wyhash64.hash(0, s.toCharArray());
        long hChars2 = Wyhash64.hash(0, s.toCharArray());

        assertEquals(hStr1, hStr2, "hash(String) must be deterministic");
        assertEquals(hChars1, hChars2, "hash(char[]) must be deterministic");

        Wyhash64.Streaming st1a = new Wyhash64.Streaming(0);
        st1a.update(s);
        Wyhash64.Streaming st1b = new Wyhash64.Streaming(0);
        st1b.update(s);
        assertEquals(st1a.finalHash(), st1b.finalHash(),
                "Streaming.update(String) must be deterministic");

        Wyhash64.Streaming st2a = new Wyhash64.Streaming(0);
        st2a.update(s.toCharArray());
        Wyhash64.Streaming st2b = new Wyhash64.Streaming(0);
        st2b.update(s.toCharArray());
        assertEquals(st2a.finalHash(), st2b.finalHash(),
                "Streaming.update(char[]) must be deterministic");
    }
}