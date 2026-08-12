package hr.hrg.dialog.logback;

import hr.hrg.dialog.core.StringByteExtractor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StringByteExtractorTest {

    private static VarHandle valueHandle;
    private static VarHandle coderHandle;

    @BeforeAll
    static void setupVarHandles() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
            valueHandle = lookup.findVarHandle(String.class, "value", byte[].class);
            coderHandle = lookup.findVarHandle(String.class, "coder", byte.class);
        } catch (Throwable e) {
            // VarHandle reflective access failed (--add-opens not specified)
            valueHandle = null;
            coderHandle = null;
        }
    }

    @Test
    @DisplayName("Verify static initialization correctly detects --add-opens status")
    void testAddOpensDetection() {
        boolean expectedStatus = (valueHandle != null && coderHandle != null);
        assertEquals(expectedStatus, StringByteExtractor.isVarHandleSupported());
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "com.example.service.UserService",
            "main",
            "UserService.java:42",
            "\n\tat com.example.Controller.handle(Controller.java:105)",
            "NullPointerException: Object was null",
            "A Very Long String With Special Identifiers $100_000 #Test!",
            "café",       // Latin-1 extended
            "🚀 Emoji"    // UTF-16 Multi-byte
    })
    @DisplayName("Verify VarHandle variant matches Classic variant byte-for-byte")
    void testVarHandleAndClassicParity(String input) throws IOException {
        // Skip test if --add-opens is not present in test runner
        assumeTrue(valueHandle != null, "Skipping VarHandle parity test: --add-opens flag missing");

        ByteArrayOutputStream classicStream = new ByteArrayOutputStream();
        ByteArrayOutputStream varHandleStream = new ByteArrayOutputStream();

        // 1. Write via Classic Strategy
        StringByteExtractor.writeClassic(classicStream, input);

        // 2. Write via VarHandle Strategy directly
        StringByteExtractor.writeVarHandle(varHandleStream, input, valueHandle, coderHandle);

        // 3. Assert Exact Match
        byte[] classicBytes = classicStream.toByteArray();
        byte[] varHandleBytes = varHandleStream.toByteArray();

        assertArrayEquals(classicBytes, varHandleBytes,
                () -> String.format("Byte output for string '%s' must match classic implementation", input));

        // 4. Assert UTF-8 Decoding Correctness
        assertEquals(input, new String(varHandleBytes, StandardCharsets.UTF_8),
                "The decoded UTF-8 string should match the original input");
    }

    @Test
    @DisplayName("Verify behavior on empty and null strings")
    void testEdgeCases() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        StringByteExtractor.writeAsciiDirect(out, "");
        assertEquals(0, out.toByteArray().length, "Empty string should produce 0 bytes");

        // Reset stream for the next check
        out.reset();

        StringByteExtractor.writeAsciiDirect(out, null);
        assertEquals(0, out.toByteArray().length, "Null string should produce 0 bytes");
    }

    // =========================================================================
    // Latin-1 (coder == 0) encoding verification
    // =========================================================================

    /**
     * Builds a Latin-1 string containing every code point 0x00-0xFF (excluding 0x00 which is a
     * NUL and would be awkward, and control chars that don't round-trip cleanly in a test string).
     * Latin-1 chars in 0x80-0xFF exercise the single-pass Latin-1 -> UTF-8 expansion.
     */
    private static String allLatin1Range() {
        StringBuilder sb = new StringBuilder(256);
        for (int cp = 0x20; cp <= 0xFF; cp++) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("VarHandle Latin-1 encoding matches Java UTF-8 byte-for-byte over the full Latin-1 range")
    void testVarHandleLatin1_matchesJavaUtf8_fullRange() throws IOException {
        assumeTrue(valueHandle != null, "Skipping VarHandle test: --add-opens flag missing");

        String latin1 = allLatin1Range();
        // Confirm the test string is actually stored as Latin-1 (coder == 0) by the JVM.
        assumeTrue((byte) coderHandle.get(latin1) == 0,
                "Test string should be a Latin-1 compact string (coder==0)");

        // Java's canonical UTF-8 encoding (the reference).
        byte[] expected = latin1.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringByteExtractor.writeVarHandle(out, latin1, valueHandle, coderHandle);
        byte[] actual = out.toByteArray();

        assertArrayEquals(expected, actual,
                "VarHandle Latin-1 encoding must match Java UTF-8 for every Latin-1 code point");

        // Round-trip: decoding the bytes must reproduce the original string.
        assertEquals(latin1, new String(actual, StandardCharsets.UTF_8),
                "Decoded UTF-8 must round-trip to the original Latin-1 string");
    }

    @Test
    @DisplayName("VarHandle Latin-1 encoding matches Java UTF-8 for representative Latin-1 extended strings")
    void testVarHandleLatin1_matchesJavaUtf8_representative() throws IOException {
        assumeTrue(valueHandle != null, "Skipping VarHandle test: --add-opens flag missing");

        String[] latin1Strings = {
                "café",                  // U+00E9
                "naïve résumé",          // U+00EF, U+00E9
                "über",                  // U+00FC
                "café au lait à la mode",// U+00E9, U+00E0
                "ÆØÅ æøå",               // Latin-1 capitals + lowercase
                "¡Hola! ¿Cómo estás?",   // U+00A1, U+00BF, U+00F3
                "£100",                  // U+00A3 (pound sign is Latin-1)
                "°C ±5",                 // U+00B0, U+00B1
                "«quoted»",              // U+00AB, U+00BB
                "çüğışö",                // Turkish Latin-1 chars
        };

        for (String input : latin1Strings) {
            // Only test strings the JVM stores as Latin-1 (coder == 0).
            if ((byte) coderHandle.get(input) != 0) {
                continue;
            }
            byte[] expected = input.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StringByteExtractor.writeVarHandle(out, input, valueHandle, coderHandle);
            byte[] actual = out.toByteArray();

            assertArrayEquals(expected, actual,
                    () -> String.format("Latin-1 encoding for '%s' must match Java UTF-8", input));
            assertEquals(input, new String(actual, StandardCharsets.UTF_8),
                    () -> String.format("UTF-8 round-trip for '%s'", input));
        }
    }

    @Test
    @DisplayName("VarHandle Latin-1 encoding matches Java UTF-8 for every single Latin-1 extended char")
    void testVarHandleLatin1_matchesJavaUtf8_eachExtendedChar() throws IOException {
        assumeTrue(valueHandle != null, "Skipping VarHandle test: --add-opens flag missing");

        // Every Latin-1 code point in the extended range 0x80-0xFF must encode to the same
        // UTF-8 bytes as Java's standard encoder.
        for (int cp = 0x80; cp <= 0xFF; cp++) {
            final int codePoint = cp;
            String s = String.valueOf((char) cp);
            if ((byte) coderHandle.get(s) != 0) {
                continue; // paranoia: should always be Latin-1
            }
            byte[] expected = s.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StringByteExtractor.writeVarHandle(out, s, valueHandle, coderHandle);
            assertArrayEquals(expected, out.toByteArray(),
                    () -> String.format("Latin-1 char U+%04X must encode like Java UTF-8", codePoint));
        }
    }
}