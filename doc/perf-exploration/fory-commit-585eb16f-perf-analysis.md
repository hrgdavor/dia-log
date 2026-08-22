# Performance Gains Extractable from apache/fory commit 585eb16f

Analyzed: `feat(java): optimize json perf (#3871)` — Shawn Yang, 2026-07-26,
https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6

Fory is a Java JSON library; dia-log is a JSON **log writer**. The overlap is the
JSON *writer* hot path: UTF-8 output, string escaping, integer formatting, and
buffer management. Reader/codegen techniques are flagged as not applicable at
the end. All line references below are to the commit diff
(`fory-commit.diff`, kept next to this doc) and to the Fory sources at that SHA
(`Utf8JsonWriter.java` / `StringJsonWriter.java`).

> **Implementation status:** T1–T6 are implemented in this repo. Each
> technique has a dedicated document under `doc/perf-exploration/` with the
> before/after code, the Fory attribution, and verification:
>
> - [T1 — SWAR word-at-a-time JSON escape scan](t1-swar-word-scan.md)
> - [T2 — length-specialized string writers](t2-length-specialized-writers.md)
> - [T3 — inlined capacity checks](t3-inlined-capacity-checks.md)
> - [T4 — writer-owns-buffer direct API](t4-writer-owns-buffer.md)
> - [T5 — packed digit tables](t5-packed-digit-tables.md)
> - [T6 — packed field-name prefixes](t6-packed-field-prefixes.md)
>
> The pre-optimization implementations are preserved as benchmark baselines in
> `core/src/test/java/hr/hrg/dialog/core/perf/` and compared for byte
> equivalence by `ForyPerfComparisonTest` /
> `JsonLogWriterDirectBufferTest`, with head-to-head JMH benchmarks in
> `ForyPerfComparisonBenchmark`.

The techniques are grouped by impact. T1–T3 are drop-in micro-optimizations
that need no architectural change; T4 is a structural change; T5–T8 are
smaller or conditional gains.

---

## T1 — SWAR word-at-a-time JSON-escape scan (highest impact, drop-in)

### What Fory does

