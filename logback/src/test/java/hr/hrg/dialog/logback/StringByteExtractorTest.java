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
}