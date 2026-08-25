package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boundary tests for the limit-aware (no-grow) string writers introduced in
 * step 3 of the no-grow plan: {@link WriteOps#writeEscapedJsonStringNoGrow},
 * {@link DirectJsonStringWriter#writeJsonStringNoGrow},
 * {@link DirectJsonStringWriter#writeEscapedLatin1NoGrow} and
 * {@link StringByteExtractor#writeLatin1NoGrow}.
 *
 * <p>Negated-position contract: success returns the new position; overflow
 * returns {@code -pos} (the pre-call position, negated) so the caller restores
 * its cursor and finalizes with the placeholder. Partial writes past the
 * returned position are allowed garbage that the caller never flushes — the
 * writers must never throw or report a result past {@code limit}.
 *
 * <p>Note the design's conservatism: the ≥ 32-byte block loops guard with
 * {@code pos + LIMIT_MARGIN (1024) > limit} between blocks, so long strings
 * are only admitted while ≥ 1024 bytes of headroom remain (irrelevant for the
 * production 16 MiB buffer — 0.006 %). Short strings use exact band checks.
 */
class NoGrowWritersTest {

    private static final String[] ESCAPE_SAMPLES = {
        "", "abc", "hello world",
        "quote \" and backslash \\ inside",
        "tab\there newline\nhere return\rhere backspace\bhere formfeed\fhere",
        "control \u0001\u001F\u0000",
        "héllo wörld — naïve café",
        "🚀 emoji and ☕",
        "lone surrogate \uD800 and \uDFFF",
        "mixed: \"quoted\" \\backslash\\ \u0007 end"
    };

    /** Oracle: the pre-existing pure (capacity-assumed) escaped-string writer. */
    private static int pureEscaped(byte[] buf, int pos, String s) {
        return WriteOps.writeEscapedJsonString(buf, pos, s);
    }

    private static byte[] slice(byte[] buf, int from, int to) {
        return Arrays.copyOfRange(buf, from, to);
    }

    // ---- WriteOps.writeEscapedJsonStringNoGrow ------------------------------

    @Test
    void writeEscapedJsonStringNoGrow_fits_matchesPureOverload() {
        for (String s : ESCAPE_SAMPLES) {
            byte[] buf = new byte[4096];
            byte[] expected = new byte[4096];
            int pos = 16;
            int limit = 3000;
            int p = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, s);
            assertTrue(p > 0, "must fit: " + s);
            int ep = pureEscaped(expected, pos, s);
            assertArrayEquals(slice(expected, pos, ep), slice(buf, pos, p), "bytes for: " + s);
            assertEquals(ep, p, "position for: " + s);
        }
    }

    @Test
    void writeEscapedJsonStringNoGrow_null_packedLiteral() {
        byte[] buf = new byte[64];
        int pos = 8;
        // the packed "null" store reserves a full 8-byte slot
        int p = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, pos + 8, null);
        assertEquals(pos + 4, p);
        assertEquals("null", new String(buf, pos, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void writeEscapedJsonStringNoGrow_shortString_fitsAndConservativeOverflow() {
        byte[] buf = new byte[64];
        int pos = 10;
        // "hello" escapes to 7 bytes; bodyLimit = limit - 2 reserves the closing
        // quote + 1 headroom, so it needs limit >= pos + 8.
        int p = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, pos + 8, "hello");
        assertEquals(pos + 7, p);
        // one byte short: conservatively rejected with the negated position
        assertEquals(-pos, WriteOps.writeEscapedJsonStringNoGrow(buf, pos, pos + 7, "hello"));
    }

    @Test
    void writeEscapedJsonStringNoGrow_overflow_returnsNegatedInputPos() {
        byte[] buf = new byte[64];
        int pos = 10;
        // no room at all / room for the opening quote only
        assertEquals(-pos, WriteOps.writeEscapedJsonStringNoGrow(buf, pos, pos, "x"));
        assertEquals(-pos, WriteOps.writeEscapedJsonStringNoGrow(buf, pos, pos + 2, "x"));
        // null literal needs a full 8-byte packed slot
        assertEquals(-pos, WriteOps.writeEscapedJsonStringNoGrow(buf, pos, pos + 7, null));
    }

    @Test
    void writeEscapedJsonStringNoGrow_sweepLimits_neverReportsPastLimit() {
        for (String s : ESCAPE_SAMPLES) {
            byte[] expected = new byte[4096];
            int pos = 16;
            int ep = pureEscaped(expected, pos, s);
            int needed = ep - pos;
            for (int extra = -2; extra <= 16; extra++) {
                int limit = pos + Math.max(0, needed + extra);
                byte[] probe = new byte[4096];
                int r = WriteOps.writeEscapedJsonStringNoGrow(probe, pos, limit, s);
                if (r >= 0) {
                    assertTrue(r <= limit,
                            "result must never pass limit (s=" + s + ", limit=" + limit + ")");
                    assertArrayEquals(slice(expected, pos, ep), slice(probe, pos, r),
                            "bytes for s=" + s + " limit=" + limit);
                } else {
                    assertEquals(-pos, r,
                            "negated pre-call position (s=" + s + ", limit=" + limit + ")");
                }
            }
        }
    }

    // ---- DirectJsonStringWriter.writeJsonStringNoGrow ------------------------

    @Test
    void writeJsonStringNoGrow_matchesPureOverload() {
        for (String s : ESCAPE_SAMPLES) {
            byte[] buf = new byte[4096];
            byte[] expected = new byte[4096];
            int pos = 16;
            int limit = 3000;
            int p = DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, limit, s);
            assertTrue(p > 0, "must fit: " + s);
            int ep = pureEscaped(expected, pos, s);
            assertArrayEquals(slice(expected, pos, ep), slice(buf, pos, p), "bytes for: " + s);
            assertEquals(ep, p, "position for: " + s);
        }
    }

    @Test
    void writeJsonStringNoGrow_longString_blockLoop_matches() {
        // >= 32 source chars -> 16-byte block loop with the LIMIT_MARGIN guard;
        // big buffer so the guard never fires and the output lands well below
        // the 1024-byte headroom.
        String s = "a".repeat(200) + "\u0001\u001F" + "🚀 surrogates ☕ tail";
        byte[] buf = new byte[4096];
        byte[] expected = new byte[4096];
        int pos = 16;
        int limit = 3000;
        int p = DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, limit, s);
        assertTrue(p > 0, "must fit");
        int ep = pureEscaped(expected, pos, s);
        assertArrayEquals(slice(expected, pos, ep), slice(buf, pos, p));
        assertEquals(ep, p);
    }

    @Test
    void writeJsonStringNoGrow_overflow_returnsNegatedInputPos() {
        byte[] buf = new byte[64];
        int pos = 10;
        assertEquals(-pos, DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, pos, "x"));
        assertEquals(-pos, DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, pos + 2, "x"));
        // surrogate pair needs 4 UTF-8 bytes + 2 quotes
        assertEquals(-pos, DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, pos + 4, "🚀"));
    }

    // ---- DirectJsonStringWriter.writeEscapedLatin1NoGrow ---------------------

    @Test
    void writeEscapedLatin1NoGrow_allLengthBands_matches() {
        // lengths 0..50 cover the <8 per-byte band, 8..15 one-word, 16..24
        // three-word, 25..31 four-word and the >=32 block loop. Big buffer:
        // the block loop's LIMIT_MARGIN guard needs >= 1024 free bytes.
        // writeEscapedLatin1NoGrow writes the BODY only (no surrounding quotes),
        // so the oracle's first and last bytes are stripped.
        for (int len = 0; len <= 50; len++) {
            byte[] latin1 = new byte[len];
            for (int i = 0; i < len; i++) {
                latin1[i] = (byte) (0x21 + (i * 7) % 0x5E);
            }
            if (len > 0) {
                latin1[len / 2] = (byte) (len % 3 == 0 ? '"' : len % 3 == 1 ? '\\' : 0xE9);
            }
            byte[] buf = new byte[4096];
            byte[] expected = new byte[4096];
            int pos = 8;
            int limit = 3000;
            int p = DirectJsonStringWriter.writeEscapedLatin1NoGrow(buf, pos, limit, latin1);
            assertTrue(p > 0, "must fit len=" + len);
            int ep = pureEscaped(expected, pos, new String(latin1, StandardCharsets.ISO_8859_1));
            assertArrayEquals(slice(expected, pos + 1, ep - 1), slice(buf, pos, p), "len=" + len);
            assertEquals(ep - 2, p, "pos len=" + len);
        }
    }

    @Test
    void writeEscapedLatin1NoGrow_overflow_returnsNegatedPosition() {
        byte[] buf = new byte[64];
        byte[] latin1 = "héllo wörld".getBytes(StandardCharsets.ISO_8859_1);
        int pos = 10;
        // dirty word falls back to per-byte writes: the negated position is the
        // internal cursor at the failing write (the caller — writeJsonStringNoGrow
        // — restores from its own start, so the magnitude is not part of the
        // public contract here)
        assertTrue(DirectJsonStringWriter.writeEscapedLatin1NoGrow(buf, pos, pos + 2, latin1) < 0);
        // a long string into a nearly-full buffer: the margin guard fires
        byte[] big = new byte[64];
        Arrays.fill(big, (byte) 'x');
        big[50] = (byte) 0xE9;
        assertTrue(DirectJsonStringWriter.writeEscapedLatin1NoGrow(buf, pos, pos + 40, big) < 0);
    }

    // ---- WriteOps.writeRawNoGrow --------------------------------------------

    @Test
    void writeRawNoGrow_fits_copiesBytes() {
        byte[] buf = new byte[64];
        byte[] src = "hello world".getBytes(StandardCharsets.US_ASCII);
        int pos = 8;
        int limit = 40;
        int p = WriteOps.writeRawNoGrow(buf, pos, limit, src, 0, src.length);
        assertEquals(pos + src.length, p);
        assertArrayEquals(src, slice(buf, pos, p));
    }

    @Test
    void writeRawNoGrow_exactFitAtLimit_succeeds() {
        byte[] buf = new byte[64];
        byte[] src = new byte[16];
        Arrays.fill(src, (byte) 0x5A);
        int pos = 8;
        int p = WriteOps.writeRawNoGrow(buf, pos, pos + 16, src, 0, src.length);
        assertEquals(pos + 16, p);
    }

    @Test
    void writeRawNoGrow_overflow_returnsNegatedPos_allOrNothing() {
        byte[] buf = new byte[64];
        Arrays.fill(buf, (byte) 0x11);
        byte[] src = new byte[20];
        Arrays.fill(src, (byte) 0x22);
        int pos = 8;
        int limit = pos + 10;
        assertEquals(-pos, WriteOps.writeRawNoGrow(buf, pos, limit, src, 0, src.length));
        // all-or-nothing: the check fires before any copy — nothing written
        for (int i = pos; i < buf.length; i++) {
            assertEquals((byte) 0x11, buf[i], "byte " + i);
        }
    }

    // ---- StringByteExtractor.writeLatin1NoGrow ------------------------------

    @Test
    void writeLatin1NoGrow_fits_encodesUtf8() {
        byte[][] samples = {
            "abc".getBytes(StandardCharsets.ISO_8859_1),
            "héllo wörld".getBytes(StandardCharsets.ISO_8859_1),
            {(byte) 0xFF, (byte) 0x80, (byte) 0xE9, 'a', 'b'},
            new byte[40], // zeros: Latin-1 -> UTF-8 keeps them single-byte
        };
        for (byte[] latin1 : samples) {
            byte[] buf = new byte[4096];
            int pos = 8;
            int limit = 3000;   // word-loop margin needs >= 1024 free bytes
            int p = StringByteExtractor.writeLatin1NoGrow(buf, pos, limit, latin1);
            assertTrue(p > 0, "must fit len=" + latin1.length);
            byte[] expected = new String(latin1, StandardCharsets.ISO_8859_1)
                    .getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, slice(buf, pos, p), "len=" + latin1.length);
        }
    }

    @Test
    void writeLatin1NoGrow_longString_wordLoop_matches() {
        // > 32 bytes with high bytes sprinkled: 8-byte word loop with dirty
        // words, the between-word margin guard and the per-byte tail.
        byte[] latin1 = new byte[300];
        for (int i = 0; i < latin1.length; i++) {
            latin1[i] = (byte) (0x20 + (i * 7) % 0x5E);
            if (i % 37 == 0) latin1[i] = (byte) (0x80 + (i % 0x40));
        }
        byte[] buf = new byte[4096];
        int pos = 4;
        int limit = 3000;
        int p = StringByteExtractor.writeLatin1NoGrow(buf, pos, limit, latin1);
        assertTrue(p > 0);
        byte[] expected = new String(latin1, StandardCharsets.ISO_8859_1)
                .getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, slice(buf, pos, p));
    }

    @Test
    void writeLatin1NoGrow_overflow_returnsNegatedInputPos() {
        byte[] buf = new byte[64];
        byte[] latin1 = "héllo wörld".getBytes(StandardCharsets.ISO_8859_1);
        int pos = 10;
        assertEquals(-pos, StringByteExtractor.writeLatin1NoGrow(buf, pos, pos, latin1));
        assertEquals(-pos, StringByteExtractor.writeLatin1NoGrow(buf, pos, pos + 2, latin1));
        // a clean 100-byte string into a nearly-full buffer: the word-loop
        // margin guard fires before any copy
        byte[] clean = new byte[100];
        Arrays.fill(clean, (byte) 'x');
        assertEquals(-pos, StringByteExtractor.writeLatin1NoGrow(buf, pos, pos + 50, clean));
    }

    @Test
    void writeLatin1NoGrow_overflow_pastMargin_stillRejectedByExactChecks() {
        // limit >= pos + LIMIT_MARGIN: the margin guard passes, the exact
        // per-write checks reject the oversized output — never an exception.
        // The negated value is the internal cursor at the failing write.
        byte[] buf = new byte[8192];
        byte[] high = new byte[3000];
        for (int i = 0; i < high.length; i++) {
            high[i] = (byte) (0x80 + (i % 0x40));
        }
        int pos = 10;
        int limit = pos + 2000;
        assertTrue(StringByteExtractor.writeLatin1NoGrow(buf, pos, limit, high) < 0);
    }

    @Test
    void writeLatin1_2argEntry_rboTarget_noGrowContract() throws Exception {
        ReusableByteArrayOutputStream fits = new ReusableByteArrayOutputStream(64);
        byte[] latin1 = "héllo".getBytes(StandardCharsets.ISO_8859_1);
        StringByteExtractor.writeLatin1(fits, latin1);
        assertEquals("héllo", new String(fits.buffer(), 0, fits.size(), StandardCharsets.UTF_8));

        // Overflow is only detectable as a negative position once the cursor is
        // past 0 (-0 == 0); production call sites always write after a key
        // prefix, so pre-fill the buffer like they do.
        ReusableByteArrayOutputStream over = new ReusableByteArrayOutputStream(32);
        over.write('k');
        over.write(':');
        byte[] big = "this message is way too long for the remaining thirty bytes"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThrows(BufferFullException.class, () -> StringByteExtractor.writeLatin1(over, big));
        assertEquals(32, over.buffer().length, "buffer must not grow");
        assertEquals(2, over.size(), "all-or-nothing: no partial write");
    }
}
