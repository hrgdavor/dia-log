package hr.hrg.dialog.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CodeBuddy instruction {@code @CB.StrPacker}: replaces a runtime-initialized
 * static field block with gen-time computed compile-time constants.
 * <p>
 * Marker convention: a line comment placed directly above a field block. The
 * comment carries the declaration template for the base field; the UTF-8 string
 * literal is backtick-delimited so JSON text with quotes can be written without
 * escaping:
 * <pre>{@code
 *   // @CB.StrPacker private static final KEY_TS = `{"ts":`
 *   private static final String KEY_TS = "{\"ts\":";
 *   private static final long KEY_TS_W0 = 0x00003a227374227bL;
 *   private static final int KEY_TS_LEN = 6;
 *   private static final int KEY_TS_LEN_BUF = 8;
 * }</pre>
 * <p>
 * Running the tool rewrites the lines below every marker (the marker line itself
 * is the source of truth and is left untouched):
 * <ul>
 *   <li>{@code String NAME} — the literal itself as a {@code String} reference,
 *       the naive form used by tests and comparison code that writes strings;
 *       the optimized path uses the packed words below</li>
 *   <li>{@code long NAME_W0..NAME_W3} — {@link #packWord} of each 8-byte window
 *       (offsets 0, 8, 16, 24), as 16-digit hex literals computed at generation
 *       time instead of at class init. Word {@code i} is emitted when the UTF-8
 *       length exceeds {@code i*8}; a literal longer than
 *       {@value #MAX_LITERAL_BYTES} bytes cannot be fully represented by
 *       {@value #MAX_WORDS} words, so it falls back to a String-only block (no
 *       packed words) and takes the stream path</li>
 *   <li>{@code int NAME_LEN} — the UTF-8 byte length, as a literal</li>
 *   <li>{@code int NAME_LEN_BUF} — the buffer reserve the packed store occupies
 *       (whole 8-byte word slots, {@link #packedBufferBytes}); emitted only on
 *       the packed path so capacity checks use the constant instead of
 *       recomputing the rounded length</li>
 * </ul>
 * <p>
 * The transformation is idempotent: the generated block matches its own
 * consumption pattern, so running the tool twice yields the same source.
 * <p>
 * See {@code doc/codebuddy-strpacker.md} for the full reference and examples.
 */
public final class StrPacker {

    /** The marker token that selects this instruction, without the leading {@code @CB.} prefix. */
    public static final String MARKER = "@CB.StrPacker";

    /** Maximum number of packed {@code long} words the generator emits ({@code NAME_W0..NAME_W3}). */
    public static final int MAX_WORDS = 4;

    /** Maximum UTF-8 literal length packed into {@link #MAX_WORDS} words ({@code 4 × 8} bytes); longer literals fall back to a byte[]-only block. */
    public static final int MAX_LITERAL_BYTES = MAX_WORDS * 8;

    /** Parsed form of a {@code // @CB.StrPacker ...} marker comment line. */
    public record Spec(String modifiers, String name, String literal) {}

    private StrPacker() {}

    /**
     * Packs up to 8 bytes starting at {@code off} little-endian into one long.
     * Must stay byte-for-byte equivalent to {@code JsonLogWriter.packWord}: the
     * gen-time constants are only correct if both implementations agree.
     */
    public static long packWord(byte[] bytes, int off) {
        long v = 0;
        int end = Math.min(off + 8, bytes.length);
        for (int i = off; i < end; i++) {
            v |= (bytes[i] & 0xFFL) << ((i - off) << 3);
        }
        return v;
    }

    /** Formats a long as a 16-digit lowercase hex Java literal, e.g. {@code 0x00003a227374227bL}. */
    public static String longLiteral(long v) {
        return String.format("0x%016xL", v);
    }

    /**
     * Bytes a packed key occupies in the buffer: its length rounded up to whole
     * 8-byte word slots (one VarHandle store per window, the last window may be
     * partial). E.g. 9 → 16, 6 → 8. This is the value emitted as
     * {@code NAME_LEN_BUF}.
     */
    public static int packedBufferBytes(int len) {
        return (len + 7) & ~7;
    }

    /**
     * Parses a marker comment line, or returns {@code null} if the line is not a
     * {@code @CB.StrPacker} marker.
     *
     * @throws IllegalArgumentException if the line starts with the marker token
     *         but its declaration template is malformed
     */
    public static Spec parseMarker(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("// ")) {
            return null;
        }
        String rest = trimmed.substring(3).trim();
        if (!rest.startsWith(MARKER)) {
            return null;
        }
        if (rest.length() > MARKER.length() && !Character.isWhitespace(rest.charAt(MARKER.length()))) {
            return null; // different @CB.* instruction (e.g. @CB.StrPackerX)
        }
        String decl = rest.substring(MARKER.length()).trim();
        if (decl.isEmpty()) {
            throw new IllegalArgumentException("Malformed @CB.StrPacker marker, missing declaration: " + line);
        }
        int eq = decl.indexOf('=');
        if (eq < 0) {
            throw new IllegalArgumentException("Malformed @CB.StrPacker marker, missing '=': " + line);
        }
        String left = decl.substring(0, eq).trim();
        String right = decl.substring(eq + 1).trim();
        String[] leftParts = left.split("\\s+");
        if (leftParts.length < 2) {
            throw new IllegalArgumentException("Malformed @CB.StrPacker marker, expected '<modifiers> <NAME> = `<literal>`': " + line);
        }
        String name = leftParts[leftParts.length - 1];
        String modifiers = String.join(" ", List.of(leftParts).subList(0, leftParts.length - 1));
        if (right.length() < 2 || right.charAt(0) != '`' || right.charAt(right.length() - 1) != '`') {
            throw new IllegalArgumentException("Malformed @CB.StrPacker marker, literal must be backtick-delimited: " + line);
        }
        String literal = right.substring(1, right.length() - 1);
        if (literal.indexOf('`') >= 0) {
            throw new IllegalArgumentException("Malformed @CB.StrPacker marker, backtick inside literal: " + line);
        }
        return new Spec(modifiers, name, literal);
    }

    /**
     * Generates the field declaration block for a marker spec: the String field,
     * the packed word(s) as compile-time literals, the byte length, and — for the
     * packed path — the buffer reserve ({@code NAME_LEN_BUF}).
     * <p>
     * One {@code NAME_Wi} literal is emitted per 8-byte window of the UTF-8
     * bytes (word {@code i} covers bytes {@code i*8..i*8+7}), so a literal of up
     * to {@value #MAX_LITERAL_BYTES} bytes produces at most
     * {@value #MAX_WORDS} words plus a {@code NAME_LEN_BUF} equal to
     * {@link #packedBufferBytes}. A literal longer than that cannot be fully
     * packed, so the block falls back to the String field and {@code NAME_LEN}
     * only — no {@code NAME_W*} or {@code NAME_LEN_BUF} constants are emitted.
     */
    public static List<String> generateBlock(Spec spec, String indent) {
        byte[] bytes = spec.literal().getBytes(StandardCharsets.UTF_8);
        boolean packed = bytes.length <= MAX_LITERAL_BYTES;
        List<String> out = new ArrayList<>(packed ? 3 + MAX_WORDS : 2);
        out.add(indent + spec.modifiers() + " String " + spec.name() + " = "
                + javaStringLiteral(spec.literal()) + ";");
        if (packed) {
            int words = Math.max(1, (bytes.length + 7) / 8);
            for (int i = 0; i < words; i++) {
                out.add(indent + spec.modifiers() + " long " + spec.name() + "_W" + i + " = "
                        + longLiteral(packWord(bytes, i * 8)) + ";");
            }
        }
        out.add(indent + spec.modifiers() + " int " + spec.name() + "_LEN = " + bytes.length + ";");
        if (packed) {
            out.add(indent + spec.modifiers() + " int " + spec.name() + "_LEN_BUF = "
                    + packedBufferBytes(bytes.length) + ";");
        }
        return out;
    }

    /**
     * Rewrites the source text: every {@code @CB.StrPacker} marker block is
     * regenerated from its marker. Non-marker lines (including explanatory
     * comments and blank separators) are preserved verbatim, and the dominant
     * line ending (LF or CRLF) of the input is kept.
     */
    public static String processFileText(String source) {
        String newline = source.indexOf("\r\n") >= 0 ? "\r\n" : "\n";
        String[] lines = source.split("\r\n|\r|\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            Spec spec = parseMarker(line);
            if (spec == null) {
                out.add(line);
                i++;
                continue;
            }
            // Collect blank lines between the marker and its block (preserved).
            int j = i + 1;
            List<String> blanks = new ArrayList<>();
            while (j < lines.length && lines[j].isBlank()) {
                blanks.add(lines[j]);
                j++;
            }
            List<String> consumed = consumeBlock(lines, i, spec.name());
            int k = j + consumed.size();
            String indent = consumed.isEmpty() ? leadingWhitespace(line) : leadingWhitespace(consumed.get(0));
            out.add(line);
            out.addAll(blanks);
            out.addAll(generateBlock(spec, indent));
            i = k;
        }
        return String.join(newline, out);
    }

    /**
     * Returns the existing declaration lines below the marker at
     * {@code markerIdx} for {@code name}: every consecutive
     * {@code NAME} (String or byte[]), {@code NAME_W*}, {@code NAME_LEN} and
     * {@code NAME_LEN_BUF} line, skipping blank lines. All of them are replaced
     * on regeneration, so a stale or duplicated block (e.g. a leftover
     * byte[]-form block under a marker that now generates String) is fully
     * overwritten in one pass. Empty when there is no block to replace — the
     * marker then generates the block from scratch.
     */
    static List<String> consumeBlock(String[] lines, int markerIdx, String name) {
        int j = markerIdx + 1;
        while (j < lines.length && lines[j].isBlank()) {
            j++;
        }
        List<String> consumed = new ArrayList<>();
        int k = j;
        while (k < lines.length) {
            String line = lines[k];
            if (isStringDecl(line, name) || isWordDecl(line, name)
                    || isLenDecl(line, name) || isBufDecl(line, name)) {
                consumed.add(line);
                k++;
            } else {
                break;
            }
        }
        return consumed;
    }

    /** Escapes a raw string for use inside a Java {@code "..."} literal. */
    static String javaStringLiteral(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static boolean isStringDecl(String line, String name) {
        // Accept both the current String shape and the historical byte[] shape
        // so old generated blocks are consumed (and migrated) on re-run.
        // Modifiers (private/public/etc.) are not hardcoded so declarations with
        // non-private modifiers are consumed correctly.
        return line.trim().matches("\\w+ static final (String|byte\\[\\]) "
                + java.util.regex.Pattern.quote(name) + " = .*;");
    }

    private static boolean isWordDecl(String line, String name) {
        return line.trim().matches("\\w+ static final long " + java.util.regex.Pattern.quote(name) + "_W\\d+ = .*;");
    }

    private static boolean isLenDecl(String line, String name) {
        return line.trim().matches("\\w+ static final int " + java.util.regex.Pattern.quote(name) + "_LEN = .*;");
    }

    private static boolean isBufDecl(String line, String name) {
        return line.trim().matches("\\w+ static final int " + java.util.regex.Pattern.quote(name) + "_LEN_BUF = .*;");
    }

    static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }
}
