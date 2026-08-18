package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the less-frequently exercised public entry points of
 * {@link JavaStackSanitizer}: {@code fingerprintFromTrace}, the JSON output
 * variants (escaped {@code \\n} separators), and the fallback path when every
 * frame is filtered out.
 */
class JavaStackSanitizerExtrasTest {

    private static StackTraceElement ste(String className, String methodName) {
        return new StackTraceElement(className, methodName, null, -1);
    }

    private static final Predicate<String> ACCEPT_ALL = cls -> true;
    private static final Predicate<String> REJECT_ALL = cls -> false;

    private static final StackTraceElement[] TRACE = {
            ste("com.example.Service", "lambda$handle$0"),
            ste("com.example.Main", "run"),
            ste("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0"),
    };

    /** Caller-owned reusable hasher, as the API requires (no convenience overloads). */
    private final Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

    @Test
    void fingerprintFromTrace_matchesFingerprintOfThrowable() {
        Exception ex = new Exception();
        long fromTrace = JavaStackSanitizer.fingerprintFromTrace(ex.getStackTrace(), ACCEPT_ALL, ex.getClass().getName(), stream);
        long fromThrowable = JavaStackSanitizer.fingerprint(ex, ACCEPT_ALL, stream);
        assertEquals(fromThrowable, fromTrace);
    }

    @Test
    void fingerprintFromTrace_nullClassName_doesNotThrow() {
        long h = JavaStackSanitizer.fingerprintFromTrace(TRACE, ACCEPT_ALL, null, stream);
        assertNotEquals(0L, h);
    }

    @Test
    void fingerprintFromTrace_reusableStream() {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        long h1 = JavaStackSanitizer.fingerprintFromTrace(TRACE, ACCEPT_ALL, "x", stream);
        long h2 = JavaStackSanitizer.fingerprintFromTrace(TRACE, ACCEPT_ALL, "x", stream);
        assertEquals(h1, h2, "reusable stream must be reset per call");
    }

    @Test
    void jsonOutput_usesEscapedNewlines() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JavaStackSanitizer.addFromTraceToOutputStreamJson(TRACE, ACCEPT_ALL, out);
        String json = out.toString(StandardCharsets.UTF_8);

        assertTrue(json.startsWith("\\ncom.example.Service"), "JSON output must start with escaped newline: " + json);
        assertTrue(json.contains("\\ncom.example.Main.run"), "frames joined by escaped newlines: " + json);
        // lambda method normalization: lambda$handle$0 -> handle
        assertTrue(json.contains("Service.handle"), "lambda method must be normalized: " + json);
        assertFalse(json.contains("\n"), "no raw newlines allowed in JSON output: " + json);
    }

    @Test
    void jsonOutputAndFingerprint_singlePass() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long hash = JavaStackSanitizer.addFromTraceToOutputStreamJsonAndFingerprint(
                TRACE, ACCEPT_ALL, out, "com.example.Ex", stream);

        String json = out.toString(StandardCharsets.UTF_8);
        assertEquals("com.example.Ex" + json, jsonWithHeader());

        // single-pass hash must equal fingerprintFromTrace over the same payload
        long expected = JavaStackSanitizer.fingerprintFromTrace(TRACE, ACCEPT_ALL, "com.example.Ex", stream);
        assertEquals(expected, hash);
    }

    private static String jsonWithHeader() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("com.example.Ex".getBytes(StandardCharsets.UTF_8));
        JavaStackSanitizer.addFromTraceToOutputStreamJson(TRACE, ACCEPT_ALL, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void allFramesFiltered_fallsBackToRawTop3() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JavaStackSanitizer.addFromTraceToOutputStream(TRACE, REJECT_ALL, out);
        String s = out.toString(StandardCharsets.UTF_8);

        assertTrue(s.contains("com.example.Service"), "fallback must write raw class names: " + s);
        assertTrue(s.contains("lambda$handle$0"), "fallback keeps raw method names: " + s);
        assertTrue(s.contains("jdk.internal.reflect.NativeMethodAccessorImpl"), "fallback includes up to 3 raw frames: " + s);
    }

    @Test
    void fingerprint_emptyTrace_withFilter() {
        long h = JavaStackSanitizer.fingerprintFromTrace(new StackTraceElement[0], REJECT_ALL, "clazz", stream);
        assertNotEquals(0L, h);
    }
}
