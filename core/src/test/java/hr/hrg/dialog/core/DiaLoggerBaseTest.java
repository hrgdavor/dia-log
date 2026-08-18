package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.event.KeyValuePair;
import org.slf4j.event.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the void {@code trace/debug/info/warn/error} overload families and
 * the {@code atXxx(filler)} variants of {@link DiaLoggerBase} (the bulk of the
 * class is mechanical SLF4J delegation, verified here per overload).
 */
class DiaLoggerBaseTest {

    private interface LogCall {
        void run(DiaLogger log);
    }

    private static void assertLogs(LogCall call, String expectedLast) {
        DiaLoggerTest.RecordingLogger delegate = new DiaLoggerTest.RecordingLogger("test");
        DiaLogger log = new DiaLogger(delegate);
        call.run(log);
        assertEquals(expectedLast, delegate.lastMessage());
    }

    private static Marker marker() {
        return MarkerFactory.getMarker("mk");
    }

    @Test
    void trace_overloads() {
        assertLogs(l -> l.trace("plain"), "plain");
        assertLogs(l -> l.trace("fmt {}", 1), "fmt {}");
        assertLogs(l -> l.trace("fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.trace("fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.trace("boom", new RuntimeException("x")), "boom");
        assertLogs(l -> l.trace(marker(), "plain"), "plain");
        assertLogs(l -> l.trace(marker(), "fmt {}", 1), "fmt {}");
        assertLogs(l -> l.trace(marker(), "fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.trace(marker(), "fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.trace(marker(), "boom", new RuntimeException("x")), "boom");
    }

    @Test
    void debug_overloads() {
        assertLogs(l -> l.debug("plain"), "plain");
        assertLogs(l -> l.debug("fmt {}", 1), "fmt {}");
        assertLogs(l -> l.debug("fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.debug("fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.debug("boom", new RuntimeException("x")), "boom");
        assertLogs(l -> l.debug(marker(), "plain"), "plain");
        assertLogs(l -> l.debug(marker(), "fmt {}", 1), "fmt {}");
        assertLogs(l -> l.debug(marker(), "fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.debug(marker(), "fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.debug(marker(), "boom", new RuntimeException("x")), "boom");
    }

    @Test
    void info_overloads() {
        assertLogs(l -> l.info("plain"), "plain");
        assertLogs(l -> l.info("fmt {}", 1), "fmt {}");
        assertLogs(l -> l.info("fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.info("fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.info("boom", new RuntimeException("x")), "boom");
        assertLogs(l -> l.info(marker(), "plain"), "plain");
        assertLogs(l -> l.info(marker(), "fmt {}", 1), "fmt {}");
        assertLogs(l -> l.info(marker(), "fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.info(marker(), "fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.info(marker(), "boom", new RuntimeException("x")), "boom");
    }

    @Test
    void warn_overloads() {
        assertLogs(l -> l.warn("plain"), "plain");
        assertLogs(l -> l.warn("fmt {}", 1), "fmt {}");
        assertLogs(l -> l.warn("fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.warn("fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.warn("boom", new RuntimeException("x")), "boom");
        assertLogs(l -> l.warn(marker(), "plain"), "plain");
        assertLogs(l -> l.warn(marker(), "fmt {}", 1), "fmt {}");
        assertLogs(l -> l.warn(marker(), "fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.warn(marker(), "fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.warn(marker(), "boom", new RuntimeException("x")), "boom");
    }

    @Test
    void error_overloads() {
        assertLogs(l -> l.error("plain"), "plain");
        assertLogs(l -> l.error("fmt {}", 1), "fmt {}");
        assertLogs(l -> l.error("fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.error("fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.error("boom", new RuntimeException("x")), "boom");
        assertLogs(l -> l.error(marker(), "plain"), "plain");
        assertLogs(l -> l.error(marker(), "fmt {}", 1), "fmt {}");
        assertLogs(l -> l.error(marker(), "fmt2 {}", 1, 2), "fmt2 {}");
        assertLogs(l -> l.error(marker(), "fmt3 {}", new Object[]{1, 2, 3}), "fmt3 {}");
        assertLogs(l -> l.error(marker(), "boom", new RuntimeException("x")), "boom");
    }

    @Test
    void atXxx_withFiller_appliesFiller() {
        DiaLoggerTest.RecordingLogger delegate = new DiaLoggerTest.RecordingLogger("test");
        DiaLogger log = new DiaLogger(delegate);

        log.atInfo(b -> b.addKeyValue("f", "v")).log("m");
        assertTrue(delegate.keyValues.contains(new KeyValuePair("f", "v")));

        log.atWarn(b -> b.addMarker(marker())).log("w");
        assertEquals("w", delegate.lastMessage());

        log.at(Level.DEBUG, b -> b.addKeyValue("k2", "v2")).log("d");
        assertTrue(delegate.keyValues.contains(new KeyValuePair("k2", "v2")));
    }

    @Test
    void makeLoggingEventBuilder_returnsWrapper() {
        DiaLoggerTest.RecordingLogger delegate = new DiaLoggerTest.RecordingLogger("test");
        DiaLogger log = new DiaLogger(delegate);

        var builder = log.makeLoggingEventBuilder(Level.INFO);
        assertInstanceOf(LoggingEventBuilderWrapperBase.class, builder);
    }

    @Test
    void isEnabledForLevel_delegates() {
        DiaLoggerTest.RecordingLogger delegate = new DiaLoggerTest.RecordingLogger("test");
        DiaLogger log = new DiaLogger(delegate);
        assertTrue(log.isEnabledForLevel(Level.INFO));
        assertTrue(log.isTraceEnabled(marker()));
        assertTrue(log.isDebugEnabled(marker()));
        assertTrue(log.isInfoEnabled(marker()));
        assertTrue(log.isWarnEnabled(marker()));
        assertTrue(log.isErrorEnabled(marker()));
    }
}
