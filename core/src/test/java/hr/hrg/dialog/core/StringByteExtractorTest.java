package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StringByteExtractor}: classic fallback, Latin-1 encoding,
 * ASCII direct writing, and the active strategy (VarHandle path when
 * --add-opens is available, classic otherwise).
 */
class StringByteExtractorTest {

    private static String write(IOExceptionThrowing writer) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private interface IOExceptionThrowing {
        void write(ByteArrayOutputStream out) throws IOException;
    }

    @Test
    void writeClassic_encodesUtf8() throws Exception {
        String result = write(out -> StringByteExtractor.writeClassic(out, "héllo 🚀"));
        assertEquals("héllo 🚀", result);
    }

    @Test
    void writeLatin1_asciiIsSingleByte() throws Exception {
        String result = write(out -> StringByteExtractor.writeLatin1(out, "abc".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("abc", result);
    }

    @Test
    void writeLatin1_extendedCharsBecomeTwoByteUtf8() throws Exception {
        // 'é' is 0xE9 in Latin-1; as UTF-8 it must be C3 A9 (two bytes)
        String result = write(out -> StringByteExtractor.writeLatin1(out, "é".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("é", result);
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        assertEquals(2, bytes.length);
        assertEquals((byte) 0xC3, bytes[0]);
        assertEquals((byte) 0xA9, bytes[1]);
    }

    @Test
    void writeAsciiDirect_nullAndEmpty_writeNothing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringByteExtractor.writeAsciiDirect(out, null);
        StringByteExtractor.writeAsciiDirect(out, "");
        assertEquals(0, out.size());
    }

    @Test
    void writeAsciiDirect_writesString() throws Exception {
        String result = write(out -> StringByteExtractor.writeAsciiDirect(out, "direct"));
        assertEquals("direct", result);
    }

    @Test
    void activeStrategy_writesAscii() throws Exception {
        String result = write(out -> StringByteExtractor.getStrategy().write(out, "strategy"));
        assertEquals("strategy", result);
    }

    @Test
    void activeStrategy_writesLatin1Extended() throws Exception {
        // With --add-opens this exercises the VarHandle writeLatin1 path;
        // otherwise it falls back to the classic path. Both must produce UTF-8.
        String result = write(out -> StringByteExtractor.getStrategy().write(out, "héllo"));
        assertEquals("héllo", result);
    }

    @Test
    void activeStrategy_writesUtf16StringViaClassic() throws Exception {
        // Non-Latin-1 strings always take the classic UTF-8 path
        String result = write(out -> StringByteExtractor.getStrategy().write(out, "🚀"));
        assertEquals("🚀", result);
    }

    @Test
    void strategyIsAlwaysAvailable() {
        assertNotNull(StringByteExtractor.getStrategy());
    }
}
