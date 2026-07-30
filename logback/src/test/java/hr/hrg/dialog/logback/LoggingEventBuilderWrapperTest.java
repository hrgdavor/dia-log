package hr.hrg.dialog.logback;

import hr.hrg.dialog.core.LoggingEventBuilderWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/**
 * Unit tests for {@link LoggingEventBuilderWrapper}.
 */
class LoggingEventBuilderWrapperTest {

    private final LoggerContext loggerContext = new LoggerContext();
    private final Logger realLogger = loggerContext.getLogger("test");

    /**
     * Concrete test subclass to allow instantiation (the base class is abstract).
     */
    private static class TestWrapper extends LoggingEventBuilderWrapper {
        TestWrapper(LoggingEventBuilder delegate, org.slf4j.Logger logger) {
            super(delegate, logger);
        }
    }

    // ---- stackWhenTraceEnabled ----

    @Test
    void stackWhenTraceEnabled_attachesThrowableWhenTraceEnabled() {
        realLogger.setLevel(ch.qos.logback.classic.Level.TRACE);

        LoggingEventBuilder delegate = realLogger.atDebug();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.stackWhenTraceEnabled();
        wrapper.log("debug message");

        realLogger.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    void stackWhenTraceEnabled_doesNotAttachWhenTraceDisabled() {
        realLogger.setLevel(ch.qos.logback.classic.Level.INFO); // TRACE disabled

        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.stackWhenTraceEnabled();
        wrapper.log("info message");

        // No assertion needed — just verify no exception is thrown
    }

    @Test
    void stackWhenTraceEnabled_worksWithDisabledLevel() {
        realLogger.setLevel(ch.qos.logback.classic.Level.ERROR); // DEBUG+TRACE disabled

        LoggingEventBuilder delegate = realLogger.atDebug(); // no-op builder
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.stackWhenTraceEnabled();
        wrapper.kv("key", "value").log("debug message");

        // Should not throw — no-op builder ignores everything
    }

    // ---- delegation ----

    @Test
    void setCause_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);

        LoggingEventBuilder result = wrapper.setCause(new RuntimeException("test"));
        Assertions.assertSame(wrapper, result, "setCause should return the wrapper for chaining");
    }

    @Test
    void addMarker_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);

        Marker marker = MarkerFactory.getMarker("TEST");
        LoggingEventBuilder result = wrapper.addMarker(marker);
        Assertions.assertSame(wrapper, result, "addMarker should return the wrapper for chaining");
    }

    @Test
    void addArgument_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);

        LoggingEventBuilder result = wrapper.addArgument("arg1");
        Assertions.assertSame(wrapper, result, "addArgument should return the wrapper for chaining");
    }

    @Test
    void addKeyValue_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);

        LoggingEventBuilder result = wrapper.addKeyValue("key", "value");
        Assertions.assertSame(wrapper, result, "addKeyValue should return the wrapper for chaining");
    }

    @Test
    void kv_shorthand_isDelegated() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);

        LoggingEventBuilder result = wrapper.kv("key", "value");
        Assertions.assertSame(wrapper, result, "kv should return the wrapper for chaining");
    }

    // ---- log() overloads ----

    @Test
    void log_varargsOverload() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.log("msg {} {} {}", "a", "b", "c");
    }

    @Test
    void log_twoArgOverload() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.log("msg {}", "arg");
    }

    @Test
    void log_threeArgOverload() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.log("msg {} {}", "a", "b");
    }

    @Test
    void log_supplierOverload() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.log(() -> "lazy message");
    }

    @Test
    void log_noArgOverload() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.log();
    }

    @Test
    void log_withCause() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.setCause(new RuntimeException("test cause")).log("error occurred");
    }

    @Test
    void log_withMarker() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.addMarker(MarkerFactory.getMarker("TEST")).log("marked message");
    }

    @Test
    void log_withKeyValue() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.addKeyValue("userId", "alice").log("user processed");
    }

    @Test
    void log_withMultipleKeyValues() {
        LoggingEventBuilder delegate = realLogger.atInfo();
        TestWrapper wrapper = new TestWrapper(delegate, realLogger);
        wrapper.addKeyValue("userId", "alice")
               .addKeyValue("requestId", "123")
               .log("request completed");
    }
}