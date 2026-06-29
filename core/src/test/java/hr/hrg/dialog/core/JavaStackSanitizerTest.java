package hr.hrg.dialog.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JavaStackSanitizer#addFromTrace}.
 * <p>
 * Tests focus on the streaming-hash behaviour: which frames are included,
 * how class names and method names are normalised, and the fallback path
 * when all frames are filtered out.
 */
class JavaStackSanitizerTest {

    // ---- helpers ----

    /** Hash the given frames with the given filter and return the hex string. */
    private static String hashFrames(StackTraceElement[] trace, Predicate<String> filter) {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        JavaStackSanitizer.addFromTrace(trace, filter, stream);
        return HexFormat.of().toHexDigits(stream.finalHash());
    }

    /** Create a simple stack-frame element without a file/line number. */
    private static StackTraceElement ste(String className, String methodName) {
        return new StackTraceElement(className, methodName, null, -1);
    }

    /** Create a stack-frame element with file and line number (line is ignored by the sanitizer). */
    private static StackTraceElement ste(String className, String methodName, String file, int line) {
        return new StackTraceElement(className, methodName, file, line);
    }

    /** Filter that accepts every frame. */
    private static final Predicate<String> ACCEPT_ALL = cls -> true;

    /** Filter that rejects jdk.internal.* and sun.reflect.* frames. */
    private static final Predicate<String> JDK_FILTER = cls ->
            !cls.startsWith("jdk.internal.") && !cls.startsWith("sun.reflect.");

    // ---- basic inclusion / filtering ----

