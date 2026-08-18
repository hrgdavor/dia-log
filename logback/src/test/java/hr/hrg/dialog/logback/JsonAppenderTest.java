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
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JsonAppender}: full append lifecycle (JSON + newline),
 * stream replacement, and {@code stackTraceFilter} configuration.
 */
class JsonAppenderTest {

    /** Public no-arg Predicate used by setStackTraceFilter. */
    public static class AcceptAllFilter implements Predicate<String> {
        public AcceptAllFilter() {
        }

        @Override
        public boolean test(String s) {
            return true;
        }
    }

    private LoggerContext context;
    private JsonAppender appender;
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() {
        // Use the SLF4J-bound context (initializes the logback MDC adapter that
        // LoggingEvent.prepareForDeferredProcessing() requires); a bare
        // new LoggerContext() leaves mdcAdapter null and doAppend() NPEs.
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
        Logger logger = context.getLogger("test.appender");
        logger.setLevel(Level.INFO);
        LoggingEvent event = new LoggingEvent("test.appender", logger, Level.INFO, message, null, null);
        event.setTimeStamp(123456789L);
        return event;
    }

    @Test
    void append_writesJsonEventWithNewline() {
        appender.doAppend(event("hello appender"));

        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"msg\":\"hello appender\""), "msg missing: " + json);
        assertTrue(json.contains("\"level\":\"INFO\""), "level missing: " + json);
        assertTrue(json.endsWith("\n"), "each event must end with a newline: " + json);
    }

    @Test
    void append_multipleEvents_eachOnOwnLine() {
        appender.doAppend(event("first"));
        appender.doAppend(event("second"));

        String json = out.toString(StandardCharsets.UTF_8);
        String[] lines = json.split("\n");
        assertEquals(2, lines.length, "each event must be one JSON line: " + json);
        assertTrue(lines[0].contains("\"msg\":\"first\""));
        assertTrue(lines[1].contains("\"msg\":\"second\""));
    }

    @Test
    void setOutputStream_replacesTarget() {
        appender.doAppend(event("before"));
        String first = out.toString(StandardCharsets.UTF_8);
        assertTrue(first.contains("\"msg\":\"before\""), "first event must go to the first stream: " + first);

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        appender.setOutputStream(out2);
        appender.doAppend(event("after"));

        assertTrue(out2.toString(StandardCharsets.UTF_8).contains("\"msg\":\"after\""),
                "subsequent events must go to the new stream");
    }

    @Test
    void stackTraceFilter_blank_resetsToAcceptAll() {
        appender.setStackTraceFilter("");
        appender.setStackTraceFilter(null);
        // no exception, default filter applied
        appender.doAppend(event("filtered"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"msg\":\"filtered\""));
    }

    @Test
    void stackTraceFilter_nonExistentClass_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> appender.setStackTraceFilter("no.such.Class"));
    }

    @Test
    void stackTraceFilter_nonPredicateClass_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> appender.setStackTraceFilter("java.lang.String"));
    }

    @Test
    void stackTraceFilter_validPredicate_isAccepted() {
        appender.setStackTraceFilter(AcceptAllFilter.class.getName());
        appender.doAppend(event("with filter"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"msg\":\"with filter\""));
    }

    @Test
    void startsWithoutEncoder_andWritesJson() {
        // New appender with NO encoder configured — start() must install a no-op one.
        JsonAppender noEncoderAppender = new JsonAppender();
        noEncoderAppender.setContext(context);
        ByteArrayOutputStream noEncOut = new ByteArrayOutputStream();
        noEncoderAppender.setOutputStream(noEncOut);
        noEncoderAppender.start();

        assertTrue(noEncoderAppender.isStarted(), "appender must start without an <encoder>");
        assertNotNull(noEncoderAppender.getEncoder(), "a no-op encoder must be installed");

        noEncoderAppender.doAppend(event("no encoder needed"));
        assertTrue(noEncOut.toString(StandardCharsets.UTF_8).contains("\"msg\":\"no encoder needed\""),
                "events must be written without a configured encoder");
    }

    @Test
    void exceptionEvent_includesErrHash() {
        LoggingEvent event = event("boom");
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(new RuntimeException("boom")));

        appender.doAppend(event);

        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"errHash\":"), "errHash missing: " + json);
        assertTrue(json.contains("\"stack\":"), "stack missing: " + json);
    }
}
