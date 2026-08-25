# Step 3: Limit-aware writers — SWAR preserved, limit check between blocks

> Parent overview: [`../no-grow-jsonlogwriter-placeholder.md`](../no-grow-jsonlogwriter-placeholder.md)
> Prereqs: Steps 1–2. Adds limit-aware overloads to the string writers. The key
> design principle: **SWAR band boundaries are the optimal chunk boundaries. Do not
> subdivide them. The limit check goes *between* SWAR blocks, not inside them.***

## Rule

> **On hot-path writers for values without a known max size (strings, raw JSON, stack
> trace), the limit/buffer-capacity check happens once per SWAR block (16 bytes), never
> per byte.** SWAR band classification (`isJsonAsciiWords2/3/4`, `isJsonAsciiTail`)
> is preserved unchanged — clean 16-byte blocks are stored with two `LE_WORD` stores
> and no per-byte dispatch. The limit check goes **between** blocks. The per-byte
> fallback is called only for dirty blocks (rare for log content) and handles the
> 6 B/byte worst case within one block (96 B, well under `LIMIT_MARGIN`).

Applies **only** to variable-length writers targeting the no-grow buffer:
`StringByteExtractor.writeLatin1NoGrow`, `DirectJsonStringWriter`
(`writeEscapedLatin1NoGrow`, `writeEscapedLatin1PerByteNoGrow`, `writeEscapedCharsNoGrow`),
and `WriteOps.writeEscapedJsonStringNoGrow(byte[], …, limit, …)` (a pure delegate).
`writeEscapedCharsNoGrow` has no SWAR bands — its "block" is the per-char check
(3c).

**Excluded** (known max size, covered by `RESERVE`): `JsonNumberWriter` values
(`int`/`long`/`float`/`double` ≤ 25 B), the closing `}` (1 B), the `'\n'` (1 B),
and the `"V2BIG"` placeholder (7 B). These write directly with a single capacity
check; no chunking.

## Constant

```java
/// Headroom reserved at the tail of the buffer for one worst-case SWAR block.
/// A 16-byte SWAR block expands to at most 96 output bytes (all control chars
/// → \uXXXX each) for the JSON-escape path, or 32 bytes (2-byte UTF-8 each)
/// for the Latin-1→UTF-8 path. LIMIT_MARGIN = 1024 far exceeds both, so a
/// single `pos + LIMIT_MARGIN > limit` check between SWAR blocks guarantees
/// the tight inner loop cannot overflow. The SWAR band boundaries (<8, 8-15,
/// 16-24, 25-31, ≥32) are the chunk boundaries — do not subdivide them.
private static final int LIMIT_MARGIN = 1024;
```

One constant, shared by all paths (placed in each affected class). On a 16 MiB
buffer 1024 B = 0.006 %. Not configurable.

## 3a–3b: `DirectJsonStringWriter` — limit-aware Latin-1 overload

- [x] **3a.** Add `LIMIT_MARGIN` (definition-site comment above) to the **two**
  classes that run SWAR block loops with a between-block limit check:
  `DirectJsonStringWriter` (16-byte blocks, JSON escape) and `StringByteExtractor`
  (8-byte words, Latin-1→UTF-8). `WriteOps` and `EscapedJsonStringWriter` do **not**
  need it — `WriteOps` only delegates, and `EscapedJsonStringWriter` drops its
  grow-based direct path (3e).
- [x] **3b.** Add new limit-aware overload `writeEscapedLatin1NoGrow(byte[] buf,
  int pos, int limit, byte[] bytes)` in `DirectJsonStringWriter`. The SWAR band
  structure (<8, 8-15, 16-24, 25-31, ≥32) is **preserved unchanged** — only the
  capacity check between blocks is added (`pos + LIMIT_MARGIN > limit`):

