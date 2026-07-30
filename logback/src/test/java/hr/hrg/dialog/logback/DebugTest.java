package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DebugTest {

    @Test
    void encoderDirectTest() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.debug");
        logger.setLevel(Level.TRACE);

        CustomJsonEncoder encoder = new CustomJsonEncoder();
        encoder.setContext(context);
        encoder.start();

        LoggingEvent event = new LoggingEvent(
            "test.debug", logger, Level.INFO, "hello world", null, null
        );
        event.setTimeStamp(System.currentTimeMillis());

        byte[] encoded = encoder.encode(event);
        String output = new String(encoded, StandardCharsets.UTF_8);
        System.err.println("DEBUG: encoder output = '" + output + "'");

        assertTrue(encoded.length > 0, "encoded bytes should not be empty");
        assertTrue(output.contains("hello world"), "output should contain the message");
        assertTrue(output.contains("\"msg\""), "output should have msg field");
        assertTrue(output.contains("\"level\""), "output should have level field");
        assertTrue(output.contains("\"ts\""), "output should have ts field");
    }
}
