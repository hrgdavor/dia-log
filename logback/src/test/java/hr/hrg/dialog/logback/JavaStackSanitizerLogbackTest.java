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

class JavaStackSanitizerLogbackTest {

    private static final Predicate<String> ACCEPT_ALL = cls -> true;

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

        long core = JavaStackSanitizer.fingerprint(throwable, ACCEPT_ALL);
        long logback = JavaStackSanitizerLogback.fingerprint(proxy, ACCEPT_ALL);

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
        long twoPassHash = JavaStackSanitizerLogback.fingerprint(proxy, ACCEPT_ALL);

        ByteArrayOutputStream singlePassOut = new ByteArrayOutputStream();
        long singlePassHash = JavaStackSanitizerLogback.addFromTraceToOutputStreamJsonAndFingerprint(
                proxy.getStackTraceElementProxyArray(),
                ACCEPT_ALL,
                singlePassOut,
                proxy.getClassName()
        );

        assertEquals(twoPassOut.toString(StandardCharsets.UTF_8), singlePassOut.toString(StandardCharsets.UTF_8));
        assertEquals(twoPassHash, singlePassHash);
    }
}
