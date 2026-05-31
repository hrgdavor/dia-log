package hr.hrg.dialog.logback;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ContextBase;

/**
 * Unit tests for {@link ConsoleAppenderDev} placeholder expansion.
 */
public class ConsoleAppenderDevTest {

    private ConsoleAppenderDev<LoggingEvent> appender;
    private ByteArrayOutputStream baos;

    @BeforeEach
    void setUp() {
        appender = new ConsoleAppenderDev<>();
        appender.setContext(new ContextBase());
        appender.start();

        baos = new ByteArrayOutputStream();
    }

    @Test
    void testBasicExpansion() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "User {user} logged in");
        event.addKeyValuePair(new KeyValuePair("user", "alice"));

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("User alice logged in"),
                "Should expand {user} to alice. Got: " + output);
    }

    @Test
    void testMultiplePlaceholders() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "{user} bought {count} items for ${price}");
        event.addKeyValuePair(new KeyValuePair("user", "bob"));
        event.addKeyValuePair(new KeyValuePair("count", 3));
        event.addKeyValuePair(new KeyValuePair("price", 42.50));

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("bob bought 3 items for $42.5"),
                "Should expand all placeholders. Got: " + output);
    }

    @Test
    void testSlf4jPositionalBracesUntouched() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "User {} logged from IP {}");
        event.addKeyValuePair(new KeyValuePair("extra", "value"));

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("User {} logged from IP {}"),
                "SLF4J-style {} should remain untouched. Got: " + output);
    }

    @Test
    void testNumericPlaceholdersUntouched() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "Arg {0} and {1}");
        event.addKeyValuePair(new KeyValuePair("0", "should-not-expand"));

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("{0}"),
                "Numeric placeholder {0} should remain untouched. Got: " + output);
        assertTrue(output.contains("{1}"),
                "Numeric placeholder {1} should remain untouched. Got: " + output);
    }

    @Test
    void testMissingKeyLeavesPlaceholder() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "Hello {unknown} world");

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("{unknown}"),
                "Missing key should leave placeholder as-is. Got: " + output);
    }

    @Test
    void testNoPlaceholdersNoChange() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "Plain message without placeholders");

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("Plain message without placeholders"));
    }

    @Test
    void testEmptyMessage() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "");

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertFalse(output.isEmpty(), "Should still output even with empty message");
    }

    @Test
    void testNullKeyValuePairs() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "Message");

        // No kv pairs added
        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("Message"));
    }

    @Test
    void testExpansionDisabled() throws Exception {
        appender.setExpandPlaceholders(false);

        LoggingEvent event = createEvent(Level.INFO, "Hello {user}");
        event.addKeyValuePair(new KeyValuePair("user", "alice"));

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("{user}"),
                "With expandPlaceholders=false, {user} should remain. Got: " + output);
    }

    @Test
    void testAllLogLevels() throws Exception {
        for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)) {
            baos.reset();
            appender.setOutputStream(baos);

            LoggingEvent event = createEvent(level, "Test {level} log", level.toString());
            appender.writeOut(event);

            String output = baos.toString("UTF-8");
            // Level name should appear in the output (5-char fixed width)
            assertTrue(output.contains("Test " + level.toString() + " log"),
                    "Level " + level + " output contains expanded msg. Got: " + output);
        }
    }

    @Test
    void testSpecialCharactersInValues() throws Exception {
        LoggingEvent event = createEvent(Level.INFO, "Path {path} and {name}");
        event.addKeyValuePair(new KeyValuePair("path", "C:\\Users\\test\\file.txt"));
        event.addKeyValuePair(new KeyValuePair("name", "John \"Jack\" Doe"));

        appender.setOutputStream(baos);
        appender.writeOut(event);

        String output = baos.toString("UTF-8");
        assertTrue(output.contains("C:\\Users\\test\\file.txt"),
                "Path with backslashes: " + output);
        assertTrue(output.contains("John \"Jack\" Doe"),
                "Name with quotes: " + output);
    }

    // ---- Helper ----

    private LoggingEvent createEvent(Level level, String message, String... kvArgs) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName("test.Logger");
        event.setMessage(message);
        event.setTimeStamp(System.currentTimeMillis());
        event.setThreadName(Thread.currentThread().getName());

        for (int i = 1; i < kvArgs.length; i += 2) {
            event.addKeyValuePair(new KeyValuePair(kvArgs[i - 1], kvArgs[i]));
        }

        return event;
    }
}