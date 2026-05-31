package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JavaStackSanitizer}.
 */
class JavaStackSanitizerTest {

    // ---- getSanitizedFrames ----

    @Test
    void getSanitizedFrames_filtersJdkInternalFrames() {
        StackTraceElement[] frames = {
            ste("com.example.MyClass", "method1"),
            ste("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0"),
            ste("jdk.internal.misc.Unsafe", "getInt"),
            ste("com.example.MyClass", "method2"),
        };

        List<String> result = JavaStackSanitizer.getSanitizedFrames(frames, 100);

        assertEquals(2, result.size());
        assertEquals("com.example.MyClass.method1", result.get(0));
        assertEquals("com.example.MyClass.method2", result.get(1));
    }

    @Test
    void getSanitizedFrames_filtersSunReflectFrames() {
        StackTraceElement[] frames = {
            ste("com.example.MyClass", "method1"),
            ste("sun.reflect.NativeMethodAccessorImpl", "invoke"),
            ste("sun.reflect.Reflection", "getCallerClass"),
            ste("com.example.MyClass", "method2"),
        };

        List<String> result = JavaStackSanitizer.getSanitizedFrames(frames, 100);

        assertEquals(2, result.size());
        assertEquals("com.example.MyClass.method1", result.get(0));
        assertEquals("com.example.MyClass.method2", result.get(1));
    }

    @Test
    void getSanitizedFrames_stripsLineNumbers() {
        StackTraceElement[] frames = {
            ste("com.example.MyClass", "method1", "MyClass.java", 42),
            ste("com.example.MyClass", "method2", "MyClass.java", 99),
        };

        List<String> result = JavaStackSanitizer.getSanitizedFrames(frames, 100);

        assertEquals("com.example.MyClass.method1", result.get(0));
        assertEquals("com.example.MyClass.method2", result.get(1));
        // No line numbers in output
        assertFalse(result.get(0).contains("42"));
        assertFalse(result.get(1).contains("99"));
    }

    @Test
    void getSanitizedFrames_normalizesLambdaIdentifiers() {
        StackTraceElement[] frames = {
            ste("com.example.MyClass$$Lambda$123/0x00007f8b12345678", "run"),
            ste("com.example.MyClass$$Lambda$456/0x00007f8b87654321", "apply"),
        };

        List<String> result = JavaStackSanitizer.getSanitizedFrames(frames, 100);

        assertEquals("com.example.MyClass$$Lambda.run", result.get(0));
        assertEquals("com.example.MyClass$$Lambda.apply", result.get(1));
    }

    @Test
    void getSanitizedFrames_standardizesNativeMethods() {
        StackTraceElement nativeFrame = new StackTraceElement(
            "com.example.MyClass", "nativeMethod", "MyClass.java", -2);

        List<String> result = JavaStackSanitizer.getSanitizedFrames(
            new StackTraceElement[]{nativeFrame}, 100);

        assertEquals(1, result.size());
        assertEquals("com.example.MyClass.nativeMethod(native)", result.get(0));
    }

    @Test
    void getSanitizedFrames_respectsMaxFrames() {
        StackTraceElement[] frames = {
            ste("com.example.A", "method1"),
            ste("com.example.B", "method2"),
            ste("com.example.C", "method3"),
            ste("com.example.D", "method4"),
        };

        List<String> result = JavaStackSanitizer.getSanitizedFrames(frames, 2);

        assertEquals(2, result.size());
        assertEquals("com.example.A.method1", result.get(0));
        assertEquals("com.example.B.method2", result.get(1));
    }

    @Test
    void getSanitizedFrames_nullFrames_returnsEmpty() {
        List<String> result = JavaStackSanitizer.getSanitizedFrames((StackTraceElement[]) null, 100);
        assertTrue(result.isEmpty());
    }

    @Test
    void getSanitizedFrames_emptyFrames_returnsEmpty() {
        List<String> result = JavaStackSanitizer.getSanitizedFrames(new StackTraceElement[0], 100);
        assertTrue(result.isEmpty());
    }