```java
/// Writes a JSON-escaped Latin-1 string directly into buf[pos..limit).
/// Returns the new position, or -pos on overflow (the caller restores pos
/// from the magnitude and writes "V2BIG").
/// The SWAR band structure (<8, 8-15, 16-24, 25-31, ≥32) is preserved
/// unchanged — only the capacity check between blocks is replaced by a
/// limit check.
public static int writeEscapedLatin1NoGrow(byte[] buf, int pos, int limit, byte[] bytes) {
    int to = bytes.length;
    if (to < 8) {
        return writeEscapedLatin1PerByteNoGrow(buf, pos, limit, bytes, 0, to);
    }
    if (to <= 15) {
        long word = (long) LE_WORD.get(bytes, 0);
        if (isJsonAsciiWord(word) && isJsonAsciiTail(bytes, 8, to)) {
            if (pos + to > limit) return -pos;
            LE_WORD.set(buf, pos, word);
            if (to > 8) LE_WORD.set(buf, pos + (to - 8), LE_WORD.get(bytes, to - 8));
            return pos + to;
        }
        return writeEscapedLatin1PerByteNoGrow(buf, pos, limit, bytes, 0, to);
    }
    if (to <= 24) {
        long w0 = (long) LE_WORD.get(bytes, 0);
        long w1 = (long) LE_WORD.get(bytes, 8);
        long w2 = (long) LE_WORD.get(bytes, to - 8);
        if (isJsonAsciiWords3(w0, w1, w2)) {
            if (pos + to > limit) return -pos;
            LE_WORD.set(buf, pos, w0);
            LE_WORD.set(buf, pos + 8, w1);
            LE_WORD.set(buf, pos + (to - 8), w2);
            return pos + to;
        }
        return writeEscapedLatin1PerByteNoGrow(buf, pos, limit, bytes, 0, to);
    }
    if (to <= 31) {
        long w0 = (long) LE_WORD.get(bytes, 0);
        long w1 = (long) LE_WORD.get(bytes, 8);
        long w2 = (long) LE_WORD.get(bytes, 16);
        long w3 = (long) LE_WORD.get(bytes, to - 8);
        if (isJsonAsciiWords4(w0, w1, w2, w3)) {
            if (pos + to > limit) return -pos;
            LE_WORD.set(buf, pos, w0);
            LE_WORD.set(buf, pos + 8, w1);
            LE_WORD.set(buf, pos + 16, w2);
            LE_WORD.set(buf, pos + (to - 8), w3);
            return pos + to;
        }
        return writeEscapedLatin1PerByteNoGrow(buf, pos, limit, bytes, 0, to);
    }
    // ≥ 32 bytes: 16-byte block loop, LIMIT_MARGIN check between blocks.
    int i = 0;
    for (; i + 16 <= to; i += 16) {
        if (pos + LIMIT_MARGIN > limit) return -pos;
        long w0 = (long) LE_WORD.get(bytes, i);
        long w1 = (long) LE_WORD.get(bytes, i + 8);
        if (isJsonAsciiWords2(w0, w1)) {
            LE_WORD.set(buf, pos, w0);
            LE_WORD.set(buf, pos + 8, w1);
            pos += 16;
        } else {
            pos = writeEscapedLatin1PerByteNoGrow(buf, pos, limit, bytes, i, i + 16);
            if (pos < 0) return pos;   // propagate negated overflow
        }
    }
    if (i < to) {
        if (pos + LIMIT_MARGIN > limit) return -pos;
        pos = writeEscapedLatin1PerByteNoGrow(buf, pos, limit, bytes, i, to);
        if (pos < 0) return pos;       // propagate negated overflow
    }
    return pos;
}

private static int writeEscapedLatin1PerByteNoGrow(byte[] buf, int pos, int limit,
        byte[] bytes, int from, int to) {
    int segmentStart = from;
    for (int i = from; i < to; i++) {
        int b = bytes[i] & 0xFF;
        if (b < 0x20 || b == '"' || b == '\\' || b >= 0x80) {
            if (i > segmentStart) {
                pos = writeRawNoGrow(buf, pos, limit, bytes, segmentStart, i - segmentStart);
                if (pos < 0) return pos;
            }
            pos = writeEscapedByteNoGrow(buf, pos, limit, b);
            if (pos < 0) return pos;
            segmentStart = i + 1;
        }
    }
    if (segmentStart < to) {
        pos = writeRawNoGrow(buf, pos, limit, bytes, segmentStart, to - segmentStart);
    }
    return pos;
}
```

Add a limit-aware variant of the extraction path (non-null only — `WriteOps`
owns the packed `null` literal, Step 4a):

```java
/// Limit-aware variant of writeJsonString (non-null value only; null is handled
/// by WriteOps.writeEscapedJsonStringNoGrow via the packed "null" literal).
/// Returns new pos or -pos on overflow.
public static int writeJsonStringNoGrow(byte[] buf, int pos, int limit, String value) {
    int start = pos;
    if (pos + 1 > limit) return -pos;           // no room for opening quote
    buf[pos++] = '"';
    int bodyLimit = limit - 2;                  // reserve closing quote + 1 B headroom
    int bodyPos;
    if (STRING_CODER_HANDLE != null) {
        byte[] internal = (byte[]) STRING_VALUE_HANDLE.get(value);
        if (internal != null && ((byte) STRING_CODER_HANDLE.get(value)) == 0) {
            bodyPos = writeEscapedLatin1NoGrow(buf, pos, bodyLimit, internal);
        } else {
            bodyPos = writeEscapedCharsNoGrow(buf, pos, bodyLimit, value);
        }
    } else {
        bodyPos = writeEscapedCharsNoGrow(buf, pos, bodyLimit, value);
    }
    if (bodyPos < 0) return -start;             // overflow: revert to before opening quote
    buf[bodyPos++] = '"';                       // always fits: bodyPos <= limit - 2
    return bodyPos;
}
```

