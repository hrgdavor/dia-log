package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The pre-optimization event emission path of {@link JsonLogWriter}, kept in
 * shape for before/after performance comparison (T1–T6, ported from Apache
 * Fory commit 585eb16f, "feat(java): optimize json perf", PR #3871).
 *
 * <p>It writes the five fixed fields ({@code ts}, {@code level},
 * {@code logger}, {@code thread}, {@code msg}) using the old mechanisms:
 *
 * <ul>
 *   <li><b>T6 before</b> — field prefixes as {@code byte[]} via two
 *       {@code OutputStream} calls ({@code write(',')} + {@code write(bytes)});</li>
 *   <li><b>T1/T2 before</b> — per-char escape scan with a {@code switch}
 *       (the old {@code writeEscapedJsonStringClassic});</li>
 *   <li><b>T5 before</b> — digit-by-digit {@code writeLong} (divide by 10 per
 *       digit) into a reusable 20-byte buffer;</li>
 *   <li><b>T3/T4 before</b> — every byte/chunk through the virtual
 *       {@code OutputStream} interface.</li>
 * </ul>
 *
 * Output is byte-identical to {@link JsonLogWriter} for the fields it emits
 * (the equality is asserted by the Fory event benchmark setup paths and by
 * {@code JsonLogWriterDirectBufferTest} for the production writer). It is a
 * benchmark fixture only — not used in production.
 */
public final class ClassicJsonLogEventWriter {

    private static final byte[] KEY_TS = "\"ts\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_LEVEL = "\"level\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_LOGGER = "\"logger\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_THREAD = "\"thread\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_MSG = "\"msg\":".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_NULL = "null".getBytes(StandardCharsets.UTF_8);

    private static final byte[] ESCAPED_QUOTE = "\\\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_BACKSLASH = "\\\\".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_B = "\\b".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_F = "\\f".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_N = "\\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_R = "\\r".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESCAPED_T = "\\t".getBytes(StandardCharsets.UTF_8);

    private static final int MAX_LONG_BYTES = 20;
    private static final byte[] MIN_LONG_BYTES = "-9223372036854775808".getBytes(StandardCharsets.UTF_8);

    private final byte[] longBuffer = new byte[MAX_LONG_BYTES];

    /** Writes {@code {ts:...,"level":...,"logger":...,"thread":...,"msg":...}} exactly like the old writer. */
    public void writeEvent(ILoggingEvent event, OutputStream out) throws IOException {
        // API parity with JsonLogWriter: touch the same event accessors so the
        // before/after comparison is not skewed by logback event-API overhead
        // (results are discarded; only the accessor cost is shared).
        event.getMDCPropertyMap();
        event.getKeyValuePairs();
        event.getThrowableProxy();

        out.write('{');
        out.write(KEY_TS);
        writeLong(out, event.getTimeStamp());

        writeField(out, KEY_LEVEL);
        writeStringOrNull(out, event.getLevel() != null ? event.getLevel().toString() : null);

        writeField(out, KEY_LOGGER);
        writeStringOrNull(out, event.getLoggerName());

        writeField(out, KEY_THREAD);
        writeStringOrNull(out, event.getThreadName());

        writeField(out, KEY_MSG);
        writeStringOrNull(out, event.getFormattedMessage());

        out.write('}');
    }

    private static void writeField(OutputStream out, byte[] key) throws IOException {
        out.write(',');
        out.write(key);
    }

    private static void writeStringOrNull(OutputStream out, String value) throws IOException {
        if (value == null) {
            out.write(JSON_NULL);
            return;
        }
        out.write('"');

        int start = 0;
        int len = value.length();
        for (int i = 0; i < len; i++) {
            char ch = value.charAt(i);

            byte[] escape = switch (ch) {
                case '"' -> ESCAPED_QUOTE;
                case '\\' -> ESCAPED_BACKSLASH;
                case '\b' -> ESCAPED_B;
                case '\f' -> ESCAPED_F;
                case '\n' -> ESCAPED_N;
                case '\r' -> ESCAPED_R;
                case '\t' -> ESCAPED_T;
                default -> null;
            };

            if (escape != null || ch < 0x20) {
                if (i > start) {
                    writeUtf8Range(out, value, start, i);
                }
                if (escape != null) {
                    out.write(escape);
                } else {
                    out.write('\\');
                    out.write('u');
                    out.write('0');
                    out.write('0');
                    writeHexNibble(out, (ch >>> 4) & 0x0F);
                    writeHexNibble(out, ch & 0x0F);
                }
                start = i + 1;
            }
        }

        if (start < len) {
            writeUtf8Range(out, value, start, len);
        }
        out.write('"');
    }

    private static void writeUtf8Range(OutputStream out, String value, int start, int end) throws IOException {
        for (int i = start; i < end; i++) {
            char ch = value.charAt(i);
            if (ch <= 0x7F) {
                out.write(ch);
            } else if (ch <= 0x7FF) {
                out.write(0xC0 | (ch >> 6));
                out.write(0x80 | (ch & 0x3F));
            } else if (Character.isHighSurrogate(ch)) {
                if (i + 1 < end) {
                    char low = value.charAt(i + 1);
                    if (Character.isLowSurrogate(low)) {
                        int codePoint = Character.toCodePoint(ch, low);
                        out.write(0xF0 | (codePoint >> 18));
                        out.write(0x80 | ((codePoint >> 12) & 0x3F));
                        out.write(0x80 | ((codePoint >> 6) & 0x3F));
                        out.write(0x80 | (codePoint & 0x3F));
                        i++;
                        continue;
                    }
                }
                writeReplacementChar(out);
            } else if (Character.isLowSurrogate(ch)) {
                writeReplacementChar(out);
            } else {
                out.write(0xE0 | (ch >> 12));
                out.write(0x80 | ((ch >> 6) & 0x3F));
                out.write(0x80 | (ch & 0x3F));
            }
        }
    }

    private static void writeReplacementChar(OutputStream out) throws IOException {
        out.write(0xEF);
        out.write(0xBF);
        out.write(0xBD);
    }

    private static void writeHexNibble(OutputStream out, int nibble) throws IOException {
        out.write(nibble < 10 ? ('0' + nibble) : ('A' + (nibble - 10)));
    }

    /** Old digit-by-digit long writer (the T5 baseline). */
    private void writeLong(OutputStream out, long value) throws IOException {
        if (value == Long.MIN_VALUE) {
            out.write(MIN_LONG_BYTES);
            return;
        }
        int cursor = MAX_LONG_BYTES;
        boolean negative = value < 0;
        if (negative) value = -value;

        while (value >= 10) {
            long q = value / 10;
            int digit = (int) (value - (q * 10));
            longBuffer[--cursor] = (byte) ('0' + digit);
            value = q;
        }
        longBuffer[--cursor] = (byte) ('0' + value);

        if (negative) {
            longBuffer[--cursor] = (byte) '-';
        }
        out.write(longBuffer, cursor, MAX_LONG_BYTES - cursor);
    }
}
