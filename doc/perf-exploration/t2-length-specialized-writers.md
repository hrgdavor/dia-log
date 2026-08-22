# T2 — Length-Specialized String Writers

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871)).
Fory files: `java/fory-json/.../writer/Utf8JsonWriter.java` (`writeString`
length-band dispatch, `writeLatin1String0To7`, the 16..24 three-word path,
`writeLatin1String25To31`, `writeLongLatin1StringNoEnsure`).

## What Fory does

`Utf8JsonWriter.writeString` dispatches a Latin-1 string by length band so the
scan+copy work is a *fixed small number of loads and stores* for the short
strings that dominate real payloads, instead of one generic loop:

| length | strategy |
|---|---|
| `< 8` | fully unrolled per-byte (`writeLatin1String0To7`) |
| 8..15 | one `getInt64` word + 4/2/1-byte tails, each validated before storing |
| 16..24 | **three-word trick** — words at offsets 0, 8 and `length-8`, one 3-word predicate, then 3 stores |
| 25..31 | four words (0, 8, 16, `length-8`), one 4-word predicate, 4 stores |
| `>= 32` | 16-byte block loop (`isJsonAsciiWords(w0, w1)`) |

The 16..24 path is the neatest trick (Fory diff, `writeString`):

```java
int tailOffset = length - Long.BYTES;
long tail = LittleEndian.getInt64(stringBytes, tailOffset);
// one 3-word predicate over word0, word1, tail
LittleEndian.putInt64(bytes, pos, word0);
LittleEndian.putInt64(bytes, pos + 8, word1);
LittleEndian.putInt64(bytes, pos + tailOffset, tail);
pos += length;
```

The third store **overlaps** the first two (for length 17..24 it covers bytes
`length-8 .. length-1`, already written by `word0`/`word1`) but writes
byte-identical data — so any 16..24-byte string costs exactly 3 loads + 3
stores and one escape predicate, with no tail handling.

Fory also publishes the writer cursor only **after complete validation**:
when a word is dirty, `position = start;` restores the cursor and the slow
per-byte escape path runs; scratch bytes that were overwritten before
detection are simply overwritten again.

## What dia-log did before

The Latin-1 writers ran one generic loop for every length: `writeEscapedLatin1`
scanned per byte; `StringByteExtractor.writeLatin1` scanned per byte. A 20-byte
class name cost 20 per-byte classifications regardless of content.

Baseline fixtures: `hr.hrg.dialog.core.perf.ClassicEscapedStringWriter`,
`ClassicStringByteExtractor` (per-byte loops, identical in shape to the old
production code).

## What dia-log does now

`core/src/main/java/hr/hrg/dialog/core/EscapedJsonStringWriter.java`
(`writeEscapedLatin1Stream` / `writeEscapedLatin1Direct`) and
`core/src/main/java/hr/hrg/dialog/core/StringByteExtractor.java` implement the
same band dispatch:

- 8..15: one `LE_WORD` load + `isJsonAsciiTail` (int/short/byte tail
  predicates — ports of Fory's `isJsonAsciiInt`/`isJsonAsciiShort`/
  `isJsonAsciiByte`), then one bulk copy/write.
- 16..24: three-word predicate `isJsonAsciiWords3(w0, w1, w2)` where
  `w2 = word at length-8`; the direct-buffer mode then performs the
  overlapping 3-store copy (3 `putWordLE` calls) with **one** inlined capacity
  check — exactly Fory's shape.
- 25..31: four-word predicate + 4 stores.
- `>= 32`: 16-byte block loop; the `< 16` tail re-enters the short bands.
- Stream mode emits the clean band as one bulk `out.write(byte[], off, len)`;
  direct mode stores it into the buffer (T4).

## Why it is faster

- Branch count scales with the number of bands, not the string length.
- The common case — a clean short string — is a fixed small set of loads,
  one predicate, one bulk write/store.
- The overlapping-store trick eliminates all tail handling for 16..24-byte
  strings (the dominant class-name/method-name length).
- Cursor-published-after-validation keeps failed fast-path attempts free of
  side effects, so the dirty-word fallback is exact.

## Verification

- The same old-vs-new batteries in `ForyPerfComparisonTest` exercise every
  band boundary (the battery covers every length 0..48 with special bytes at
  every position, so each band's dirty and clean paths are hit).
- The existing `EscapedJsonStringWriterTest` / `StringByteExtractorTest`
  suites pin the observable escaping behavior.
- Benchmark: `ForyPerfComparisonBenchmark` (`escaping*`, `latin1*`).
