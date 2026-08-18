package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link JsonAppenderDev} and {@link JsonAppenderRollingDev} route
 * events through {@link JsonLogWriterDev} (missingKeys field present), while
 * the production appenders stay clean.
 */
class JsonAppenderDevTest {

    private LoggerContext context;
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() {
        // SLF4J-bound context: LoggingEvent needs a valid MDC adapter (see JsonAppenderTest).
        context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        out = new ByteArrayOutputStream();
    }

    private LoggingEvent event(String message) {
        Logger logger = context.getLogger("test.devapp");
        logger.setLevel(Level.INFO);
        LoggingEvent event = new LoggingEvent("test.devapp", logger, Level.INFO, message, null, null);
        event.setTimeStamp(123456789L);
        return event;
    }

    private JsonAppender startAppender(JsonAppender appender) {
        appender.setContext(context);
        appender.setOutputStream(out);
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        appender.setEncoder(encoder);
        appender.start();
        return appender;
    }

    @Test
    void devAppender_emitsMissingKeys() {
        LoggingEvent event = event("User {user} logged from {ip}");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class},
                List.of(new KeyValuePair("user", "alice")));

        startAppender(new JsonAppenderDev()).doAppend(event);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"missingKeys\":[\"ip\"]"),
                "dev appender must report missing keys: " + out);
    }

    @Test
    void devAppender_noMissingKeys_whenSatisfied() {
        startAppender(new JsonAppenderDev()).doAppend(event("plain message"));
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("missingKeys"));
    }

    @Test
    void productionAppender_staysClean() {
        LoggingEvent event = event("User {user} logged from {ip}");
        applyIfPresent(event, "setKeyValuePairs", new Class<?>[]{List.class},
                List.of(new KeyValuePair("user", "alice")));

        startAppender(new JsonAppender()).doAppend(event);
        assertFalse(out.toString(StandardCharsets.UTF_8).contains("missingKeys"),
                "production appender must not be affected: " + out);
    }

    @Test
    void devRollingAppender_emitsMissingKeys() throws Exception {
        JsonAppenderRollingDev appender = new JsonAppenderRollingDev();
        appender.setOutputStream(out);
        appender.writeOut(event("order {orderId} processed"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"missingKeys\":[\"orderId\"]"),
                "rolling dev appender must report missing keys: " + out);
    }

    private static void applyIfPresent(Object target, String methodName, Class<?>[] argTypes, Object arg) {
        try {
            Method method = target.getClass().getMethod(methodName, argTypes);
            method.invoke(target, arg);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("setter " + methodName + " not available on LoggingEvent", e);
        }
    }
}