**Helper methods** (no-grow variants):

```java
private static int writeRawNoGrow(byte[] buf, int pos, int limit, byte[] src, int off, int len) {
    if (pos + len > limit) return -pos;
    System.arraycopy(src, off, buf, pos, len);
    return pos + len;
}

private static int writeByteNoGrow(byte[] buf, int pos, int limit, int b) {
    if (pos >= limit) return -pos;
    buf[pos] = (byte) b;
    return pos + 1;
}

private static int writeEscapedByteNoGrow(byte[] buf, int pos, int limit, int b) {
    if (pos + 6 > limit) return -pos;
    switch (b) {
        case '"': buf[pos++] = '\\'; buf[pos++] = '"'; break;
        case '\\': buf[pos++] = '\\'; buf[pos++] = '\\'; break;
        case '\b': buf[pos++] = '\\'; buf[pos++] = 'b'; break;
        case '\f': buf[pos++] = '\\'; buf[pos++] = 'f'; break;
        case '\n': buf[pos++] = '\\'; buf[pos++] = 'n'; break;
        case '\r': buf[pos++] = '\\'; buf[pos++] = 'r'; break;
        case '\t': buf[pos++] = '\\'; buf[pos++] = 't'; break;
        default:
            if (b < 0x20) {
                buf[pos++] = '\\'; buf[pos++] = 'u'; buf[pos++] = '0'; buf[pos++] = '0';
                buf[pos++] = HEX_DIGITS[b >>> 4]; buf[pos++] = HEX_DIGITS[b & 0xF];
            } else {
                buf[pos++] = (byte) (0xC0 | (b >> 6));
                buf[pos++] = (byte) (0x80 | (b & 0x3F));
            }
    }
    return pos;
}
```

## 3c: `writeEscapedCharsNoGrow` (UTF-16 path)

- [x] **3c.** Add `writeEscapedCharsNoGrow(byte[] buf, int pos, int limit, String value)`
  as a **faithful limit-aware copy of the existing `writeEscapedCharsCursor`**. The
  UTF-16/classic path has **no SWAR bands** (it is already a per-char `charAt` loop),
  so the "limit check between SWAR blocks" rule does not apply. Keep the exact
  per-char structure (escape table, `\u00XX` for control, UTF-8 for non-ASCII,
  surrogate pairs, lone-surrogate replacement) and add one per-char capacity check
  (`pos + N > limit`, worst case 6 B for `\u00XX`), returning `-pos` on overflow:

```java
/// Returns new pos or -pos on overflow. Byte-identical to writeEscapedCharsCursor,
/// adding only the per-char capacity check (this is the slow fallback path — it
/// already does per-char charAt + switch, so a per-char compare is negligible).
private static int writeEscapedCharsNoGrow(byte[] buf, int pos, int limit, String value) {
    int len = value.length();
    for (int i = 0; i < len; i++) {
        char ch = value.charAt(i);
        byte[] escape = escapeBytesForAsciiControl(ch);
        if (escape != null) {
            if (pos + 2 > limit) return -pos;
            buf[pos++] = escape[0];
            buf[pos++] = escape[1];
        } else if (ch < 0x20) {
            if (pos + 6 > limit) return -pos;
            buf[pos++] = '\\';
            buf[pos++] = 'u';
            buf[pos++] = '0';
            buf[pos++] = '0';
            buf[pos++] = HEX_DIGITS[(ch >>> 4) & 0xF];
            buf[pos++] = HEX_DIGITS[ch & 0xF];
        } else if (ch <= 0x7F) {
            if (pos + 1 > limit) return -pos;
            buf[pos++] = (byte) ch;
        } else if (ch <= 0x7FF) {
            if (pos + 2 > limit) return -pos;
            buf[pos++] = (byte) (0xC0 | (ch >> 6));
            buf[pos++] = (byte) (0x80 | (ch & 0x3F));
        } else if (Character.isHighSurrogate(ch) && i + 1 < len
                && Character.isLowSurrogate(value.charAt(i + 1))) {
            if (pos + 4 > limit) return -pos;
            int codePoint = Character.toCodePoint(ch, value.charAt(i + 1));
            buf[pos++] = (byte) (0xF0 | (codePoint >> 18));
            buf[pos++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
            buf[pos++] = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
            buf[pos++] = (byte) (0x80 | (codePoint & 0x3F));
            i++;
        } else if (Character.isHighSurrogate(ch) || Character.isLowSurrogate(ch)) {
            if (pos + 3 > limit) return -pos;
            buf[pos++] = (byte) 0xEF;
            buf[pos++] = (byte) 0xBF;
            buf[pos++] = (byte) 0xBD;
        } else {
            if (pos + 3 > limit) return -pos;
            buf[pos++] = (byte) (0xE0 | (ch >> 12));
            buf[pos++] = (byte) (0x80 | ((ch >> 6) & 0x3F));
            buf[pos++] = (byte) (0x80 | (ch & 0x3F));
        }
    }
    return pos;
}
```

