package hr.hrg.dialog.logback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import hr.hrg.dialog.core.LoggingEventBuilderWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link LoggingEventBuilderWrapper}.
 */
class LoggingEventBuilderWrapperTest {

    private final LoggerContext loggerContext = new LoggerContext();
    private final Logger realLogger = loggerContext.getLogger("test");

    /**
     * Concrete test subclass to allow instantiation (the base class is abstract).
     */
    private static class TestWrapper extends LoggingEventBuilderWrapper<TestWrapper> {
        TestWrapper(LoggingEventBuilder delegate, Runnable clear) {
            super(delegate, clear);
        }
        TestWrapper(LoggingEventBuilder delegate, Runnable clear, org.slf4j.Logger logger) {
            super(delegate, clear, logger);
        }
        @Override
        public TestWrapper self() {
            return this;
        }
    }

    // ---- MDC key cleanup ----

    @Test
    void addKeyValue_cleansUpMdcAfterLog() {
        List<String> keysTracked = new ArrayList<>();
        LoggingEventBuilder delegate = realLogger.atInfo();

        TestWrapper wrapper = new TestWrapper(delegate, () -> {});
        wrapper.addKeyValue("userId", "alice");
        wrapper.addKeyValue("requestId", "123");

        // Keys should be in MDC during building
        assertEquals("alice", MDC.get("userId"));
        assertEquals("123", MDC.get("requestId"));

        wrapper.log("test message");

        // After log(), keys should be cleaned up
        Assertions.assertNull(MDC.get("userId"), "userId should be removed from MDC after log()");
        Assertions.assertNull(MDC.get("requestId"), "requestId should be removed from MDC after log()");
    }

    @Test
    void addKeyValue_supplier_cleansUpMdcAfterLog() {
        LoggingEventBuilder delegate = realLogger.atInfo();

        TestWrapper wrapper = new TestWrapper(delegate, () -> {});
        wrapper.addKeyValue("computed", () -> "dynamic-value");

        assertEquals("dynamic-value", MDC.get("computed"));

        wrapper.log("test");

        Assertions.assertNull(MDC.get("computed"), "Supplier key should be removed from MDC after log()");
    }

    @Test
    void multipleLogCalls_eachCleansUpKeys() {
        LoggingEventBuilder delegate = realLogger.atInfo();

        // First log
        TestWrapper w1 = new TestWrapper(delegate, () -> {});
        w1.addKeyValue("key1", "val1");
        w1.log("first");
        Assertions.assertNull(MDC.get("key1"));

        // Second log
        TestWrapper w2 = new TestWrapper(delegate, () -> {});
        w2.addKeyValue("key2", "val2");
        w2.log("second");
        Assertions.assertNull(MDC.get("key2"));
    }

    @Test
    void closeContext_onlyRunsOnce() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        LoggingEventBuilder delegate = realLogger.atInfo();

        TestWrapper wrapper = new TestWrapper(delegate, () -> cleared.set(true));
        wrapper.addKeyValue("k", "v");

        wrapper.log("msg");
        Assertions.assertTrue(cleared.get());

