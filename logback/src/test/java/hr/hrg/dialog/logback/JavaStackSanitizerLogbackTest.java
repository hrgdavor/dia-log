package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import hr.hrg.dialog.core.JavaStackSanitizer;
import hr.hrg.dialog.core.Wyhash64;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaStackSanitizerLogbackTest {

    private static final Predicate<String> ACCEPT_ALL = cls -> true;

    /** Caller-owned reusable hasher, as the API requires (no convenience overloads). */
    private final Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

    private static StackTraceElement ste(String className, String methodName) {
        return new StackTraceElement(className, methodName, null, -1);
    }

    private static Throwable sampleThrowable() {
        RuntimeException ex = new RuntimeException("boom");
        ex.setStackTrace(new StackTraceElement[]{
                ste("com.example.MyClass", "doStuff"),
                ste("com.example.MyClass$$Lambda$42/0xdeadbeef", "run"),
                ste("com.example.MyClass", "lambda$doOtherStuff$99")
        });
        return ex;
    }

    private static String hashCore(StackTraceElement[] trace, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        JavaStackSanitizer.addFromTrace(trace, filter, stream);
        return HexFormat.of().toHexDigits(stream.finalHash());
    }

    private static String hashLogback(IThrowableProxy proxy, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        JavaStackSanitizerLogback.addFromTrace(proxy.getStackTraceElementProxyArray(), filter, stream);
        return HexFormat.of().toHexDigits(stream.finalHash());
    }

    @Test
    void fingerprint_matchesCoreForSameThrowable() {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        long core = JavaStackSanitizer.fingerprint(throwable, ACCEPT_ALL, stream);
        long logback = JavaStackSanitizerLogback.fingerprint(proxy, ACCEPT_ALL, stream);

        assertEquals(core, logback);
    }

    @Test
    void addFromTrace_matchesCoreStreamingHash() {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        assertEquals(
                hashCore(throwable.getStackTrace(), ACCEPT_ALL),
                hashLogback(proxy, ACCEPT_ALL));
    }

    @Test
    void addFromTraceToStringBuffer_matchesCoreOutput() {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        StringBuffer core = new StringBuffer();
        JavaStackSanitizer.addFromTraceToStringBuffer(throwable.getStackTrace(), ACCEPT_ALL, core);

        StringBuffer logback = new StringBuffer();
        JavaStackSanitizerLogback.addFromTraceToStringBuffer(proxy.getStackTraceElementProxyArray(), ACCEPT_ALL, logback);

        assertEquals(core.toString(), logback.toString());
    }

    @Test
    void addFromTraceToOutputStream_matchesCoreOutput() throws IOException {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        ByteArrayOutputStream core = new ByteArrayOutputStream();
        JavaStackSanitizer.addFromTraceToOutputStream(throwable.getStackTrace(), ACCEPT_ALL, core);

        ByteArrayOutputStream logback = new ByteArrayOutputStream();
        JavaStackSanitizerLogback.addFromTraceToOutputStream(proxy.getStackTraceElementProxyArray(), ACCEPT_ALL, logback);

        assertEquals(core.toString(StandardCharsets.UTF_8), logback.toString(StandardCharsets.UTF_8));
    }

    @Test
    void addFromTraceToOutputStreamJson_matchesCoreOutput() throws IOException {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        ByteArrayOutputStream core = new ByteArrayOutputStream();
        JavaStackSanitizer.addFromTraceToOutputStreamJson(throwable.getStackTrace(), ACCEPT_ALL, core);

        ByteArrayOutputStream logback = new ByteArrayOutputStream();
        JavaStackSanitizerLogback.addFromTraceToOutputStreamJson(proxy.getStackTraceElementProxyArray(), ACCEPT_ALL, logback);

        assertEquals(core.toString(StandardCharsets.UTF_8), logback.toString(StandardCharsets.UTF_8));
    }

    @Test
    void singlePassJsonWriteAndFingerprint_matchesTwoPass() throws IOException {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        ByteArrayOutputStream twoPassOut = new ByteArrayOutputStream();
        JavaStackSanitizerLogback.addFromTraceToOutputStreamJson(
                proxy.getStackTraceElementProxyArray(),
                ACCEPT_ALL,
                twoPassOut
        );
        long twoPassHash = JavaStackSanitizerLogback.fingerprint(proxy, ACCEPT_ALL, stream);

        ByteArrayOutputStream singlePassOut = new ByteArrayOutputStream();
        long singlePassHash = JavaStackSanitizerLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                proxy.getStackTraceElementProxyArray(),
                ACCEPT_ALL,
                singlePassOut,
                proxy.getClassName(),
                stream
        );

        assertEquals(twoPassOut.toString(StandardCharsets.UTF_8), singlePassOut.toString(StandardCharsets.UTF_8));
        assertEquals(twoPassHash, singlePassHash);
    }

    @Test
    void fingerprint_withRejectAllFilter_usesFallback() {
        IThrowableProxy proxy = new ThrowableProxy(sampleThrowable());
        long hash = JavaStackSanitizerLogback.fingerprint(proxy, cls -> false, stream);
        assertNotEquals(0L, hash, "fallback must still produce a hash");
    }

    @Test
    void fingerprint_withSelectiveFilter_differsFromAcceptAll() {
        IThrowableProxy proxy = new ThrowableProxy(sampleThrowable());
        // rejects the lambda frame, keeps the two plain com.example.MyClass frames
        long selective = JavaStackSanitizerLogback.fingerprint(proxy, cls -> !cls.contains("Lambda"), stream);
        long all = JavaStackSanitizerLogback.fingerprint(proxy, ACCEPT_ALL, stream);
        assertNotEquals(all, selective, "different filters must produce different fingerprints");
    }

    @Test
    void fingerprint_withRejectAllFilter_matchesCoreFallback() {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);
        long fromProxy = JavaStackSanitizerLogback.fingerprint(proxy, cls -> false, stream);
        long fromCore = JavaStackSanitizer.fingerprint(throwable, cls -> false, stream);
        assertEquals(fromCore, fromProxy, "logback fallback must match core fallback");
    }

    @Test
    void addFromTraceToOutputStreamJson_withRejectAll_writesFallback() throws IOException {
        IThrowableProxy proxy = new ThrowableProxy(sampleThrowable());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JavaStackSanitizerLogback.addFromTraceToOutputStreamJson(proxy.getStackTraceElementProxyArray(), cls -> false, out);
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("com.example.MyClass"), "fallback must write raw frames: " + json);
    }
}
