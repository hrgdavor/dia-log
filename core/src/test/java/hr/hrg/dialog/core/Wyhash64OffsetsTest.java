package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the offset/length and {@link CharSequence} entry points of
 * {@link Wyhash64} that the edge-case suite does not exercise directly.
 */
class Wyhash64OffsetsTest {

    private static final String TEXT = "The quick brown fox jumps over the lazy dog";

    @Test
    void hashStringSlice_equalsHashOfSubstring() {
        long slice = Wyhash64.hash(0, TEXT, 4, 9);           // "quick"
        long direct = Wyhash64.hash(0, TEXT.substring(4, 13));
        assertEquals(direct, slice);
    }

    @Test
    void hashByteSlice_equalsHashOfSliceArray() {
        byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
        long slice = Wyhash64.hash(0, bytes, 4, 9);
        long direct = Wyhash64.hash(0, new String(bytes, 4, 9, StandardCharsets.UTF_8));
        assertEquals(direct, slice);
    }

    @Test
    void hashCharSlice_equalsHashOfSliceArray() {
        char[] chars = TEXT.toCharArray();
        long slice = Wyhash64.hash(0, chars, 4, 9);
        long direct = Wyhash64.hash(0, String.valueOf(chars, 4, 9));
        assertEquals(direct, slice);
    }

    @Test
    void hashByteBufferSlice_usesAbsoluteOffsets() {
        byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
        // hash(ByteBuffer, off, len) reads absolute buffer indices (position-independent)
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long slice = Wyhash64.hash(0, buf, 4, 9);
        long direct = Wyhash64.hash(0, bytes, 4, 9); // same bytes, byte[] off/len
        assertEquals(direct, slice);
    }

    @Test
    void hashByteBuffer_full_equalsByteArray() {
        byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
        assertEquals(
                Wyhash64.hash(0, bytes),
                Wyhash64.hash(0, ByteBuffer.wrap(bytes)));
    }

    @Test
    void hashCharSequence_latin1_equalsHashOfString() {
        StringBuilder sb = new StringBuilder(TEXT);
        assertEquals(Wyhash64.hash(0, TEXT), Wyhash64.hash(0, (CharSequence) sb));
    }

    @Test
    void hashCharSequence_isLatin1Only_consistentWithTruncatedBytes() {
        // hash(CharSequence) packs chars as Latin-1 bytes (1 byte/char);
        // non-Latin-1 chars are truncated, so only Latin-1 input is meaningful.
        String latin1 = "caf\u00e9"; // 'é' fits in Latin-1
        assertEquals(
                Wyhash64.hash(0, latin1),
                Wyhash64.hash(0, (CharSequence) new StringBuilder(latin1)));
    }

    @Test
    void unicodeSlice_equalsHashOfSubstring() {
        String unicode = "héllo wörld 🚀 Ñoño";
        long slice = Wyhash64.hash(0, unicode, 6, 5);     // "wörld" (all Latin-1)
        long direct = Wyhash64.hash(0, unicode.substring(6, 11));
        assertEquals(direct, slice);
    }

    @Test
    void unicodeSlice_containingNonLatin1_equalsHashOfSubstring() {
        // slice keeps a non-Latin-1 char -> substring stays coder=1 (UTF-16)
        String unicode = "héllo wörld 🚀 Ñoño";
        long slice = Wyhash64.hash(0, unicode, 12, 3);    // "🚀 " (surrogate + space)
        long direct = Wyhash64.hash(0, unicode.substring(12, 15));
        assertEquals(direct, slice);
    }

    @Test
    void streamingUnicodeSlice_equalsHashOfSubstring() {
        String unicode = "héllo wörld 🚀 Ñoño";

        Wyhash64.Streaming utf16Slice = new Wyhash64.Streaming(0);
        utf16Slice.update(unicode, 12, 3);                 // "🚀 " — UTF-16 slice
        assertEquals(Wyhash64.hash(0, unicode.substring(12, 15)), utf16Slice.finalHash());

        Wyhash64.Streaming latin1Slice = new Wyhash64.Streaming(0);
        latin1Slice.update(unicode, 6, 5);                 // "wörld" — Latin-1 slice
        assertEquals(Wyhash64.hash(0, unicode.substring(6, 11)), latin1Slice.finalHash());
    }

    @Test
    void longLatin1SliceOfUtf16String_equalsHashOfSubstring() {
        // 🚀 (2 chars) + 70 Latin-1 chars + € -> the 70-char middle is an
        // all-Latin-1 slice of a UTF-16 string (hits the 48-byte round paths)
        String utf16 = "\uD83D\uDE80" + "a".repeat(70) + "\u20ac";

        long slice = Wyhash64.hash(0, utf16, 2, 70);
        long direct = Wyhash64.hash(0, utf16.substring(2, 72));
        assertEquals(direct, slice, "hash(String,off,len) of a long Latin-1 slice must match substring");

        Wyhash64.Streaming st = new Wyhash64.Streaming(0);
        st.update(utf16, 2, 70);
        assertEquals(direct, st.finalHash(), "Streaming.update(String,off,len) of a long Latin-1 slice must match substring");
    }
}
