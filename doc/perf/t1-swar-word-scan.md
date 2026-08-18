# T1 — SWAR Word-at-a-Time JSON Escape Scan

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871)).
Fory files: `java/fory-json/.../writer/Utf8JsonWriter.java` (`writeString`,
`isJsonAsciiWord`, `isJsonAsciiWords`, fallbacks) and
`writer/StringJsonWriter.java` (`writeString`).

## What Fory does

Fory writes a Latin-1 compact `String` into its JSON buffer by loading 8 bytes
at a time as one little-endian `long` and testing the whole word with a
bit-parallel ("SWAR", *SIMD Within A Register*) predicate. A clean word — every
byte is printable ASCII that needs no JSON escaping — is copied in one
8-byte store; only a word containing a byte that must be escaped
(`"` 0x22, `\` 0x5C, control `< 0x20`, non-ASCII `>= 0x80`) falls back to the
per-byte escape loop. This turns ~8 branchy per-byte classifications into a
handful of integer ops + one branch per 8 bytes.

The predicates (Fory `Utf8JsonWriter.java`, lines 1955–2075 at that SHA):

```java
private static final long HIGH_BITS                  = 0x8080808080808080L;
private static final long ASCII_CONTROL_OFFSET       = 0x6060606060606060L;
private static final long ASCII_GT_QUOTE_OFFSET      = 0x5D5D5D5D5D5D5D5DL;
private static final long ONE_BYTES                  = 0x0101010101010101L;
private static final long QUOTE_BYTES_COMPLEMENT     = ~0x2222222222222222L;
private static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;

// Fast path: true iff every byte is in (0x22, 0x5C) ∪ (0x5C, 0x80), i.e.
// printable ASCII strictly above '"' and not '\\'. NO false negatives: every
// byte that needs escaping fails this test and reaches the exact fallback.
private static boolean isJsonAsciiWord(long word) {
    long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    if ((notBackslashMask & (word + ASCII_GT_QUOTE_OFFSET)) == HIGH_BITS) {
        return true;
    }
    return isJsonAsciiWordFallback(word);
}

// Exact per-byte test: byte in [0x20, 0x7F] and not '"' and not '\\'.
private static boolean isJsonAsciiWordFallback(long word) {
    long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    return (((word + ASCII_CONTROL_OFFSET) & ~word) & HIGH_BITS) == HIGH_BITS
        && (((word ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS) == HIGH_BITS
        && notBackslashMask == HIGH_BITS;
}
```

The arithmetic, per byte (little-endian bit lanes):

- `b + 0x5D >= 0x80` ⟺ `b >= 0x23` for `b <= 0x7F` → "greater than quote".
- `(b ^ ~0x5C) + 1 = -(b ^ 0x5C)`; the high bit is set ⟺ `b != 0x5C`. Because
  `b ^ 0x5C` for non-ASCII `b >= 0x80` never equals 0, non-ASCII bytes also
  fail the fast path through this mask — exactly what an *escaping* writer needs.
- Fallback: `(b + 0x60) & ~b` sets the high bit ⟺ `b in [0x20, 0x7F]` (control
  and high-bit bytes rejected), the quote term rejects exactly `b == 0x22`,
  the mask rejects `b == 0x5C`. Together: the exact `isJsonAscii` predicate.

The 2/3/4-word variants (`isJsonAsciiWords`, `isJsonAsciiWords3/4` in this
repo) AND the masks across words so one branch serves 16–32 bytes, and the
exact masks are aggregated before branching in the fallbacks.

## What dia-log did before

`EscapedJsonStringWriter.writeEscapedLatin1(OutputStream, byte[])` scanned
byte-by-byte: for each byte it ran a `switch`
(`escapeBytesForAsciiControl`) plus three comparisons, flushed clean ASCII runs
as bulk writes, and emitted escapes through the stream. For typical log content
(class names, method names, messages, keys) nearly every byte is clean, yet
every byte still paid the full classification cost.

The pre-change implementation is preserved verbatim (in shape) as the
comparison baseline:
`core/src/test/java/hr/hrg/dialog/core/perf/ClassicEscapedStringWriter.java`.

## What dia-log does now

`core/src/main/java/hr/hrg/dialog/core/EscapedJsonStringWriter.java`:

- New SWAR constants + predicates (direct port of Fory's, with the same
  constants and fallback structure).
- Word loads via `MethodHandles.byteArrayViewVarHandle(long[].class,
  ByteOrder.LITTLE_ENDIAN)` — a standard JDK API that needs no `--add-opens`
  and is byte-order portable (byte *i* always lands in bits 8*i..8*i+7).
- `writeEscapedLatin1` now branches on the sink:
  - **stream mode** (`ByteArrayOutputStream` and friends): clean words stay
    inside one pending segment flushed as one bulk `write(byte[], off, len)`
    (the documented ≈51× cheaper bulk form); dirty words pay the per-byte cost.
  - **direct mode** (`ReusableByteArrayOutputStream`, the production event
    buffer): clean words are copied straight into the backing array through
    the T4 direct-buffer API; escapes are stored inline.

`core/src/main/java/hr/hrg/dialog/core/StringByteExtractor.java`:
`writeLatin1` uses the same structure with the simpler high-bit test
`(word & 0x8080808080808080L) == 0` — a clean word is any word with no byte
`>= 0x80`, since this writer's only job is Latin-1→UTF-8 expansion.

## Why it is faster

| work | per-byte (old) | per 8-byte word (new) |
|---|---|---|
| classification | 1 switch + 3 compares + branch | ~5 integer ops + 1 compare + branch |
| escapes | only at dirty bytes | only at dirty words |
| output | bulk per clean run | bulk per clean run / packed stores (T4) |

Escape-heavy and Latin-1-mixed content still take the exact same path as
before (the per-byte emitters are unchanged in semantics), so worst-case
behavior is unchanged while the common case (clean ASCII) is ~8× less work per
byte plus fewer stream calls.

## Verification

- `ForyPerfComparisonTest.escapedLatin1_classicVsNewStreamVsNewDirect_allMatchReference`
  compares old vs new (both modes) against an independent reference escaper
  over a battery covering every length 0..48, special bytes at every position,
  control chars, the full Latin-1 range, and 300 seeded random inputs.
- `ForyPerfComparisonTest.latin1ToUtf8_classicVsNewStreamVsNewDirect` does the
  same for `StringByteExtractor`.
- Benchmark: `ForyPerfComparisonBenchmark` (`escapingClassic` /
  `escapingNewStream` / `escapingNewDirect`, `latin1*`).
