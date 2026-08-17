package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TraceId} — generation, uniqueness, byte/string consistency,
 * and span-id non-zero guarantee. (Planned coverage item from plans/analysis-report.md §11.)
 */
class TraceIdTest {

    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern HEX16 = Pattern.compile("[0-9a-f]{16}");
    private static final String ZERO_SPAN = "0000000000000000";

    @Test
    void generateTraceId_is32LowercaseHexChars() {
        String id = TraceId.generateTraceId();
        assertEquals(32, id.length(), "trace id must be 32 hex chars");
        assertTrue(HEX32.matcher(id).matches(), "trace id must be lowercase hex: " + id);
    }

    @Test
    void generateTraceId_isUniqueAcrossCalls() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = TraceId.generateTraceId();
            assertTrue(ids.add(id), "duplicate trace id at iteration " + i + ": " + id);
        }
    }

    @Test
    void generateTraceIdBytes_is16Bytes() {
        byte[] bytes = TraceId.generateTraceIdBytes();
        assertEquals(16, bytes.length, "trace id bytes must be 16");
    }

    @Test
    void constructor_bytesAndStringAreConsistent() {
        TraceId id = new TraceId();

        byte[] bytes = id.getBytes();
        assertEquals(16, bytes.length);

        String string = id.getString();
        assertEquals(32, string.length());

        assertEquals(TraceId.HEX_LOWERCASE.formatHex(bytes), string,
                "string form must be lowercase hex of the byte form");
        assertEquals(string, id.toString(), "toString() must equal the string form");
    }

    @Test
    void generateSpanId_is16LowercaseHexChars() {
        String span = TraceId.generateSpanId();
        assertEquals(16, span.length(), "span id must be 16 hex chars");
        assertTrue(HEX16.matcher(span).matches(), "span id must be lowercase hex: " + span);
    }

    @Test
    void generateSpanId_isNeverZero() {
        for (int i = 0; i < 1000; i++) {
            String span = TraceId.generateSpanId();
            assertNotEquals(ZERO_SPAN, span, "span id must never be all zeros (reserved invalid value)");
        }
    }
}