No `writeEscapedCharInline` helper is introduced — the previous draft's version
wrote an 8-byte `\u00XXXX` for all non-ASCII/control, mishandled surrogate pairs,
and referenced an out-of-scope `limit`. This corrected form reuses
`escapeBytesForAsciiControl` and `HEX_DIGITS` already in `DirectJsonStringWriter`.

## 3d: `StringByteExtractor` — limit-aware core, keep the 2-arg entry point

- [x] **3d.** Add a limit-aware core and keep the existing 2-arg `writeLatin1` as the
  entry point (its `ByteWriter`/`STRING_STRATEGY` call chain cannot carry a `limit`):

```java
/// Limit-aware Latin-1→UTF-8 core: 8-byte word loop with a LIMIT_MARGIN check
/// between words (worst case 2 B/byte, so 1024 B covers 512 source bytes per
/// check). Returns new pos or -pos on overflow; never grows.
public static int writeLatin1NoGrow(byte[] buf, int pos, int limit, byte[] latin1Bytes) {
    // ... same word loop as the old writeLatin1Direct, limit checks instead of rbo.grow ...
}

/// Entry point (unchanged 2-arg signature). For a ReusableByteArrayOutputStream
/// the bound is rbo.buf.length (the whole buffer); overflow throws
/// BufferFullException, which the stack-field try/catch converts to "V2BIG".
public static void writeLatin1(OutputStream out, byte[] latin1Bytes) throws IOException {
    if (out instanceof ReusableByteArrayOutputStream rbo) {
        int newPos = writeLatin1NoGrow(rbo.buf, rbo.pos, rbo.buf.length, latin1Bytes);
        if (newPos < 0) throw new BufferFullException();
        rbo.pos = newPos;
        return;
    }
    writeLatin1Stream(out, latin1Bytes, 0, latin1Bytes.length);
}
```

**Why keep the 2-arg form:** `writeVarHandle` (StringByteExtractor:112) and the
`ByteWriter`/`STRING_STRATEGY` chain used for the `stack` field's
`throwableClassName` (and `JsonLogWriter.writeRawValue` / `JsonLogWriterClassic`)
call the 2-arg method; a `ByteWriter` cannot pass a `limit`. The stack-field write
is wrapped in try/catch in Step 5e, so a `BufferFullException` from the
whole-buffer bound is converted to `writeTooLargeField`. The old grow-based
`writeLatin1Direct`/`copyDirect` are replaced by `writeLatin1NoGrow` (in-place).

## 3e: `EscapedJsonStringWriter` — drop the grow-based direct path

- [x] **3e.** `EscapedJsonStringWriter` drops its grow-based direct path
  (`writeEscapedLatin1Direct` and its `copyDirect`/`writeEscapedByteDirect`): the
  `OutputStream` dispatcher routes `ReusableByteArrayOutputStream` targets through
  `writeEscapedLatin1Stream`, whose `out.write(...)` calls now throw
  `BufferFullException` via the no-grow RBO write methods (Step 2b). The dev
  writer (`JsonLogWriterDev.writeExtraFields`) no longer goes through
  `EscapedJsonStringWriter.writeJsonStringOrNull(out, ...)` — it calls
  `WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, value)` directly (Step 5h).

## Interaction with `RESERVE` / `limit`

`limit = buf.length - RESERVE` leaves 27 B at the tail. The limit-aware writers
check `pos + LIMIT_MARGIN > limit` (1024 ≪ buf.length for realistic buffers),
so the closing `"` and `}` and `\n` always fit in the reserved tail.

## Validation

- `mvn -o -pl core,logback test` passes; normal-event output byte-identical.
- Correctness at the boundary: a string whose escaped length lands exactly at
  `limit` must not overflow; the margin check fires with ≥ 16 source bytes
  still to process, so the worst case (96 B output) is covered.
- Confirm zero allocation on the fits hot path (placeholder/limit path is cold only).
