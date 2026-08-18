package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the experimental {@link ZeroCopyDirectAppender}: writing an event
 * directly through the Jackson generator, bypassing the encoder pipeline.
 */
class ZeroCopyDirectAppenderTest {

    @Test
    void doAppend_writesMinimalJsonDirectly() {
        LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ZeroCopyDirectAppender appender = new ZeroCopyDirectAppender();
        appender.setContext(context);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appender.setOutputStream(out);

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        appender.setEncoder(encoder);
        appender.start();

        Logger logger = context.getLogger("test.zero");
        logger.setLevel(Level.INFO);
        LoggingEvent event = new LoggingEvent("test.zero", logger, Level.INFO, "hello zero", null, null);
        event.setTimeStamp(123456789L);

        appender.doAppend(event);

        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"message\":\"hello zero\""), "message field missing: " + json);
        assertTrue(json.contains("\"level\":\"INFO\""), "level field missing: " + json);
        assertTrue(json.contains("\"timestamp\":123456789"), "timestamp field missing: " + json);
        assertTrue(json.endsWith("\n"), "must end with newline: " + json);
    }

    @Test
    void notStarted_doesNothing() {
        ZeroCopyDirectAppender appender = new ZeroCopyDirectAppender();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        appender.setOutputStream(out);

        // doAppend before start(): must not throw and must not write
        appender.doAppend(null);
        assertEquals(0, out.size());
    }
}
