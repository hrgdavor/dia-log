package hr.hrg.dialog.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code @CB.StrPacker} instruction processor: marker parsing,
 * gen-time constant computation (against {@link StrPacker#packWord}) and the
 * committed {@code JsonLogWriter} generated blocks.
 */
class StrPackerTest {

    // ------------------------------------------------------------------
    // Marker parsing
    // ------------------------------------------------------------------

    @Test
    void parseMarker_parsesModifiersNameAndLiteral() {
        StrPacker.Spec spec = StrPacker.parseMarker(
                "    // @CB.StrPacker private static final KEY_TS = `{\"ts\":`");
        assertNotNull(spec);
        assertEquals("private static final", spec.modifiers());
        assertEquals("KEY_TS", spec.name());
        assertEquals("{\"ts\":", spec.literal());
    }

    @Test
    void parseMarker_returnsNullForNonMarkerLines() {
        assertNull(StrPacker.parseMarker("    // @CB.StrPackerX private static final A = `x`"));
        assertNull(StrPacker.parseMarker("    // regular comment"));
        assertNull(StrPacker.parseMarker("    private static final byte[] KEY = new byte[0];"));
        assertNull(StrPacker.parseMarker(""));
    }

    @Test
    void parseMarker_rejectsMalformedTemplates() {
        // missing declaration
        assertThrows(IllegalArgumentException.class, () -> StrPacker.parseMarker("// @CB.StrPacker"));
        // missing '='
        assertThrows(IllegalArgumentException.class, () -> StrPacker.parseMarker("// @CB.StrPacker private static final KEY_TS"));
        // literal not backtick-delimited
        assertThrows(IllegalArgumentException.class, () -> StrPacker.parseMarker("// @CB.StrPacker private static final KEY_TS = \"ts\""));
        // backtick inside literal
        assertThrows(IllegalArgumentException.class, () -> StrPacker.parseMarker("// @CB.StrPacker private static final KEY_TS = `a`b`"));
    }

    // ------------------------------------------------------------------
    // packWord / longLiteral
    // ------------------------------------------------------------------

    @Test
    void packWord_packsLittleEndian() {
        // "ts" -> bytes 0x74 0x73 -> 0x74 | 0x73<<8
        assertEquals(0x7374L, StrPacker.packWord("ts".getBytes(StandardCharsets.UTF_8), 0));
        // empty tail: off beyond end packs 0
        byte[] bytes = "ab".getBytes(StandardCharsets.UTF_8);
        assertEquals(0L, StrPacker.packWord(bytes, 8));
        // partial window at offset
        assertEquals(0x63L, StrPacker.packWord("abc".getBytes(StandardCharsets.UTF_8), 2));
    }

    @Test
    void packWord_matchesJsonLogWriterForKnownKeys() {
        // Concrete regression anchor: {"ts": packs to the literal CodeBuddy emitted.
        byte[] ts = "{\"ts\":".getBytes(StandardCharsets.UTF_8);
        assertEquals(0x00003a227374227bL, StrPacker.packWord(ts, 0));
        assertEquals(0x22726567676f6c22L, StrPacker.packWord("\"logger\":".getBytes(StandardCharsets.UTF_8), 0));
        assertEquals(0x3aL, StrPacker.packWord("\"logger\":".getBytes(StandardCharsets.UTF_8), 8));
    }

    @Test
    void longLiteral_isSixteenDigitLowercaseHex() {
        assertEquals("0x0000000000000000L", StrPacker.longLiteral(0L));
        assertEquals("0x00003a227374227bL", StrPacker.longLiteral(0x3a227374227bL));
        assertEquals("0xffffffffffffffffL", StrPacker.longLiteral(-1L));
    }

    // ------------------------------------------------------------------
    // Block generation
    // ------------------------------------------------------------------

    @Test
    void generateBlock_escapesLiteralAndComputesConstants() {
        StrPacker.Spec spec = new StrPacker.Spec("private static final", "KEY_TS", "{\"ts\":");
        assertEquals(List.of(
                "    private static final String KEY_TS = \"{\\\"ts\\\":\";",
                "    private static final long KEY_TS_W0 = 0x00003a227374227bL;",
                "    private static final int KEY_TS_LEN = 6;",
                "    private static final int KEY_TS_LEN_BUF = 8;"
        ), StrPacker.generateBlock(spec, "    "));
    }

    @Test
    void generateBlock_emitsWordsPerEightByteWindow() {
        String indent = "    ";

        // 6 bytes -> 1 word (W0 only)
        StrPacker.Spec oneWord = new StrPacker.Spec("private static final", "KEY_MSG", "\"msg\":");
        List<String> block1 = StrPacker.generateBlock(oneWord, indent);
        assertEquals(4, block1.size());
        assertEquals("    private static final int KEY_MSG_LEN = 6;", block1.get(2));
        assertEquals("    private static final int KEY_MSG_LEN_BUF = 8;", block1.get(3));

        // 9 bytes -> 2 words (W0, W1)
        StrPacker.Spec twoWords = new StrPacker.Spec("private static final", "KEY_LOGGER", "\"logger\":");
        List<String> block2 = StrPacker.generateBlock(twoWords, indent);
        assertEquals(5, block2.size());
        byte[] logger = "\"logger\":".getBytes(StandardCharsets.UTF_8);
        assertEquals("    private static final long KEY_LOGGER_W0 = " + StrPacker.longLiteral(StrPacker.packWord(logger, 0)) + ";", block2.get(1));
        assertEquals("    private static final long KEY_LOGGER_W1 = " + StrPacker.longLiteral(StrPacker.packWord(logger, 8)) + ";", block2.get(2));
        assertEquals("    private static final int KEY_LOGGER_LEN = 9;", block2.get(3));
        assertEquals("    private static final int KEY_LOGGER_LEN_BUF = 16;", block2.get(4));

        // 17 bytes -> 3 words (W0, W1, W2)
        StrPacker.Spec threeWords = new StrPacker.Spec("private static final", "KEY_17", "\"123456789012345\"");
        List<String> block3 = StrPacker.generateBlock(threeWords, indent);
        assertEquals(6, block3.size());
        byte[] lit17 = "\"123456789012345\"".getBytes(StandardCharsets.UTF_8);
        assertEquals(17, lit17.length);
        for (int i = 0; i < 3; i++) {
            assertEquals("    private static final long KEY_17_W" + i + " = "
                    + StrPacker.longLiteral(StrPacker.packWord(lit17, i * 8)) + ";", block3.get(1 + i));
        }
        assertEquals("    private static final int KEY_17_LEN = 17;", block3.get(4));
        assertEquals("    private static final int KEY_17_LEN_BUF = 24;", block3.get(5));

        // 25 bytes -> 4 words, concrete values (anchor for doc/codebuddy-strpacker.md)
        StrPacker.Spec fourWords = new StrPacker.Spec("private static final", "KEY_LONG", "\"someLongKeyName\":\"value\"");
        assertEquals(List.of(
                "    private static final String KEY_LONG = \"\\\"someLongKeyName\\\":\\\"value\\\"\";",
                "    private static final long KEY_LONG_W0 = 0x6e6f4c656d6f7322L;",
                "    private static final long KEY_LONG_W1 = 0x656d614e79654b67L;",
                "    private static final long KEY_LONG_W2 = 0x65756c6176223a22L;",
                "    private static final long KEY_LONG_W3 = 0x0000000000000022L;",
                "    private static final int KEY_LONG_LEN = 25;",
                "    private static final int KEY_LONG_LEN_BUF = 32;"
        ), StrPacker.generateBlock(fourWords, indent));
    }

    @Test
    void generateBlock_fallsBackToBytesOnlyWhenLiteralExceedsMaxWords() {
        // 33 bytes would need a 5th word -> String + LEN only, no packed words.
        String literal33 = "\"" + "x".repeat(31) + "\"";
        StrPacker.Spec spec33 = new StrPacker.Spec("private static final", "KEY_33", literal33);
        List<String> block = StrPacker.generateBlock(spec33, "    ");
        assertEquals(2, block.size(), "fallback block is String + LEN only");
        assertEquals("    private static final int KEY_33_LEN = 33;", block.get(1));
        assertTrue(block.get(0).startsWith("    private static final String KEY_33 = \"\\\""),
                "String line carries the escaped literal: " + block.get(0));
        assertTrue(block.get(0).endsWith("\";"), block.get(0));
        assertFalse(String.join("\n", block).contains("_W"), "no packed words in fallback block");
        assertFalse(String.join("\n", block).contains("_LEN_BUF"), "no buffer reserve in fallback block");

        // Exactly 32 bytes is the maximum packed form: String + W0..W3 + LEN + LEN_BUF.
        StrPacker.Spec spec32 = new StrPacker.Spec("private static final", "KEY_32", "\"" + "x".repeat(30) + "\"");
        List<String> packed = StrPacker.generateBlock(spec32, "");
        assertEquals(7, packed.size());
        for (int i = 0; i < 4; i++) {
            assertTrue(packed.get(1 + i).startsWith("private static final long KEY_32_W" + i + " = "), packed.get(1 + i));
        }
        assertEquals("private static final int KEY_32_LEN = 32;", packed.get(5));
        assertEquals("private static final int KEY_32_LEN_BUF = 32;", packed.get(6));
    }

    @Test
    void processFileText_fallsBackToBytesOnlyAndIsIdempotent() {
        // A 42-byte literal (40 y's + quotes) exceeds 4 words: the placeholder
        // words must be dropped in favor of a String + LEN fallback block.
        String literal = "\"" + "y".repeat(40) + "\"";
        String source = "    // @CB.StrPacker private static final KEY_LONG = `" + literal + "`\n"
                + "    private static final String KEY_LONG = \"\";\n"
                + "    private static final long KEY_LONG_W0 = 0L;\n"
                + "    private static final int KEY_LONG_LEN = 0;\n";
        String processed = StrPacker.processFileText(source);
        assertTrue(processed.contains(
                "private static final String KEY_LONG = \"\\\"" + "y".repeat(40) + "\\\"\";"),
                processed);
        assertFalse(processed.contains("KEY_LONG_W"), "fallback block must not keep packed words: " + processed);
        assertFalse(processed.contains("KEY_LONG_LEN_BUF"), "fallback block must not keep a buffer reserve: " + processed);
        assertTrue(processed.contains("private static final int KEY_LONG_LEN = 42;"), processed);
        assertEquals(processed, StrPacker.processFileText(processed), "fallback block must be idempotent");
    }

    @Test
    void generateBlock_emitsAllConstantsViaPackWord() {
        String[] literals = {"{\"ts\":", "\"level\":", "\"logger\":", "\"thread\":", "\"msg\":",
                "\"errClass\":", "\"errMessage\":", "\"errHash\":", "\"stack\":", "a\\\"b\\c\n",
                "\"someLongKeyName\":\"value\"" };
        for (String literal : literals) {
            StrPacker.Spec spec = new StrPacker.Spec("private static final", "K_" + literal.length(), literal);
            List<String> block = StrPacker.generateBlock(spec, "");
            byte[] bytes = literal.getBytes(StandardCharsets.UTF_8);
            int words = Math.max(1, (bytes.length + 7) / 8);
            for (int i = 0; i < words; i++) {
                assertEquals("private static final long K_" + literal.length() + "_W" + i + " = "
                        + StrPacker.longLiteral(StrPacker.packWord(bytes, i * 8)) + ";", block.get(1 + i));
            }
            assertEquals("private static final int K_" + literal.length() + "_LEN = " + bytes.length + ";", block.get(1 + words));
            assertEquals("private static final int K_" + literal.length() + "_LEN_BUF = "
                    + StrPacker.packedBufferBytes(bytes.length) + ";", block.get(2 + words));
            assertEquals(block.size(), 3 + words);
        }
    }

    // ------------------------------------------------------------------
    // Source rewriting
    // ------------------------------------------------------------------

    @Test
    void processFileText_replacesRuntimeBlockWithLiterals() {
        String source = ""
                + "    // explanatory comment\n"
                + "    // @CB.StrPacker private static final KEY_MSG = `\"msg\":`\n"
                + "    private static final String KEY_MSG = \"\\\"msg\\\":\";\n"
                + "    private static final long KEY_MSG_W0 = packWord(KEY_MSG, 0);\n"
                + "    private static final int KEY_MSG_LEN = KEY_MSG.length;\n"
                + "    private static final byte[] JSON_NULL = \"null\".getBytes(StandardCharsets.UTF_8);\n";
        String expected = ""
                + "    // explanatory comment\n"
                + "    // @CB.StrPacker private static final KEY_MSG = `\"msg\":`\n"
                + "    private static final String KEY_MSG = \"\\\"msg\\\":\";\n"
                + "    private static final long KEY_MSG_W0 = 0x00003a2267736d22L;\n"
                + "    private static final int KEY_MSG_LEN = 6;\n"
                + "    private static final int KEY_MSG_LEN_BUF = 8;\n"
                + "    private static final byte[] JSON_NULL = \"null\".getBytes(StandardCharsets.UTF_8);\n";
        assertEquals(expected, StrPacker.processFileText(source));
    }

    @Test
    void processFileText_isIdempotent() {
        String source = ""
                + "    // @CB.StrPacker private static final KEY_LOGGER = `\"logger\":`\n"
                + "    private static final String KEY_LOGGER = \"\\\"logger\\\":\";\n"
                + "    private static final long KEY_LOGGER_W0 = packWord(KEY_LOGGER, 0);\n"
                + "    private static final long KEY_LOGGER_W1 = packWord(KEY_LOGGER, 8);\n"
                + "    private static final int KEY_LOGGER_LEN = KEY_LOGGER.length;\n";
        String once = StrPacker.processFileText(source);
        assertEquals(once, StrPacker.processFileText(once));
    }

    @Test
    void processFileText_generatesBlockFromMarkerAlone() {
        // Marker with no block below: the block is generated from scratch.
        String source = "    // @CB.StrPacker private static final KEY_TS = `{\"ts\":`\n"
                + "    private static final int OTHER = 1;\n";
        String processed = StrPacker.processFileText(source);
        assertTrue(processed.contains("private static final String KEY_TS = \"{\\\"ts\\\":\";"));
        assertTrue(processed.contains("private static final long KEY_TS_W0 = 0x00003a227374227bL;"));
        assertTrue(processed.contains("private static final int KEY_TS_LEN = 6;"));
        assertTrue(processed.contains("private static final int KEY_TS_LEN_BUF = 8;"));
        assertTrue(processed.contains("private static final int OTHER = 1;"));
    }

    @Test
    void processFileText_keepsCrlfLineEndings() {
        String source = "    // @CB.StrPacker private static final KEY_MSG = `\"msg\":`\r\n"
                + "    private static final String KEY_MSG = \"\";\r\n"
                + "    private static final long KEY_MSG_W0 = 0L;\r\n"
                + "    private static final int KEY_MSG_LEN = 0;\r\n";
        String processed = StrPacker.processFileText(source);
        assertTrue(processed.contains("\r\n"), "output must keep CRLF");
        assertFalse(processed.replace("\r\n", "\n").contains("\r"), "no stray lone CR");
    }

    @Test
    void processFileText_migratesOldByteArrayBlocksToString() {
        // The previous generated shape was byte[]; CodeBuddy must fully replace
        // it with the String shape in one pass — no leftover byte[] lines or
        // runtime initializers, and the result is idempotent.
        String oldSource = ""
                + "    // @CB.StrPacker private static final KEY_LOGGER = `\"logger\":`\n"
                + "    private static final byte[] KEY_LOGGER = \"\\\"logger\\\":\".getBytes(StandardCharsets.UTF_8);\n"
                + "    private static final long KEY_LOGGER_W0 = packWord(KEY_LOGGER, 0);\n"
                + "    private static final long KEY_LOGGER_W1 = packWord(KEY_LOGGER, 8);\n"
                + "    private static final int KEY_LOGGER_LEN = KEY_LOGGER.length;\n"
                + "    private static final int KEY_LOGGER_LEN_BUF = 16;\n";
        String once = StrPacker.processFileText(oldSource);
        assertTrue(once.contains("private static final String KEY_LOGGER = \"\\\"logger\\\":\";"), once);
        assertFalse(once.contains("byte[] KEY_LOGGER"), "old byte[] declaration must be replaced: " + once);
        assertFalse(once.contains("packWord("), "runtime initializers must be replaced: " + once);
        assertEquals(once, StrPacker.processFileText(once), "migrated block must be idempotent");
    }

    @Test
    void processFileText_collapsesDuplicateBlocksBelowMarker() {
        // A stale duplicate block below the marker (the transient state after a
        // generator shape change) must be fully overwritten, not left behind.
        String dupSource = ""
                + "    // @CB.StrPacker private static final KEY_LOGGER = `\"logger\":`\n"
                + "    private static final String KEY_LOGGER = \"\\\"logger\\\":\";\n"
                + "    private static final long KEY_LOGGER_W0 = 0x22726567676f6c22L;\n"
                + "    private static final long KEY_LOGGER_W1 = 0x000000000000003aL;\n"
                + "    private static final int KEY_LOGGER_LEN = 9;\n"
                + "    private static final int KEY_LOGGER_LEN_BUF = 16;\n"
                + "    private static final byte[] KEY_LOGGER = \"\\\"logger\\\":\".getBytes(StandardCharsets.UTF_8);\n"
                + "    private static final long KEY_LOGGER_W0 = 0x22726567676f6c22L;\n"
                + "    private static final long KEY_LOGGER_W1 = 0x000000000000003aL;\n"
                + "    private static final int KEY_LOGGER_LEN = 9;\n"
                + "    private static final int KEY_LOGGER_LEN_BUF = 16;\n"
                + "    private static final int OTHER = 1;\n";
        String processed = StrPacker.processFileText(dupSource);
        assertEquals(1, countOccurrences(processed, "private static final String KEY_LOGGER ="), processed);
        assertFalse(processed.contains("byte[] KEY_LOGGER"), "duplicate byte[] block must be removed: " + processed);
        assertTrue(processed.contains("private static final int OTHER = 1;"), processed);
        assertEquals(processed, StrPacker.processFileText(processed), "collapsed block must be idempotent");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Committed JsonLogWriter generated code
    // ------------------------------------------------------------------

    @Test
    void jsonLogWriterSource_isUpToDate() throws IOException {
        Path source = findJsonLogWriterSource();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertEquals(text, StrPacker.processFileText(text),
                "JsonLogWriter generated blocks are stale - run CodeBuddy");
    }

    @Test
    void jsonLogWriterGeneratedBlocks_matchPackWord() throws IOException {
        Path source = findJsonLogWriterSource();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        String[] lines = text.split("\r\n|\r|\n", -1);

        int markers = 0;
        for (int i = 0; i < lines.length; i++) {
            StrPacker.Spec spec = StrPacker.parseMarker(lines[i]);
            if (spec == null) {
                continue;
            }
            markers++;
            byte[] bytes = spec.literal().getBytes(StandardCharsets.UTF_8);
            List<String> block = StrPacker.consumeBlock(lines, i, spec.name());
            assertFalse(block.isEmpty(), "no block below marker: " + lines[i].trim());
            String indent = StrPacker.leadingWhitespace(block.get(0));

            // Structural: the committed block is exactly what the generator emits.
            assertEquals(StrPacker.generateBlock(spec, indent), block, "block below: " + lines[i].trim());

            // Value-level, explicitly via packWord: the W0..W3 literals and LEN.
            int words = Math.max(1, (bytes.length + 7) / 8);
            for (int w = 0; w < words; w++) {
                assertEquals(StrPacker.longLiteral(StrPacker.packWord(bytes, w * 8)),
                        extractLiteral(block.get(1 + w), "0x[0-9a-fA-F]+L"),
                        spec.name() + "_W" + w + " for marker: " + lines[i].trim());
            }
            assertEquals(String.valueOf(bytes.length), extractLiteral(block.get(1 + words), "\\d+"),
                    spec.name() + "_LEN for marker: " + lines[i].trim());
            assertEquals(String.valueOf(StrPacker.packedBufferBytes(bytes.length)),
                    extractLiteral(block.get(2 + words), "\\d+"),
                    spec.name() + "_LEN_BUF for marker: " + lines[i].trim());
            assertEquals(3 + words, block.size(), "block line count for marker: " + lines[i].trim());
        }
        assertEquals(13, markers, "expected the 13 markers (TS, LEVEL, LOGGER, THREAD, MSG, ERR_CLASS, ERR_MESSAGE, ERR_HASH, STACK, JSON_NULL, JSON_TRUE, JSON_FALSE, PLACEHOLDER)");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String extractLiteral(String line, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(line);
        assertTrue(m.find(), "no literal in: " + line);
        return m.group();
    }

    /** Locates JsonLogWriter.java by walking up from the working directory to the repo root. */
    private static Path findJsonLogWriterSource() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Cannot locate JsonLogWriter.java from "
                + System.getProperty("user.dir"));
    }
}
