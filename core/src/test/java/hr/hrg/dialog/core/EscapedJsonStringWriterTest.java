package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EscapedJsonStringWriter}: null/empty handling, mandatory
 * escaping of quotes/backslash/control chars, and UTF-8 encoding of non-ASCII
 * (Latin-1 extended and surrogate pairs). Works for both the VarHandle fast path
 * (with --add-opens) and the classic fallback.
 */
class EscapedJsonStringWriterTest {

    private static String write(String value) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EscapedJsonStringWriter.writeJsonStringOrNull(out, value);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void null_writesJsonNull() throws Exception {
        assertEquals("null", write(null));
    }

    @Test
    void emptyString_writesQuotes() throws Exception {
        assertEquals("\"\"", write(""));
    }

    @Test
    void plainAscii_isUnchanged() throws Exception {
        assertEquals("\"hello world\"", write("hello world"));
    }

    @Test
    void quote_isEscaped() throws Exception {
        assertEquals("\"a\\\"b\"", write("a\"b"));
    }

    @Test
    void backslash_isEscaped() throws Exception {
        assertEquals("\"a\\\\b\"", write("a\\b"));
    }

    @Test
    void namedControlChars_useShortEscapes() throws Exception {
        assertEquals("\"\\b\"", write("\b"));
        assertEquals("\"\\f\"", write("\f"));
        assertEquals("\"\\n\"", write("\n"));
        assertEquals("\"\\r\"", write("\r"));
        assertEquals("\"\\t\"", write("\t"));
    }

    @Test
    void otherControlChars_useUnicodeEscape() throws Exception {
        assertEquals("\"\\u0001\"", write("\u0001"));
        // hex digits are uppercase for values > 9 (both forms are valid JSON)
        assertEquals("\"\\u001F\"", write("\u001f"));
    }

    @Test
    void latin1Extended_isEncodedAsUtf8() throws Exception {
        assertEquals("\"héllo wörld\"", write("héllo wörld"));
    }

    @Test
    void surrogatePair_isEncodedAsUtf8() throws Exception {
        assertEquals("\"🚀\"", write("🚀"));
    }

    @Test
    void mixedEscapesAndUnicode() throws Exception {
        assertEquals("\"a\\n\\\"héllo🚀\\t\"", write("a\n\"héllo🚀\t"));
    }

    @Test
    void escapeAtStartAndEnd() throws Exception {
        assertEquals("\"\\\"x\\\"\"", write("\"x\""));
        assertEquals("\"\\\\\"", write("\\"));
    }

    @Test
    void onlyEscapeWhenNeeded_noDoubleEscaping() throws Exception {
        // Backslash followed by a normal char must not be treated as an escape
        assertEquals("\"a\\\\nb\"", write("a\\nb"));
    }
}
