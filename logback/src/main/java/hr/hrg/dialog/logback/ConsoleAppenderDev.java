package hr.hrg.dialog.logback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.event.KeyValuePair;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

/**
 * Development-time console appender that expands {@code {name}} placeholders
 * from the event's key-value pairs for human-readable output.
 * <p>
 * In production, use {@link ConsoleAppenderJson} which keeps {@code {name}}
 * literal in the {@code msg} field and stores the values in {@code kv}.
 * This appender is for development only — it substitutes those values
 * back into the message for easy reading.
 * </p>
 *
 * <pre>{@code
 * <appender name="DEV" class="hr.hrg.dialog.logback.ConsoleAppenderDev">
 *     <expandPlaceholders>true</expandPlaceholders>
 *     <warnOnMissingKeys>true</warnOnMissingKeys>
 * </appender>
 * }</pre>
 *
 * With log message:
 * <pre>{@code log.info("User {user} logged from {ip}", "user", "alice", "ip", "10.0.0.1");}</pre>
 *
 * Output:
 * <pre>12:34:56.789 [main] INFO  c.e.MyClass - User alice logged from 10.0.0.1</pre>
 *
 * When {@code warnOnMissingKeys=true} and a key is missing:
 * <pre>
 * 12:34:56.789 [main] INFO  c.e.MyClass - User {user} logged from 10.0.0.1 ⚠ MISSING KEYS: user
 * java.lang.Throwable: Missing keys: user
 * 	at com.example.MyClass.main(MyClass.java:15)
 * 	...
 * </pre>
 */
public class ConsoleAppenderDev<E> extends ConsoleAppender<E> {