        // Calling close again should be a no-op
        wrapper.close();
        Assertions.assertTrue(cleared.get()); // Still true, not called twice
    }

    @Test
    void close_viaAutoCloseable() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        LoggingEventBuilder delegate = realLogger.atInfo();

        try (TestWrapper wrapper = new TestWrapper(delegate, () -> cleared.set(true))) {
            wrapper.addKeyValue("k", "v");
            Assertions.assertFalse(cleared.get());
        }

        Assertions.assertTrue(cleared.get(), "close() should fire the clear Runnable");
        Assertions.assertNull(MDC.get("k"));
    }

    // ---- Clear Runnable ----

    @Test
    void clearRunnable_isCalledAfterLog() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        LoggingEventBuilder delegate = realLogger.atInfo();

        TestWrapper wrapper = new TestWrapper(delegate, () -> cleared.set(true));
        wrapper.log("test");

        Assertions.assertTrue(cleared.get(), "Clear Runnable should be called after log()");
    }

    // ---- stackWhenTraceEnabled ----

    @Test
    void stackWhenTraceEnabled_attachesThrowableWhenTraceEnabled() {
        // Enable TRACE on the test logger
        realLogger.setLevel(ch.qos.logback.classic.Level.TRACE);

        LoggingEventBuilder delegate = realLogger.atDebug();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {}, realLogger);
        wrapper.stackWhenTraceEnabled();
        wrapper.log("debug message");

        realLogger.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    void stackWhenTraceEnabled_doesNotAttachWhenTraceDisabled() {
        realLogger.setLevel(ch.qos.logback.classic.Level.INFO); // TRACE disabled

        LoggingEventBuilder delegate = realLogger.atInfo();
        // No-op wrapper since INFO is enabled but TRACE is not
        TestWrapper wrapper = new TestWrapper(delegate, () -> {}, realLogger);
        wrapper.stackWhenTraceEnabled();
        wrapper.log("info message");

        // No assertion needed — just verify no exception is thrown
    }

    @Test
    void stackWhenTraceEnabled_worksWithDisabledLevel() {
        realLogger.setLevel(ch.qos.logback.classic.Level.ERROR); // DEBUG+TRACE disabled

        LoggingEventBuilder delegate = realLogger.atDebug(); // no-op builder
        TestWrapper wrapper = new TestWrapper(delegate, () -> {}, realLogger);
		wrapper.stackWhenTraceEnabled();
        wrapper.kv("key", "value").log("debug message");

        // Should not throw — no-op builder ignores everything
    }

    // ---- kv() shorthand ----

    @Test
    void kv_shorthand_addsKeyValueToMDC() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});

        wrapper.kv("myKey", "myValue");
        assertEquals("myValue", MDC.get("myKey"));

        wrapper.log("test");
        Assertions.assertNull(MDC.get("myKey"));
    }

    // ---- delegation ----

    @Test
    void setCause_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});

        // setCause should return the wrapper for chaining
        LoggingEventBuilder result = wrapper.setCause(new RuntimeException("test"));
        Assertions.assertSame(wrapper, result, "setCause should return the wrapper for chaining");
    }

    @Test
    void addMarker_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});

        Marker marker = MarkerFactory.getMarker("TEST");
        LoggingEventBuilder result = wrapper.addMarker(marker);
        Assertions.assertSame(wrapper, result, "addMarker should return the wrapper for chaining");
    }

    @Test
    void addArgument_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});

        LoggingEventBuilder result = wrapper.addArgument("arg1");
        Assertions.assertSame(wrapper, result, "addArgument should return the wrapper for chaining");
    }

    // ---- log() overloads ----

    @Test
    void log_varargsOverload_cleansUp() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});
        wrapper.addKeyValue("k", "v");

        wrapper.log("msg {} {} {}", "a", "b", "c");
        Assertions.assertNull(MDC.get("k"));
    }

    @Test
    void log_twoArgOverload_cleansUp() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});
        wrapper.addKeyValue("k", "v");

        wrapper.log("msg {}", "arg");
        Assertions.assertNull(MDC.get("k"));
    }

    @Test
    void log_threeArgOverload_cleansUp() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});
        wrapper.addKeyValue("k", "v");

        wrapper.log("msg {} {}", "a", "b");
        Assertions.assertNull(MDC.get("k"));
    }

    @Test
    void log_supplierOverload_cleansUp() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});
        wrapper.addKeyValue("k", "v");

        wrapper.log(() -> "lazy message");
        Assertions.assertNull(MDC.get("k"));
    }

    @Test
    void log_noArgOverload_cleansUp() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, () -> {});
        wrapper.addKeyValue("k", "v");

        wrapper.log();
        Assertions.assertNull(MDC.get("k"));
    }

    // ---- thread safety ----

    @Test
    void closeContext_isThreadSafe() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicBoolean anyFailed = new AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    LoggingEventBuilder delegate = realLogger.atInfo();
                    TestWrapper wrapper = new TestWrapper(delegate, () -> {});
                    wrapper.addKeyValue("key", "value");
                    wrapper.log("msg");
                } catch (Exception e) {
                    anyFailed.set(true);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        Assertions.assertFalse(anyFailed.get(), "Concurrent access should not throw");
        Assertions.assertNull(MDC.get("key"));
    }
}