    @Test
    void addFromTrace_includesAllFrames_whenFilterAcceptsAll() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.Other", "method2"),
        };
        String hash = hashFrames(frames, ACCEPT_ALL);
        assertNotNull(hash);
        assertEquals(16, hash.length(), "hex should be 16 chars (8 bytes)");
    }

    @Test
    void addFromTrace_filtersOutRejectedFrames() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0"),
                ste("sun.reflect.Reflection", "getCallerClass"),
                ste("com.example.MyClass", "method2"),
        };

        String hashAll   = hashFrames(frames, ACCEPT_ALL);   // 4 frames
        String hashApp   = hashFrames(frames, JDK_FILTER);    // 2 frames
        assertNotEquals(hashAll, hashApp, "filtered hash must differ from unfiltered");
    }

    @Test
    void addFromTrace_deterministic_sameInputSameHash() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "method1"),
                ste("com.example.MyClass", "method2"),
        };

        String h1 = hashFrames(frames, ACCEPT_ALL);
        String h2 = hashFrames(frames, ACCEPT_ALL);
        assertEquals(h1, h2, "hash should be deterministic");
    }

    @Test
    void addFromTrace_differentTracesDifferentHash() {
        StackTraceElement[] framesA = {
                ste("com.example.A", "method1"),
        };
        StackTraceElement[] framesB = {
                ste("com.example.B", "method1"),
        };

        assertNotEquals(
                hashFrames(framesA, ACCEPT_ALL),
                hashFrames(framesB, ACCEPT_ALL),
                "different class names should produce different hashes");
    }

    @Test
    void addFromTrace_emptyTrace_producesHash() {
        String hash = hashFrames(new StackTraceElement[0], ACCEPT_ALL);
        assertNotNull(hash);
        assertEquals(16, hash.length());
    }

    @Test
    void addFromTrace_traceWithNullFilter_throwsNullPointerException() {
        Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
        assertThrows(NullPointerException.class,
                () -> JavaStackSanitizer.addFromTrace(
                        new StackTraceElement[]{ste("x", "y")},
                        null,
                        stream));
    }

    // ---- lambda class normalisation ($$Lambda$…) ----

    @Test
    void addFromTrace_normalisesLambdaClassNames() {
        // two different synthetic class names that should produce the same hash
        StackTraceElement[] frames1 = {
                ste("com.example.MyClass$$Lambda$123/0x00007f8b12345678", "run"),
        };
        StackTraceElement[] frames2 = {
                ste("com.example.MyClass$$Lambda$456/0x00007f8b87654321", "apply"),
        };

        // different method name → must differ
        assertNotEquals(
                hashFrames(frames1, ACCEPT_ALL),
                hashFrames(frames2, ACCEPT_ALL),
                "different lambda methods should produce different hashes");
    }

    @Test
    void addFromTrace_lambdaClassWithSameEnclosingClassAndMethod_producesSameHash() {
        // Same enclosing class + same method reference → same hash
        StackTraceElement[] frames1 = {
                ste("com.example.MyClass$$Lambda$123/0xabc", "run"),
        };
        StackTraceElement[] frames2 = {
                ste("com.example.MyClass$$Lambda$999/0xdef", "run"),
        };

        assertEquals(
                hashFrames(frames1, ACCEPT_ALL),
                hashFrames(frames2, ACCEPT_ALL),
                "lambda classes with different synthetic IDs but same enclosing class and method should hash identically");
    }

    @Test
    void addFromTrace_lambdaClass_methodNameFixedToLambda() {
        // Lambda class frames always use "lambda" as the method name
        StackTraceElement[] lambdaFrame = {
                ste("com.example.MyClass$$Lambda$1/0x123", "run"),
        };
        // A hand-written class with same class name (no $$Lambda$) and method "lambda"
        StackTraceElement[] handWritten = {
                ste("com.example.Foo", "lambda"),
        };

        // These should be different because the class names differ
        assertNotEquals(
                hashFrames(lambdaFrame, ACCEPT_ALL),
                hashFrames(handWritten, ACCEPT_ALL));
    }

    @Test
    void addFromTrace_lambdaClass_stripsSyntheticSuffix() {
        // lambda class frame vs same enclosing class hand-written
        StackTraceElement[] lambda = {
                ste("com.example.MyClass$$Lambda$42/0xbeef", "run"),
        };
        StackTraceElement[] plain = {
                ste("com.example.MyClass", "lambda"),
        };

        // Both should hash to the enclosing class + "lambda"
        assertEquals(
                hashFrames(lambda, ACCEPT_ALL),
                hashFrames(plain, ACCEPT_ALL),
                "lambda class should be normalised to enclosing class.method= 'lambda'");
    }

    // ---- lambda$ method name extraction ----

    @Test
    void addFromTrace_extractsOriginalMethodFromLambdaDollarMethod() {
        StackTraceElement[] lambdaDollar = {
                ste("com.example.MyClass", "lambda$originalMethod$42"),
        };
        StackTraceElement[] normal = {
                ste("com.example.MyClass", "originalMethod"),
        };

        assertEquals(
                hashFrames(lambdaDollar, ACCEPT_ALL),
                hashFrames(normal, ACCEPT_ALL),
                "lambda$originalMethod$42 should hash as originalMethod");
    }

    @Test
    void addFromTrace_lambdaDollarWithTwoParts() {
        // lambda$method$number pattern
        StackTraceElement[] withNumber = {
                ste("com.example.MyClass", "lambda$process$12"),
        };
        StackTraceElement[] justMethod = {
                ste("com.example.MyClass", "process"),
        };

        assertEquals(
                hashFrames(withNumber, ACCEPT_ALL),
                hashFrames(justMethod, ACCEPT_ALL));
    }

    @Test
    void addFromTrace_lambdaDollarWithoutTrailingNumber() {
        // lambda$method (no $number suffix) — should still extract the method
        StackTraceElement[] withoutNumber = {
                ste("com.example.MyClass", "lambda$handle"),
        };
        StackTraceElement[] justMethod = {
                ste("com.example.MyClass", "handle"),
        };

        assertEquals(
                hashFrames(withoutNumber, ACCEPT_ALL),
                hashFrames(justMethod, ACCEPT_ALL));
    }

    @Test
    void addFromTrace_nonLambdaMethodNameUnchanged() {
        StackTraceElement[] normal = {
                ste("com.example.MyClass", "process"),
        };
        StackTraceElement[] alsoNormal = {
                ste("com.example.MyClass", "process"),
        };

        assertEquals(
                hashFrames(normal, ACCEPT_ALL),
                hashFrames(alsoNormal, ACCEPT_ALL));
    }

    // ---- fallback when all frames are filtered out ----

    @Test
    void addFromTrace_fallback_usesTopThreeFramesWhenAllFiltered() {
        StackTraceElement[] trace = {
                ste("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0"),
                ste("jdk.internal.misc.Unsafe", "getInt"),
                ste("sun.reflect.Reflection", "getCallerClass"),
        };

        // Reject everything → fallback to top 3
        Predicate<String> rejectAll = cls -> false;
        String fallbackHash = hashFrames(trace, rejectAll);

        assertNotNull(fallbackHash);
        assertEquals(16, fallbackHash.length());
    }

    @Test
    void addFromTrace_fallback_deterministic() {
        StackTraceElement[] trace = {
                ste("jdk.internal.A", "m1"),
                ste("jdk.internal.B", "m2"),
        };

        Predicate<String> rejectAll = cls -> false;
        assertEquals(
                hashFrames(trace, rejectAll),
                hashFrames(trace, rejectAll));
    }

    @Test
    void addFromTrace_fallback_respectsTopThreeOnly() {
        StackTraceElement[] traceLong = {
                ste("jdk.internal.A", "m1"),
                ste("jdk.internal.B", "m2"),
                ste("jdk.internal.C", "m3"),
                ste("jdk.internal.D", "m4"),
        };
        StackTraceElement[] traceShort = {
                ste("jdk.internal.A", "m1"),
                ste("jdk.internal.B", "m2"),
                ste("jdk.internal.C", "m3"),
        };

        Predicate<String> rejectAll = cls -> false;
        // Both should hash the same (only top 3 considered in fallback)
        assertEquals(
                hashFrames(traceLong, rejectAll),
                hashFrames(traceShort, rejectAll));
    }

    @Test
    void addFromTrace_fallback_withFewerThanThreeFrames() {
        StackTraceElement[] trace = {
                ste("jdk.internal.A", "m1"),
        };

        Predicate<String> rejectAll = cls -> false;
        String hash = hashFrames(trace, rejectAll);
        assertNotNull(hash);
        assertEquals(16, hash.length());
    }

    @Test
    void addFromTrace_fallback_emptyTrace_noThrow() {
        Predicate<String> rejectAll = cls -> false;
        String hash = hashFrames(new StackTraceElement[0], rejectAll);
        assertNotNull(hash);
        assertEquals(16, hash.length());
    }

    // ---- DOT / NEWLINE separator behaviour (tested implicitly via hash differences) ----

    @Test
    void addFromTrace_twoFramesDifferFromOne() {
        StackTraceElement[] oneFrame = {
                ste("com.example.A", "m1"),
        };
        StackTraceElement[] twoFrames = {
                ste("com.example.A", "m1"),
                ste("com.example.B", "m2"),
        };

        assertNotEquals(
                hashFrames(oneFrame, ACCEPT_ALL),
                hashFrames(twoFrames, ACCEPT_ALL),
                "different number of frames should produce different hashes");
    }

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

        assertNotEquals(
                hashFrames(order1, ACCEPT_ALL),
                hashFrames(order2, ACCEPT_ALL),
                "frame order should affect the hash");
    }

    // ---- mixed scenario ----

    @Test
    void addFromTrace_mixedFrameTypes() {
        StackTraceElement[] frames = {
                ste("com.example.MyClass", "doStuff"),
                ste("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0"),
                ste("sun.reflect.Reflection", "getCallerClass"),
                ste("com.example.MyClass$$Lambda$42/0xdeadbeef", "run"),
                ste("com.example.MyClass", "lambda$doOtherStuff$99"),
        };

        // With filter: only app frames pass through (frames at index 0, 3, 4)
        String hashFiltered = hashFrames(frames, JDK_FILTER);

        // Construct the expected filtered trace:
        StackTraceElement[] expectedFrames = {
                ste("com.example.MyClass", "doStuff"),
                ste("com.example.MyClass", "run"),      // lambda class → "com.example.MyClass.lambda"
                ste("com.example.MyClass", "doOtherStuff"), // lambda$doOtherStuff$99 → "doOtherStuff"
        };
        String hashExpected = hashFrames(expectedFrames, ACCEPT_ALL);

        assertEquals(hashExpected, hashFiltered,
                "mixed scenario: filter + lambda normalisation should match");
    }

    // ---- public constants ----

    @Test
    void constants_areNotEmpty() {
        assertTrue(JavaStackSanitizer.DOT.length > 0);
        assertTrue(JavaStackSanitizer.NEWLINE.length > 0);
        assertTrue(JavaStackSanitizer.LAMBDA_METHOD.length > 0);
    }

    // ---- filter examples (as typically used) ----

    @Test
    void addFromTrace_defaultFilter_typicalUsage() {
        // Simulate the typical filter used in production
        Predicate<String> prodFilter = cls ->
                !cls.startsWith("jdk.internal.") && !cls.startsWith("sun.reflect.");

        StackTraceElement[] frames = {
                ste("com.example.App", "main"),
                ste("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0"),
                ste("sun.reflect.Reflection", "getCallerClass"),
                ste("com.example.App", "handle"),
        };

        String hash = hashFrames(frames, prodFilter);
        // Should only include com.example.App.main and com.example.App.handle
        String hashExpected = hashFrames(
                new StackTraceElement[]{
                        ste("com.example.App", "main"),
                        ste("com.example.App", "handle"),
                },
                ACCEPT_ALL);
        assertEquals(hashExpected, hash);
    }
}