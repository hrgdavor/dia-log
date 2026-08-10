package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import hr.hrg.dialog.core.JavaStackTraceWriter;
import hr.hrg.dialog.core.Wyhash64;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaStackWriterLogbackTest {

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

    private static String hashCoreWriter(StackTraceElement[] trace) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        JavaStackTraceWriter.addFromTrace(trace, stream);
        return HexFormat.of().toHexDigits(stream.finalHash());
    }

    private static String hashLogbackWriter(IThrowableProxy proxy) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        JavaStackWriterLogback.addFromTrace(proxy.getStackTraceElementProxyArray(), stream);
        return HexFormat.of().toHexDigits(stream.finalHash());
    }

    @Test
    void fingerprint_matchesCoreWriterForSameThrowable() {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        long core = JavaStackTraceWriter.fingerprint(throwable, cls -> true);
        long logback = JavaStackWriterLogback.fingerprint(proxy, cls -> false);

        assertEquals(core, logback);
    }

    @Test
    void addFromTrace_matchesCoreWriterStreamingHash() {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        assertEquals(
                hashCoreWriter(throwable.getStackTrace()),
                hashLogbackWriter(proxy));
    }

    @Test
    void addFromTraceToStringBuffer_matchesCoreWriterOutput() {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        StringBuffer core = new StringBuffer();
        JavaStackTraceWriter.addFromTraceToStringBuffer(throwable.getStackTrace(), core);

        StringBuffer logback = new StringBuffer();
        JavaStackWriterLogback.addFromTraceToStringBuffer(proxy.getStackTraceElementProxyArray(), logback);

        assertEquals(core.toString(), logback.toString());
    }

    @Test
    void addFromTraceToOutputStream_matchesCoreWriterOutput() throws IOException {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        ByteArrayOutputStream core = new ByteArrayOutputStream();
        JavaStackTraceWriter.addFromTraceToOutputStream(throwable.getStackTrace(), core);

        ByteArrayOutputStream logback = new ByteArrayOutputStream();
        JavaStackWriterLogback.addFromTraceToOutputStream(proxy.getStackTraceElementProxyArray(), logback);

        assertEquals(core.toString(StandardCharsets.UTF_8), logback.toString(StandardCharsets.UTF_8));
    }

    @Test
    void addFromTraceToOutputStreamJson_matchesCoreWriterOutput() throws IOException {
        Throwable throwable = sampleThrowable();
        IThrowableProxy proxy = new ThrowableProxy(throwable);

        ByteArrayOutputStream core = new ByteArrayOutputStream();
        JavaStackTraceWriter.addFromTraceToOutputStreamJson(throwable.getStackTrace(), core);

        ByteArrayOutputStream logback = new ByteArrayOutputStream();
        JavaStackWriterLogback.addFromTraceToOutputStreamJson(proxy.getStackTraceElementProxyArray(), logback);

        assertEquals(core.toString(StandardCharsets.UTF_8), logback.toString(StandardCharsets.UTF_8));
    }
}