    private static final DateTimeFormatter TIME_FORMAT = 
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final byte[] SPACE = " ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OPEN_BRACKET = "[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CLOSE_BRACKET = "] ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DASH_SPACE = " - ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NL = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_TRACE = "TRACE".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_DEBUG = "DEBUG".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_INFO  = "INFO ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_WARN  = "WARN ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LEVEL_ERROR = "ERROR".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MISSING_PREFIX = " ⚠ MISSING KEYS: ".getBytes(StandardCharsets.UTF_8);

    /** Whether to expand {name} placeholders from kv pairs. Default true. */
    private boolean expandPlaceholders = true;

    /** When true, append an error + throwable stack trace for missing keys. Default false. */
    private boolean warnOnMissingKeys = false;

    public void setExpandPlaceholders(boolean expandPlaceholders) {
        this.expandPlaceholders = expandPlaceholders;
    }

    public boolean isExpandPlaceholders() {
        return expandPlaceholders;
    }

    public void setWarnOnMissingKeys(boolean warnOnMissingKeys) {
        this.warnOnMissingKeys = warnOnMissingKeys;
    }

    public boolean isWarnOnMissingKeys() {
        return warnOnMissingKeys;
    }

    @Override
    protected void writeOut(E event) throws IOException {
        if (!(event instanceof ILoggingEvent le)) {
            super.writeOut(event);
            return;
        }

        streamWriteLock.lock();
        try {
            OutputStream out = getOutputStream();
            writeDevLine(le, out);
            out.write(NL);
            out.flush();
        } finally {
            streamWriteLock.unlock();
        }
    }

    private void writeDevLine(ILoggingEvent event, OutputStream out) throws IOException {
        // Timestamp
        out.write(TIME_FORMAT.format(Instant.ofEpochMilli(event.getTimeStamp())).getBytes(StandardCharsets.UTF_8));
        out.write(SPACE);

        // Thread
        out.write(OPEN_BRACKET);
        out.write(event.getThreadName().getBytes(StandardCharsets.UTF_8));
        out.write(CLOSE_BRACKET);

        // Level (fixed-width 5 chars)
        out.write(levelBytes(event.getLevel().toString()));
        out.write(SPACE);

        // Logger name
        out.write(event.getLoggerName().getBytes(StandardCharsets.UTF_8));
        out.write(DASH_SPACE);

        // Message with placeholders expanded
        String message = event.getFormattedMessage();
        List<String> missingKeys = null;

        if (expandPlaceholders) {
            MissingKeyResult result = expandMessageTracked(message, event.getKeyValuePairs());
            message = result.message;
            missingKeys = result.missingKeys;
        }

        out.write(message.getBytes(StandardCharsets.UTF_8));

        // If warnOnMissingKeys is enabled and there are missing keys, append error and throwable
        if (warnOnMissingKeys && missingKeys != null && !missingKeys.isEmpty()) {
            String missingList = String.join(", ", missingKeys);
            out.write(MISSING_PREFIX);
            out.write(missingList.getBytes(StandardCharsets.UTF_8));
            out.write(NL);

            // Write a throwable stack trace to show where the log was called from
            Throwable missingKeyError = new Throwable("Missing keys: " + missingList);
            writeThrowable(out, missingKeyError);
        }
    }

    /**
     * Write a throwable's stack trace to the output stream, similar to how
     * Logback's default exception handling works.
     */
    private static void writeThrowable(OutputStream out, Throwable t) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        t.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8.name()));
        out.write(baos.toByteArray());
    }

    // ---- Message expansion with missing key tracking ----

    static class MissingKeyResult {
        final String message;
        final List<String> missingKeys;

        MissingKeyResult(String message, List<String> missingKeys) {
            this.message = message;
            this.missingKeys = missingKeys;
        }
    }

    /**
     * Expands {@code {name}} placeholders and tracks which keys were missing.
     */
    static MissingKeyResult expandMessageTracked(String message, List<KeyValuePair> pairs) {
        if (pairs == null || pairs.isEmpty() || message == null) {
            return new MissingKeyResult(message, List.of());
        }

        List<String> missing = new ArrayList<>();
        StringBuilder sb = new StringBuilder(message.length() + 64);
        int i = 0;
        int len = message.length();

        while (i < len) {
            int braceStart = message.indexOf('{', i);
            if (braceStart < 0 || braceStart + 1 >= len) {
                sb.append(message, i, len);
                break;
            }

            int braceEnd = message.indexOf('}', braceStart + 1);
            if (braceEnd < 0) {
                sb.append(message, i, len);
                break;
            }

            // Empty braces '{}' — skip, leave as-is for SLF4J
            if (braceEnd == braceStart + 1) {
                sb.append(message, i, braceEnd + 1);
                i = braceEnd + 1;
                continue;
            }

            String key = message.substring(braceStart + 1, braceEnd);

            // Numeric placeholder — leave as-is
            if (isNumeric(key)) {
                sb.append(message, i, braceEnd + 1);
                i = braceEnd + 1;
                continue;
            }

            // Append everything before the '{'
            sb.append(message, i, braceStart);

            // Try to find a matching value
            String value = findValue(pairs, key);
            if (value != null) {
                sb.append(value);
            } else {
                // No match — keep placeholder and track as missing
                sb.append('{').append(key).append('}');
                missing.add(key);
            }

            i = braceEnd + 1;
        }

        return new MissingKeyResult(sb.toString(), missing);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String findValue(List<KeyValuePair> pairs, String key) {
        for (KeyValuePair kv : pairs) {
            if (kv.key != null && kv.key.equals(key) && kv.value != null) {
                return kv.value.toString();
            }
        }
        return null;
    }

    private static byte[] levelBytes(String level) {
        return switch (level) {
            case "TRACE" -> LEVEL_TRACE;
            case "DEBUG" -> LEVEL_DEBUG;
            case "INFO"  -> LEVEL_INFO;
            case "WARN"  -> LEVEL_WARN;
            case "ERROR" -> LEVEL_ERROR;
            default -> (level + "    ".substring(0, Math.max(0, 5 - level.length()))).getBytes(StandardCharsets.UTF_8);
        };
    }
}