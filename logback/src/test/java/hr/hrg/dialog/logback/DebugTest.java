package hr.hrg.dialog.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DebugTest {

    @Test
    void encoderDirectTest() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test.debug");
        logger.setLevel(Level.TRACE);

        LoggingEvent event = new LoggingEvent(
            "test.debug", logger, Level.INFO, "hello world", null, null
        );
        event.setTimeStamp(System.currentTimeMillis());

        byte[] encoded = encodeEvent(event);
        String output = new String(encoded, StandardCharsets.UTF_8);
        System.err.println("DEBUG: encoder output = '" + output + "'");

        assertTrue(encoded.length > 0, "encoded bytes should not be empty");
        assertTrue(output.contains("hello world"), "output should contain the message");
        assertTrue(output.contains("\"msg\""), "output should have msg field");
        assertTrue(output.contains("\"level\""), "output should have level field");
        assertTrue(output.contains("\"timestamp\""), "output should have timestamp field");
    }

    private static byte[] encodeEvent(LoggingEvent event) {
        JsonFactory jsonFactory = new JsonFactory();
        ObjectWriteContext writeCtxt = ObjectWriteContext.empty();
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        try (JsonGenerator g = jsonFactory.createGenerator(writeCtxt, os)) {
            g.writeStartObject();
            g.writeNumberProperty("timestamp", event.getTimeStamp());
            g.writeStringProperty("level", event.getLevel().toString());
            g.writeStringProperty("msg", event.getFormattedMessage());
            g.writeEndObject();
            g.flush();
            os.write('\n');
        }

        return os.toByteArray();
    }
}
