package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for concrete {@link DiaLogger} behavior: wrapper creation, prefix handling,
 * {@link DiaLoggerBase#addKeyValues}, and disabled-level no-op behavior.
 * (Planned coverage item from plans/analysis-report.md §11.)
 */
class DiaLoggerTest {

    /** TestLogger variant that records key/value pairs and messages. */
    static class RecordingLogger extends LoggerFixture.TestLogger {

        final List<KeyValuePair> keyValues = new ArrayList<>();

        RecordingLogger(String name) {
            super(name);
        }

        @Override
        public LoggingEventBuilder atTrace() {
            return recordingBuilder();
        }

        @Override
        public LoggingEventBuilder atDebug() {
            return recordingBuilder();
        }

        @Override
        public LoggingEventBuilder atInfo() {
            return recordingBuilder();
        }

        @Override
        public LoggingEventBuilder atWarn() {
            return recordingBuilder();
        }

        @Override
        public LoggingEventBuilder atError() {
            return recordingBuilder();
        }

        @Override
        public LoggingEventBuilder atLevel(Level level) {
            return recordingBuilder();
        }

        @Override
        public LoggingEventBuilder makeLoggingEventBuilder(Level level) {
            return recordingBuilder();
        }

        @Override
        public boolean isEnabledForLevel(Level level) {
            return true;
        }

        private LoggingEventBuilder recordingBuilder() {
            return new LoggingEventBuilder() {
                @Override
                public LoggingEventBuilder setCause(Throwable t) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addMarker(Marker marker) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addKeyValue(String key, Object value) {
                    keyValues.add(new KeyValuePair(key, value));
                    return this;
                }

                @Override
                public LoggingEventBuilder addKeyValue(String key, Supplier<Object> valueSupplier) {
                    keyValues.add(new KeyValuePair(key, valueSupplier.get()));
                    return this;
                }

                @Override
                public LoggingEventBuilder addArgument(Object arg) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addArgument(Supplier<?> argSupplier) {
                    return this;
                }

                @Override
                public LoggingEventBuilder setMessage(String message) {
                    return this;
                }

                @Override
                public LoggingEventBuilder setMessage(Supplier<String> messageSupplier) {
                    return this;
                }

                @Override
                public void log() {
                    lastMessage = "";
                    allMessages.add("");
                }

                @Override
                public void log(String msg) {
                    lastMessage = msg;
                    allMessages.add(msg);
                }

                @Override
                public void log(String format, Object arg) {
                    lastMessage = format;
                    allMessages.add(format);
                }

                @Override
                public void log(String format, Object arg1, Object arg2) {
                    lastMessage = format;
                    allMessages.add(format);
                }

                @Override
                public void log(String format, Object... args) {
                    lastMessage = format;
                    allMessages.add(format);
                }

                @Override
                public void log(Supplier<String> messageSupplier) {
                    log(messageSupplier.get());
                }
            };
        }
    }

    /** TestLogger variant where every level is disabled. */
    static class DisabledLogger extends LoggerFixture.TestLogger {

        DisabledLogger(String name) {
            super(name);
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isWarnEnabled() {
            return false;
        }

        @Override
        public boolean isErrorEnabled() {
            return false;
        }

        @Override
        public boolean isEnabledForLevel(Level level) {
            return false;
        }
    }

    private final RecordingLogger delegate = new RecordingLogger("test.logger");
    private final DiaLogger log = new DiaLogger(delegate);

    @Test
    void atDebug_returnsWrapper_thatLogsThroughDelegate() {
        LoggingEventBuilderWrapperBase wrapper = log.atDebug();
        assertNotNull(wrapper);
        assertNotSame(LoggingEventBuilderWrapperNoop.singleton(), wrapper);

        wrapper.log("hello");
        assertEquals("hello", delegate.lastMessage());
    }

    @Test
    void prefix_isAddedAsKeyValue() {
        log.prependPrefix("app.");
        log.atDebug().log("msg");

        assertTrue(delegate.keyValues.contains(new KeyValuePair("prefix", "app.")),
                "prefix must be attached as a key/value pair: " + delegate.keyValues);
    }

    @Test
    void prependPrefix_prependsRepeatedCalls() {
        log.prependPrefix("a");
        log.prependPrefix("b");
        log.prependPrefix("c");
        assertEquals("cba", log.prefix, "each call must prepend to the existing prefix");
    }

    @Test
    void addKeyValues_addsPairsInOrder() {
        DiaLoggerBase.addKeyValues(log.atInfo(), "k1", "v1", "k2", "v2").log("m");

        assertEquals(List.of(
                new KeyValuePair("k1", "v1"),
                new KeyValuePair("k2", "v2")), delegate.keyValues);
    }

    @Test
    void addKeyValues_nullKey_isSkipped() {
        DiaLoggerBase.addKeyValues(log.atInfo(), "k1", "v1", null, "dropped", "k2", "v2").log("m");

        assertEquals(List.of(
                new KeyValuePair("k1", "v1"),
                new KeyValuePair("k2", "v2")), delegate.keyValues);
    }

    @Test
    void addKeyValues_oddLength_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> DiaLoggerBase.addKeyValues(log.atInfo(), "k1", "v1", "orphan"));
    }

    @Test
    void addKeyValues_nullArray_throws() {
        assertThrows(NullPointerException.class,
                () -> DiaLoggerBase.addKeyValues(log.atInfo(), (Object[]) null));
    }

    @Test
    void atLevel_enabled_returnsWrapper() {
        LoggingEventBuilderWrapperBase wrapper = log.at(Level.INFO);
        assertNotSame(LoggingEventBuilderWrapperNoop.singleton(), wrapper);
    }

    @Test
    void disabledLevel_returnsNoopWrapper_andSkipsVoidLogging() {
        DiaLogger disabled = new DiaLogger(new DisabledLogger("disabled"));

        assertSame(LoggingEventBuilderWrapperNoop.singleton(), disabled.atDebug());
        assertSame(LoggingEventBuilderWrapperNoop.singleton(), disabled.at(Level.INFO));
        assertSame(LoggingEventBuilderWrapperNoop.singleton(), disabled.atInfo());

        // void overloads must short-circuit without touching the delegate
        DisabledLogger dlg = new DisabledLogger("disabled");
        DiaLogger dl = new DiaLogger(dlg);
        dl.debug("must not be logged");
        dl.info("must not be logged");
        dl.error("must not be logged");
        assertTrue(dlg.allMessages.isEmpty(), "nothing must be logged when the level is disabled");
    }

    @Test
    void voidDebug_logsWhenEnabled() {
        log.debug("direct message");
        assertEquals("direct message", delegate.lastMessage());
    }

    @Test
    void loggerName_delegates() {
        assertEquals("test.logger", log.getName());
    }
}