    // ---- getSanitizedFrames(Throwable) ----

    @Test
    void getSanitizedFrames_throwable_extractsFrames() {
        RuntimeException ex = new RuntimeException("test");
        List<String> result = JavaStackSanitizer.getSanitizedFrames(ex, 100);

        assertFalse(result.isEmpty(), "Should extract frames from throwable");
        // First frame should be this test method
        assertTrue(result.get(0).contains("JavaStackSanitizerTest"),
                "First frame should be this test class: " + result.get(0));
    }

    @Test
    void getSanitizedFrames_nullThrowable_returnsEmpty() {
        List<String> result = JavaStackSanitizer.getSanitizedFrames((Throwable) null, 100);
        assertTrue(result.isEmpty());
    }

    // ---- getFingerprint ----

    @Test
    void getFingerprint_returnsPipeDelimitedFrames() {
        StackTraceElement[] frames = {
            ste("com.example.A", "method1"),
            ste("com.example.B", "method2"),
        };

        String fingerprint = JavaStackSanitizer.getFingerprint(
            new RuntimeException("") {
                @Override
                public StackTraceElement[] getStackTrace() { return frames; }
            }, 100);

        assertEquals("com.example.A.method1|com.example.B.method2", fingerprint);
    }

    @Test
    void getFingerprint_deterministicAcrossRuns() {
        StackTraceElement[] frames = {
            ste("com.example.MyClass", "method1"),
            ste("com.example.MyClass", "method2"),
        };

        RuntimeException ex = new RuntimeException("") {
            @Override
            public StackTraceElement[] getStackTrace() { return frames; }
        };

        String fp1 = JavaStackSanitizer.getFingerprint(ex, 100);
        String fp2 = JavaStackSanitizer.getFingerprint(ex, 100);

        assertEquals(fp1, fp2, "Fingerprint should be deterministic");
    }

    @Test
    void getFingerprint_nullThrowable_returnsEmpty() {
        assertEquals("", JavaStackSanitizer.getFingerprint(null, 100));
    }

    @Test
    void getFingerprint_respectsMaxFrames() {
        StackTraceElement[] frames = {
            ste("com.example.A", "m1"),
            ste("com.example.B", "m2"),
            ste("com.example.C", "m3"),
        };

        String fingerprint = JavaStackSanitizer.getFingerprint(
            new RuntimeException("") {
                @Override
                public StackTraceElement[] getStackTrace() { return frames; }
            }, 2);

        assertEquals("com.example.A.m1|com.example.B.m2", fingerprint);
    }

    // ---- mixed scenario ----

    @Test
    void getSanitizedFrames_mixedFrameTypes() {
        StackTraceElement[] frames = {
            ste("com.example.MyClass", "doStuff"),
            ste("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0"),
            ste("sun.reflect.Reflection", "getCallerClass"),
            ste("com.example.MyClass$$Lambda$42/0xdeadbeef", "run"),
            ste("com.example.MyClass", "doOtherStuff"),
            new StackTraceElement("com.example.MyClass", "nativeMethod", "MyClass.java", -2),
        };

        List<String> result = JavaStackSanitizer.getSanitizedFrames(frames, 100);

        assertEquals(4, result.size());
        assertEquals("com.example.MyClass.doStuff", result.get(0));
        assertEquals("com.example.MyClass$$Lambda.run", result.get(1));
        assertEquals("com.example.MyClass.doOtherStuff", result.get(2));
        assertEquals("com.example.MyClass.nativeMethod(native)", result.get(3));
    }

    // ---- helpers ----

    private static StackTraceElement ste(String className, String methodName) {
        return new StackTraceElement(className, methodName, null, -1);
    }

    private static StackTraceElement ste(String className, String methodName, String file, int line) {
        return new StackTraceElement(className, methodName, file, line);
    }
}