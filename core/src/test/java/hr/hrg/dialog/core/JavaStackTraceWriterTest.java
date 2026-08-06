package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JavaStackTraceWriter}.
 * <p>
 * Verifies that {@code JavaStackTraceWriter} produces the same output as
 * {@link JavaStackSanitizer} when the filter accepts all frames, since
 * {@code JavaStackTraceWriter} is {@code JavaStackSanitizer} with the filter
 * omitted and without fallback.
 */
class JavaStackTraceWriterTest {

    // ---- helpers ----

    /** Hash the given frames with JavaStackTraceWriter and return the hex string. */
    private static String hashFramesWriter(StackTraceElement[] trace) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        JavaStackTraceWriter.addFromTrace(trace, stream);
        return HexFormat.of().toHexDigits(stream.finalHash());
    }

    /** Hash the given frames with JavaStackSanitizer (accept-all filter) and return the hex string. */
    private static String hashFramesSanitizer(StackTraceElement[] trace) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        JavaStackSanitizer.addFromTrace(trace, cls -> true, stream);
        return HexFormat.of().toHexDigits(stream.finalHash());
    }

    /** Build the sanitized string with JavaStackTraceWriter and hash it. */
    private static String hashFromStringWriter(StackTraceElement[] trace) {
        StringBuffer sb = new StringBuffer();
        JavaStackTraceWriter.addFromTraceToStringBuffer(trace, sb);
        return HexFormat.of().toHexDigits(Wyhash64.hash(0, sb.toString()));
    }

    /** Build the sanitized string with JavaStackSanitizer (accept-all filter) and hash it. */
    private static String hashFromStringSanitizer(StackTraceElement[] trace) {
        StringBuffer sb = new StringBuffer();
        JavaStackSanitizer.addFromTraceToStringBuffer(trace, cls -> true, sb);
        return HexFormat.of().toHexDigits(Wyhash64.hash(0, sb.toString()));
    }

    /** Get the string output from JavaStackTraceWriter. */
    private static String stringFromWriter(StackTraceElement[] trace) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            JavaStackTraceWriter.addFromTraceToOutputStream(trace, baos);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Get the string output from JavaStackSanitizer (accept-all filter). */
    private static String stringFromSanitizer(StackTraceElement[] trace) {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            JavaStackSanitizer.addFromTraceToOutputStream(trace, cls -> true, baos);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Create a simple stack-frame element without a file/line number. */
    private static StackTraceElement ste(String className, String methodName) {
        return new StackTraceElement(className, methodName, null, -1);
    }

    /** Create a stack-frame element with file and line number (line is ignored by the sanitizer). */
    private static StackTraceElement ste(String className, String methodName, String file, int line) {
        return new StackTraceElement(className, methodName, file, line);
    }

    // ---- streaming hash: writer vs sanitizer (accept-all) ----

    @Test
    void addFromTrace_matchesSanitizer_whenFilterAcceptsAll() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.Other", "method2"),
        };
        assertEquals(hashFramesSanitizer(frames), hashFramesWriter(frames),
                "JavaStackTraceWriter should produce same hash as JavaStackSanitizer with accept-all filter");
    }

    @Test
    void addFromTrace_matchesSanitizer_withLambdaClass() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass$$Lambda$123/0x00007f8b12345678", "run"),
        };
        assertEquals(hashFramesSanitizer(frames), hashFramesWriter(frames),
                "Lambda class normalisation should match between writer and sanitizer");
    }

    @Test
    void addFromTrace_matchesSanitizer_withLambdaDollarMethod() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "lambda$originalMethod$42"),
        };
        assertEquals(hashFramesSanitizer(frames), hashFramesWriter(frames),
                "lambda$ method name extraction should match between writer and sanitizer");
    }

    @Test
    void addFromTrace_matchesSanitizer_mixedFrames() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "doStuff"),
                ste("com.example.MyClass$$Lambda$42/0xdeadbeef", "run"),
                ste("com.example.MyClass", "lambda$doOtherStuff$99"),
        };
        assertEquals(hashFramesSanitizer(frames), hashFramesWriter(frames),
                "Mixed frame types should produce same hash between writer and sanitizer");
    }

    @Test
    void addFromTrace_emptyTrace_matchesSanitizer() {
        StackTraceElement[] frames = new StackTraceElement[0];
        assertEquals(hashFramesSanitizer(frames), hashFramesWriter(frames),
                "Empty trace should produce same hash between writer and sanitizer");
    }

    @Test
    void addFromTrace_singleFrame_matchesSanitizer() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
        };
        assertEquals(hashFramesSanitizer(frames), hashFramesWriter(frames),
                "Single frame should produce same hash between writer and sanitizer");
    }

    // ---- string buffer: writer vs sanitizer (accept-all) ----

    @Test
    void addFromTraceToStringBuffer_matchesSanitizer_whenFilterAcceptsAll() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.Other", "method2"),
        };
        assertEquals(hashFromStringSanitizer(frames), hashFromStringWriter(frames),
                "JavaStackTraceWriter string buffer output should match JavaStackSanitizer with accept-all filter");
    }

    @Test
    void addFromTraceToStringBuffer_matchesSanitizer_withLambdaClass() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass$$Lambda$123/0xabc", "run"),
        };
        assertEquals(hashFromStringSanitizer(frames), hashFromStringWriter(frames),
                "Lambda class normalisation in string buffer should match");
    }

    @Test
    void addFromTraceToStringBuffer_matchesSanitizer_withLambdaDollarMethod() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "lambda$originalMethod$42"),
        };
        assertEquals(hashFromStringSanitizer(frames), hashFromStringWriter(frames),
                "lambda$ method name extraction in string buffer should match");
    }

    // ---- output stream: writer vs sanitizer (accept-all) ----

    @Test
    void addFromTraceToOutputStream_matchesSanitizer_whenFilterAcceptsAll() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.Other", "method2"),
        };
        assertEquals(stringFromSanitizer(frames), stringFromWriter(frames),
                "JavaStackTraceWriter output stream should match JavaStackSanitizer with accept-all filter");
    }

    @Test
    void addFromTraceToOutputStreamJson_matchesSanitizer_whenFilterAcceptsAll() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.Other", "method2"),
        };
        String writerJson = stringFromWriter(frames).replace("\n", "\\n");
        String sanitizerJson = stringFromSanitizer(frames).replace("\n", "\\n");
        // For JSON output, both should use \\n as newline separator
        assertEquals(sanitizerJson, writerJson,
                "JavaStackTraceWriter JSON output stream should match JavaStackSanitizer with accept-all filter");
    }

    @Test
    void addFromTraceToOutputStream_matchesSanitizer_withLambdaClass() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass$$Lambda$123/0xabc", "run"),
        };
        assertEquals(stringFromSanitizer(frames), stringFromWriter(frames),
                "Lambda class normalisation in output stream should match");
    }

    @Test
    void addFromTraceToOutputStream_matchesSanitizer_withLambdaDollarMethod() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "lambda$originalMethod$42"),
        };
        assertEquals(stringFromSanitizer(frames), stringFromWriter(frames),
                "lambda$ method name extraction in output stream should match");
    }

    // ---- determinism ----

    @Test
    void addFromTrace_deterministic_sameInputSameHash() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.MyClass", "method2"),
        };
        String h1 = hashFramesWriter(frames);
        String h2 = hashFramesWriter(frames);
        assertEquals(h1, h2, "JavaStackTraceWriter hash should be deterministic");
    }

    @Test
    void addFromTraceToStringBuffer_deterministic_sameInputSameHash() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.MyClass", "method2"),
        };
        String h1 = hashFromStringWriter(frames);
        String h2 = hashFromStringWriter(frames);
        assertEquals(h1, h2, "JavaStackTraceWriter string buffer hash should be deterministic");
    }

    // ---- frame order matters ----

    @Test
    void addFromTrace_frameOrderMatters() {
        StackTraceElement[] order1 = {
                ste("com.example.A", "m1"),
                ste("com.example.B", "m2"),
        };
        StackTraceElement[] order2 = {
                ste("com.example.B", "m2"),
                ste("com.example.A", "m1"),
        };
        assertNotEquals(hashFramesWriter(order1), hashFramesWriter(order2),
                "Frame order should affect the hash");
    }

    // ---- public constants ----

    @Test
    void constants_areNotEmpty() {
        assertTrue(JavaStackTraceWriter.DOT_BYTES.length > 0);
        assertTrue(JavaStackTraceWriter.NEWLINE_BYTES.length > 0);
        assertTrue(JavaStackTraceWriter.LAMBDA_METHOD_BYTES.length > 0);
    }
}