`Utf8JsonWriter.writeString` (diff line 9601+) and `StringJsonWriter.writeString`
(8437+) write a Latin-1 compact `String` by loading 8 bytes at a time with
`LittleEndian.getInt64`, testing the whole word with a bit-parallel
("SWAR") predicate, and copying it with `LittleEndian.putInt64` — 8 or 16
bytes per iteration. Only when a word contains a byte that needs escaping
(`"` 0x22, `\` 0x5C, control `< 0x20`, non-ASCII) does it fall back to the
per-byte escape loop, starting at that word.

The exact predicates (from `Utf8JsonWriter.java` at this SHA, lines 1955–2075):

```java
private static final long HIGH_BITS                  = 0x8080808080808080L;
private static final long ASCII_CONTROL_OFFSET       = 0x6060606060606060L;
private static final long ASCII_GT_QUOTE_OFFSET      = 0x5D5D5D5D5D5D5D5DL;
private static final long ONE_BYTES                  = 0x0101010101010101L;
private static final long QUOTE_BYTES_COMPLEMENT     = ~0x2222222222222222L;
private static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;

// Fast path: true iff every byte is in (0x22, 0x5C) ∪ (0x5C, 0x80)
// i.e. printable ASCII strictly above '"' and not '\\'. No false negatives:
// every byte that needs escaping fails this test and reaches the fallback.
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

Math (per byte, little-endian bit positions):

- `b + 0x5D >= 0x80` ⟺ `b >= 0x23` for `b <= 0x7F` → "greater than quote".
- `(b ^ ~0x5C) + 1 = -(b ^ 0x5C)`; high bit set ⟺ `b != 0x5C` (backslash
  mask). Note `b ^ 0x5C` for non-ASCII `b >= 0x80` never yields 0, so
  non-ASCII bytes also fail the fast path via the mask.
- Fallback: `(b + 0x60) & ~b` sets the high bit ⟺ `b in [0x20, 0x7F]`
  (control rejected, high-bit rejected); quote term rejects exactly `b == 0x22`;
  backslash mask rejects `b == 0x5C`. Together: exact `isJsonAscii`.

The 2-word variant `isJsonAsciiWords(word0, word1)` (16-byte chunks) ANDs the
masks across both words so one branch serves 16 bytes; the `...Fallback`
variants aggregate the exact masks before branching (no per-word calls).

The writer then copies the clean word(s) with `LittleEndian.putInt64` and only
touches the slow path for dirty words. `StringJsonWriter`'s Latin-1 path is the
same shape (16-byte: `word0, word1` + `isJsonAsciiWords`; then 8-byte, 4-byte,
2-byte, 1-byte tails, always validating before copying).

### Why it helps

Per-byte classification (a `switch`/comparison chain per byte) becomes ~4
integer ops + 1 compare per 8 bytes. For typical log content — class names,
method names, messages, keys — nearly every word is clean, so the hot loop is
load → test → store/bulk-write, with no branch per byte.

### Where it applies in dia-log

- `core/.../EscapedJsonStringWriter.writeEscapedLatin1(OutputStream, byte[])`
  (lines 145–178): currently scans per byte with a `switch`
  (`escapeBytesForAsciiControl`) + 3 comparisons. Replace the per-byte scan
  with: read 8 bytes as `long` (little-endian), run `isJsonAsciiWord`; if
  clean, append the whole word to the current segment (bulk
  `out.write(internal, segmentStart, ...)`); if dirty, flush the clean segment
  and handle the offending byte with the existing escape logic, then resume
  word scanning after it. Preserve the existing segment-flush behavior and
  escaping semantics exactly (`\b \f \n \r \t \" \\`, `\u00XX`, UTF-8
  expansion of `0x80..0xFF`).
- `core/.../StringByteExtractor.writeLatin1(OutputStream, byte[])`
  (lines 114–130): currently per-byte `v >= 0x80` test. A clean word there is
  simply `(word & 0x8080808080808080L) == 0` (no byte ≥ 0x80) — one AND + one
  compare per 8 bytes — then one bulk `out.write(latin1Bytes, segStart, len)`
  per clean run, exactly matching the documented
  "batch contiguous ASCII runs into bulk writes" rule (≈51× cheaper than
  per-byte writes).

### Copy guidance

- Little-endian load/store of `byte[]`: dia-log has no `LittleEndian` helper
  yet; add a tiny one (or inline `(b0 & 0xFFL) | ... << 8` assembly). The
  *store* side is byte-order independent if the word is assembled so that byte
  `i` lands at offset `i` — i.e. build the word little-endian and write it with
  `putInt64` little-endian; only run the SWAR path on little-endian JVMs
  (`ByteOrder.nativeOrder() == LITTLE_ENDIAN`, probed once at class init like
  `Wyhash64` does), and keep the current per-byte loop as the big-endian /
  fallback path.
- Keep the byte-array based path (already coder-checked): `writeEscapedLatin1`
  only runs when `coder == 0`, so byte order of `String.value` is irrelevant —
  the load side only needs a little-endian read of a `byte[]`, which is
  portable via explicit assembly (no `ByteBuffer` allocation).
- Do **not** touch `writeEscapedJsonStringClassic`'s UTF-16 path (it is
  coder-agnostic by design; a word scan there would need the platform-native
  UTF-16 byte order, which AGENTS.md forbids assuming).

---

## T2 — Length-specialized string writers + overlapping 3-word stores

### What Fory does

`Utf8JsonWriter.writeString` dispatches Latin-1 strings by length band
(diff 9618–9712):

- `length < 8`: `writeLatin1String0To7` (fully unrolled per-byte).
- 8–15: one `getInt64` word, then int/short/byte tails, each validated before
  storing (single `isJsonAsciiWord` branch for the whole string).
- 16–24: **three-word trick** — load words at offsets 0, 8 and `length-8`,
  run the 3-word SWAR test once, then store all three:
  ```java
  int tailOffset = length - Long.BYTES;
  long tail = LittleEndian.getInt64(stringBytes, tailOffset);
  // one 3-word predicate over word0, word1, tail
  LittleEndian.putInt64(bytes, pos, word0);
  LittleEndian.putInt64(bytes, pos + 8, word1);
  LittleEndian.putInt64(bytes, pos + tailOffset, tail);
  pos += length;
  ```
  The last store overlaps the first two but writes byte-identical data — 3
  loads + 3 stores for any 17–24 byte string, no tail handling.
