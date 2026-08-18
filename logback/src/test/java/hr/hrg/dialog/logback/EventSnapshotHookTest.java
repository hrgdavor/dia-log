package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link EventSnapshotHandler} hook on {@link JsonAppender}:
 * exact-bytes snapshots, programmatic and logback.xml class-name configuration.
 */
class EventSnapshotHookTest {

    /** Public no-arg handler that records snapshots into a shared list (for class-name config). */
    public static class RecordingHandler implements EventSnapshotHandler {
        static final List<byte[]> received = new ArrayList<>();

        public RecordingHandler() {
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void onEvent(byte[] eventJson) {
            received.add(eventJson);
        }

        static void reset() {
            received.clear();
        }
    }

    private LoggerContext context;
    private JsonAppender appender;
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        appender = new JsonAppender();
        appender.setContext(context);

        out = new ByteArrayOutputStream();
        appender.setOutputStream(out);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        appender.setEncoder(encoder);
        appender.start();
    }

    private LoggingEvent event(String message) {
        Logger logger = context.getLogger("test.snapshot");
        logger.setLevel(Level.INFO);
        LoggingEvent event = new LoggingEvent("test.snapshot", logger, Level.INFO, message, null, null);
        event.setTimeStamp(123456789L);
        return event;
    }

    /** Handler that records each owned snapshot into a list. */
    private static final class SnapshotList implements EventSnapshotHandler {
        final List<byte[]> snapshots = new ArrayList<>();

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void onEvent(byte[] eventJson) {
            snapshots.add(eventJson);
        }
    }

    /** Handler that is always disabled — onEvent must never be called. */
    private static final class DisabledHandler implements EventSnapshotHandler {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void onEvent(byte[] eventJson) {
            throw new AssertionError("onEvent must not be called when isEnabled() is false");
        }
    }

    @Test
    void snapshot_receivesExactJsonBytesForEachEvent() {
        SnapshotList handler = new SnapshotList();
        appender.setEventSnapshotHandler(handler);

        appender.doAppend(event("first"));
        appender.doAppend(event("second"));

        assertEquals(2, handler.snapshots.size(), "one snapshot per event");

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(2, lines.length);
        for (int i = 0; i < 2; i++) {
            String snapshot = new String(handler.snapshots.get(i), StandardCharsets.UTF_8);
            assertEquals(lines[i], snapshot, "snapshot must equal the exact written JSON line (no newline)");
            assertFalse(snapshot.endsWith("\n"), "snapshot must not include the trailing newline");
        }
    }

    @Test
    void snapshot_isOwnedFreshArrayPerEvent() {
        SnapshotList handler = new SnapshotList();
        appender.setEventSnapshotHandler(handler);

        appender.doAppend(event("first"));
        appender.doAppend(event("second"));

        assertEquals(2, handler.snapshots.size());
        byte[] first = handler.snapshots.get(0);
        byte[] second = handler.snapshots.get(1);
        assertNotSame(first, second, "each snapshot must be independently allocated");
        assertTrue(new String(first, StandardCharsets.UTF_8).contains("\"msg\":\"first\""));
        assertTrue(new String(second, StandardCharsets.UTF_8).contains("\"msg\":\"second\""));

        Arrays.fill(first, (byte) 'X');
        assertTrue(new String(handler.snapshots.get(1), StandardCharsets.UTF_8).contains("\"msg\":\"second\""),
                "later snapshots must be unaffected by mutating an earlier one");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"msg\":\"first\""),
                "the written output must be unaffected by mutating the snapshot");
    }

    @Test
    void snapshot_throwableEvent_includesStackFields() {
        SnapshotList handler = new SnapshotList();
        appender.setEventSnapshotHandler(handler);

        LoggingEvent ev = event("boom");
        ev.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(new RuntimeException("boom")));
        appender.doAppend(ev);

        assertEquals(1, handler.snapshots.size());
        String snapshot = new String(handler.snapshots.get(0), StandardCharsets.UTF_8);
        assertTrue(snapshot.contains("\"errHash\":"), "snapshot must carry the fingerprint: " + snapshot);
        assertTrue(snapshot.contains("\"stack\":"), "snapshot must carry the stack: " + snapshot);
    }

    @Test
    void snapshot_noHandler_eventStillWritten() {
        appender.doAppend(event("plain"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"msg\":\"plain\""));
    }

    @Test
    void snapshot_disabledHandler_skipsAllocationAndCallback() {
        DisabledHandler handler = new DisabledHandler();
        appender.setEventSnapshotHandler(handler);

        appender.doAppend(event("disabled"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"msg\":\"disabled\""));
    }

    @Test
    void snapshot_blankClassName_disablesHook() {
        assertSame(NoopEventSnapshotHandler.INSTANCE, JsonAppender.instantiateEventHandler(""));
        assertSame(NoopEventSnapshotHandler.INSTANCE, JsonAppender.instantiateEventHandler(null));
        appender.setEventSnapshotHandler("");
        appender.doAppend(event("ok"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"msg\":\"ok\""));
    }

    @Test
    void snapshot_nonExistentClass_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> appender.setEventSnapshotHandler("no.such.Class"));
    }

    @Test
    void snapshot_wrongTypeClass_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> appender.setEventSnapshotHandler("java.lang.String"));
    }

    @Test
    void snapshot_classNameConfig_instantiatesHandler() {
        RecordingHandler.reset();
        appender.setEventSnapshotHandler(RecordingHandler.class.getName());
        appender.doAppend(event("via class name"));

        assertEquals(1, RecordingHandler.received.size(), "class-name handler must receive snapshots");
        String snapshot = new String(RecordingHandler.received.get(0), StandardCharsets.UTF_8);
        assertTrue(snapshot.contains("\"msg\":\"via class name\""), "unexpected snapshot: " + snapshot);
    }

    @Test
    void snapshot_rollingAppender_supportsHook() throws Exception {
        JsonAppenderRolling rolling = new JsonAppenderRolling();
        ByteArrayOutputStream rollingOut = new ByteArrayOutputStream();
        rolling.setOutputStream(rollingOut);

        SnapshotList handler = new SnapshotList();
        rolling.setEventSnapshotHandler(handler);
        rolling.writeOut(event("rolling snapshot"));

        assertEquals(1, handler.snapshots.size());
        String snapshot = new String(handler.snapshots.get(0), StandardCharsets.UTF_8);
        assertTrue(snapshot.contains("\"msg\":\"rolling snapshot\""));
        assertTrue(rollingOut.toString(StandardCharsets.UTF_8).contains("\"msg\":\"rolling snapshot\""));
    }
}