- 25–31 and ≥32: dedicated helpers, ≥32 runs 16-byte blocks
  (`isJsonAsciiWords(word0, word1)`).
- Only when a word is dirty does the writer `position = start;` and enter the
  slow escape path — the cursor is published only after complete validation.

### Why it helps

Branches and loop overhead scale with the *number of bands*, not the string
length; the common case (clean short strings, which dominate log output) is a
fixed small number of loads/stores and exactly one escape test.

### Where it applies in dia-log

The same length-band shape can be layered onto T1 in
`EscapedJsonStringWriter` and `StringByteExtractor` if benchmarked to pay off
over the plain word loop. At minimum, adopt the 16–24 three-word trick and the
"publish cursor only after validation" rule (dia-log's segment-based writers
already do the equivalent by buffering a segment and flushing).

### Copy guidance

Implement after T1, measure with the existing JMH-style benchmarks
(`logback-writer-comparison-benchmark-results.md` methodology) before
accepting; the plain 8/16-byte word loop already captures most of the win.

---

## T3 — Inline capacity checks: `ensure(n)` → local check + `grow(additional)`

### What Fory does

Every `ensure(n)` call in `StringJsonWriter` and `Utf8JsonWriter` is replaced
by an inlined local check, and `grow` now takes the *incremental* byte count:

```java
// before
ensure(prefix.length + 5);
writeRawLatin1NoEnsure(prefix);

// after
int additional = prefix.length + 5;
if (position + additional > buffer.length) {
    grow(additional);
}
writeRawLatin1NoEnsure(prefix);

// grow computes the absolute capacity only on the cold path
private void grow(int additional) {
    int minCapacity = position + additional;
    buffer = Arrays.copyOf(buffer, growCapacity(buffer.length, minCapacity));
}
```

The comment (diff 9431–9434 / 10834–10837) states the rationale: the local
check lets hot methods keep their `position` cursor and `buffer` in registers;
the common no-grow case is a single compare + branch with no call, and only the
cold path computes the absolute capacity. Single-byte writes even specialize to
`if (pos == buffer.length) grow(1);`.

### Why it helps

Removes a method call (and its `position + additional` recomputation against
the current cursor) from every field/value write; keeps `position`/`buffer`
live across the write so C2 avoids reloads; the grow branch is never-taken in
steady state (1 MiB event buffer).

### Where it applies in dia-log

- `core/.../ReusableByteArrayOutputStream.write(int)` and `write(byte[],off,len)`
  already inline their checks — nothing to do there.
- The real target was the *callers*: `JsonLogWriter.writeJsonEvent` funneled
  every byte through `OutputStream.write(...)` virtual calls (see T4). With T4
  the dispatcher is gone — callers invoke `writeJsonEventDirect` (reusable
  buffer) or `writeJsonEventStream` explicitly. Every site that previously
  relied on `out.write` must apply the
  local-check pattern against the direct buffer.
- `JsonNumberWriter.writeInt/writeLong` build into a caller-provided buffer of
  exactly `MAX_*_BYTES` — no growth needed; if T5 lands, drop the length guard
  checks from the hot loop (keep the `IllegalArgumentException` validation once
  at call time if desired).

---

## T4 — Writer-owns-buffer API (structural; largest end-to-end win)

### What Fory does

`Utf8JsonWriter` exposes `@Internal public byte[] getBuffer()`,
`getPosition()`, `setPosition(int)`, `grow(int)` (diff 9502–9515, 10838–10839).
Generated codecs then write **directly into the buffer** with local capacity
checks, packed prefix stores, and one `setPosition` per field group — no
`OutputStream` in the hot path at all. `writeObjectEnd` in generated code even
does `writer.grow(1); objectEndBuffer[...] = '}'; writer.setPosition(...);
writer.setDepth(writer.getDepth() - 1);` inline (Utf8WriterCodegen diff
4363–4377). `writeRaw(byte[])` likewise becomes a direct `System.arraycopy`
with an inlined check instead of an `ensure` + helper call (diff 10707–10717).

### Why it helps

`OutputStream.write(int)` is a virtual call per byte; even `write(byte[],off,len)`
is a virtual call per segment plus an `arraycopy`. Fory's shape turns the whole
event assembly into: capacity check (local compare), direct stores, one
`setPosition`. Per-field call overhead collapses.

### Where it applies in dia-log

`JsonAppender.writeOut` already assembles each event into the reusable
`ReusableByteArrayOutputStream` and flushes once — the architecture is already
Fory-shaped. The missing piece is that `JsonLogWriter.writeJsonEvent` writes
through the `OutputStream` interface. Two options:

1. **Minimal (implemented):** add a direct-write fast path to
   `ReusableByteArrayOutputStream` — e.g. `writeWord(long)` / `writeInt(int)`
   little-endian stores with an inlined capacity check, plus a
   `writeRaw(byte[], int, int)` that inlines the check + `System.arraycopy`.
   `EscapedJsonStringWriter` / `StringByteExtractor` / `JsonNumberWriter` get
   overloads that take the buffer object. Keeps the public `OutputStream`
   API (jackson still needs it) while the known hot fields bypass dispatch.
2. **Full (implemented — see [t4-writer-owns-buffer.md](t4-writer-owns-buffer.md)):**
   give `JsonLogWriter` a direct `byte[] + position` assembly mode
   (like Fory's `getBuffer/getPosition/setPosition`), falling back to the
   stream only for `mapper.writeValue`, `RawValue`, the generated stack-trace
   writers and the dev `writeExtraFields`. Measured 108 → 95 ns/op on the
   event benchmark (≈1.14× over option 1, ≈2.3× over the pre-Fory baseline).
   The cursor machinery lives in `DirectJsonBuffer` / `DirectJsonStringWriter`
   — keeping `EscapedJsonStringWriter` small was required: adding the cursor
   methods to it silently regressed the per-string hot path 4–6× through
   changed C2 inlining (see the results doc).

Keep the AGENTS.md invariants: one bulk `writeTo(activeStream)` per event,
`activeStream` snapshot semantics, no per-event buffers, and the dev/diagnostic
variants (`JsonLogWriterDev`, `JsonLogWriterClassic`) stay unoptimized.

---

## T5 — Packed digit tables (`DIGIT_TRIPLES` / `DIGIT_QUADS`) + multiply-divide for int/long formatting

### What Fory does

Precomputed tables pack 3 and 4 ASCII digits into one int, little-endian
(`Utf8JsonWriter.java` lines 97–118):

```java
private static final int[] DIGIT_TRIPLES = new int[1000];   // 1–3 digits + skip count
private static final int[] DIGIT_QUADS   = new int[10000];  // exactly 4 digits

// DIGIT_TRIPLES[i] = skip | (c0 << 8) | (c1 << 16) | (c2 << 24)
//   where skip = leading-zero count (0, 1, or 2), c0..c2 = ASCII digits of i
// DIGIT_QUADS[i]  = c0 | (c1 << 8) | (c2 << 16) | (c3 << 24)

// 1–3 significant digits in one lookup + one shifted store:
private static int writeIntUpTo3(byte[] bytes, int pos, int value) {
    int digits = DIGIT_TRIPLES[value];
    int skip = digits & 0xFF;
    LittleEndian.putInt32(bytes, pos, digits >>> ((skip + 1) << 3));
    return pos + 3 - skip;
}

// exactly 4 digits in one store; 8 digits in one 8-byte store:
private static int writePadded4(byte[] bytes, int pos, int value) {
    LittleEndian.putInt32(bytes, pos, DIGIT_QUADS[value]);
    return pos + 4;
}
private static int writePadded8(byte[] bytes, int pos, int high, int low) {
    LittleEndian.putInt64(bytes, pos,
        (DIGIT_QUADS[high] & 0xFFFFFFFFL) | ((long) DIGIT_QUADS[low] << 32));
    return pos + 8;
}

// Division by 10000 via multiply-shift (no idiv):
private static int divide10000(int value) {
    return (int) (((long) value * 1759218605L) >> 44);
}
```

`writePositiveLong` (lines 2148–2215): if `value <= Integer.MAX_VALUE` it
delegates to the int formatter (a cheap range test avoids the full-width path);
otherwise splits by `EIGHT_DIGITS` (100_000_000) and emits two 4-digit quads
per 8-byte store, using `divide10000` and `DIGIT_TRIPLES` for the ragged top.
A full `long` costs ~3 divisions and ~3 stores instead of up to 19 divisions
and 19 stores.

### Why it helps

`idiv` is tens of cycles and serializing; the packed-table approach replaces
per-digit division with one multiply-shift per 4 digits and one 4/8-byte store.
dia-log's `JsonNumberWriter.writeLong` divides by 10 per digit
(`while (value >= 10) { long q = value / 10; ... }`); `writeInt` already uses
`DIGIT_PAIRS` (2 digits per division) — the 4-digit step is the next jump.

### Where it applies in dia-log

`core/.../JsonNumberWriter.writeInt` and `writeLong` (lines 62–127):

- Extend the existing `DIGIT_PAIRS` table with `DIGIT_QUADS[10000]` and
  `DIGIT_TRIPLES[1000]` (same init pattern, static, ~44 KB total).
- `writeLong`: `while (value >= 10_000) { q = divide10000-ish(value); ... }` —
  for `long` use `value / 10_000` (JIT strength-reduces constant division to
  multiply-shift anyway) then `DIGIT_QUADS[(int) r]`; emit the ragged top with
  `DIGIT_TRIPLES`; keep the `value == Long.MIN_VALUE` and negative handling.
  Add the `value <= Integer.MAX_VALUE → writeInt` fast path.
- Byte-order: `DIGIT_QUADS` packs little-endian, so write little-endian
  (`buf[i] = (byte)(q); buf[i+1] = (byte)(q>>>8); ...` or a small
  `putInt32`/`putInt64` helper) — byte order is the *output* byte order and is
  portable, unlike `String.value` access. On big-endian JVMs either flip with
  `Integer.reverseBytes` per lookup or keep the existing per-digit path.
- `writeInt` can adopt `divide10000`-style chunking to halve its divisions,
  but its 2-digit `DIGIT_PAIRS` loop is already decent; measure before churn.
- Ryu (`RyuFloat`/`RyuDouble`) is already a dedicated digit generator; leave it
  alone.

---

## T6 — Packed field-name prefixes as `long` constants

### What Fory does

The codegen precomputes each schema field prefix as up to two `long`
constants (little-endian packed) and generated code writes them with
`LittleEndian.putInt64` (Utf8WriterCodegen `directPackedPrefix`, diff
4386–4433), including the dynamic comma/no-comma selection via two packed
prefixes (diff 4465–4484). Object start `{` is fused into the first prefix by
shifting: `(prefix0 << 8) | '{'` (diff 4442–4463), so `{"name":` costs one
capacity check + two `putInt64` stores.

### Why it helps

The `KEY_*` byte[] prefixes in dia-log (`"ts":`, `"level":`, ... all ≤ 16
bytes) are currently written via `out.write(byte[])` → virtual call +
`arraycopy` (or worse, per-field `write` in the classic path). Packed stores
make each known key 1–2 direct 8-byte stores.

### Where it applies in dia-log

With T4 (direct-buffer mode), replace `KEY_*` `byte[]` fields with precomputed
`long` pairs (packed at class init from the existing `getBytes` results) and
write them with two `putInt64`-style stores. For stream mode, batch all fixed
prefix bytes into one `out.write(prefixBytes)` — already the case. Note: the
dynamic `writeFieldPrefixRawKey` path (user keys) must keep full JSON escaping
(AGENTS.md escape discipline) — only the *fixed* keys qualify for packing.

---

## T7 — Date-time packed digit quads + division-free nano fraction (conditional)

`writeOffsetDateTime` (diff 9750–9852) builds `YYYY-MM-DDTHH:MM:SS.fffffffffZ`
from `DIGIT_QUADS` lookups with two `putInt64` stores for the first 16 bytes,
and tests `(nano & 7) != 0 || nano % 125 != 0` to route 7 of 8 arbitrary nanos
to the nine-digit path without a division (divisibility by 1000 = 8·125; the
power-of-two factor is tested first).

dia-log writes `"ts":` as epoch millis (`JsonNumberWriter.writeLong`), so this
is **not directly applicable** today. Revisit only if an ISO-8601 timestamp
formatter is ever added (e.g. a `"tsIso"` field); the packed-quad pattern is
then copyable verbatim.

---

## T8 — Pool lease via `AtomicInteger` + `lazySet` release (conditional)

`ForyJson` (diff 239–357) replaced the `AtomicReferenceArray` + `getAndSet`
slot pool with a plain `PooledState[]` array where each slot owns its
`JsonState` for life and leases it with `leased.compareAndSet(0,1)` on acquire
and `leased.lazySet(0)` on release. Release no longer stores a reference into
the array (no GC store barrier), the state reference is never re-published,
and overflow allocates a non-pooled state.

dia-log's `ReusableByteArrayOutputStream` already reuses its buffer with no
atomics (appender is synchronized), so this pattern has no direct target here.
It is the right shape if a lock-free writer pool is ever needed (e.g. a
virtual-thread-friendly appender) — keep it in mind, do not implement now.

---

## T9 — C2 inline-budget discipline (alignment with existing AGENTS.md rules)

The commit is explicitly engineered around HotSpot's hot-inline budget
(325-byte limit on JDK 25): large bodies like `writeString` are deliberately
kept as stable call boundaries ("representation owners"), tiny helpers are
*not* extracted because C2 would absorb them and pick different transitive
closures depending on compilation order, and duplicated lanes are kept
(`writeLongArray`, `writeLongNoEnsure` vs `writeLongFieldNoEnsure`) because
merging lets one call profile dictate another's inline shape.

This is the same philosophy dia-log already encodes in AGENTS.md
("code duplication is intentional micro-optimization", "do not refactor into a
common base class" for the stack sanitizers). The extractable lesson: when
optimizing `EscapedJsonStringWriter` / `JsonNumberWriter`, keep each hot entry
a self-contained body — do not split the SWAR scan or digit emitters into
per-call helpers "for cleanliness", and verify with
`-XX:+PrintInlining` / `-XX:+LogCompilation` that the hot path is one nmethod.

---

## Not applicable / out of scope

- **Reader side** (bulk of the diff): `Utf8JsonReader`, `Latin1JsonReader`,
  SWAR string parsing, 8-digit batch parsing, `INT_MAX_DIV_10` overflow
  checks, `consumeNextStringArrayElement` token fusion, split comma/end
  profiles. dia-log never parses JSON.
- **Eisel–Lemire compact decimal → double** (`DecimalMath`,
  `tryCompactDoubleBits`, `COMPACT_DOUBLE_MANTISSAS`): a *parsing* fast path;
  dia-log's output side already uses Ryu for the reverse direction.
- **Codegen machinery**: `JsonCodegen`/`JsonReaderCodegen`/`JsonWriterCodegen`
  grouping and async JIT — Fory generates codecs at runtime; dia-log writes by
  hand. Only the *insights* (T4, T9) transfer.
- **`ArrayListCodecSupport` VarHandle element-snapshot loop**, exact collection
  codecs, `ClosedSubtypeCodec`, `JsonTypeResolver` reshuffle: all
  reader/resolver-specific.
- **Pooled state (T8)** without a concrete lock-free consumer.

---

## Suggested work order

| #   | Item                                                                  | Effort | Expected impact                | Notes                                                          |
| --- | --------------------------------------------------------------------- | ------ | ------------------------------ | -------------------------------------------------------------- |
| 1   | T1 in `StringByteExtractor.writeLatin1` (0x80 word test)              | S      | High on stack-trace path       | Pure win; complements existing bulk-write rule                 |
| 1   | T1 in `EscapedJsonStringWriter.writeEscapedLatin1` (SWAR escape scan) | S–M    | High on all string fields      | Preserve escaping semantics exactly; keep classic UTF-16 path  |
| 2   | T5 in `JsonNumberWriter.writeLong` (quads + int fast path)            | S      | Medium (ts, errHash, counters) | 4-digit chunking, little-endian stores                         |
| 3   | T4 option 1 (direct-write helpers on `ReusableByteArrayOutputStream`) | M      | High end-to-end                | Benchmark before/after per `benchmark-optimization-history.md` |
| 4   | T6 packed key prefixes                                                | M      | Medium                         | Only after T4                                                  |
| 5   | T2 length bands, T3 everywhere, T4 option 2                           | M–L    | Incremental                    | Measure each step; keep dev variants unoptimized               |
Verification: extend the existing benchmark classes
(`logback-writer-comparison-benchmark-results.md`, `allocation-benchmark-results.md`)
so each item lands with a measured before/after; respect the dev-variant
policy — never add these micro-optimizations to `JsonLogWriterDev`,
`JsonLogWriterClassic`, or benchmark fixtures.
