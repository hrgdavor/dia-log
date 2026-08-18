# T5 — Packed Digit Tables (`DIGIT_QUADS` / `DIGIT_TRIPLES`)

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871)).
Fory files: `java/fory-json/.../writer/Utf8JsonWriter.java` (`DIGIT_TRIPLES`,
`DIGIT_QUADS`, `writePositiveLong`, `writePositiveInt`, `divide10000`,
`writePadded4` / `writePadded8`).

## What Fory does

Fory precomputes two lookup tables at class init (lines 97–118 at that SHA):

```java
// DIGIT_TRIPLES[i] = skip | (c0 << 8) | (c1 << 16) | (c2 << 24)
//   skip = leading-zero count (0, 1 or 2); c0,c1,c2 = ASCII digits of i
// DIGIT_QUADS[i]   = c0 | (c1 << 8) | (c2 << 16) | (c3 << 24)
//   4 ASCII digits packed little-endian into one int
```

Number formatting then becomes chunked:

- 1..3 significant digits: one lookup + one shifted 4-byte store
  (`writeIntUpTo3`): `int digits = DIGIT_TRIPLES[v]; int skip = digits & 0xFF;
  putInt32(bytes, pos, digits >>> ((skip + 1) << 3)); pos += 3 - skip;`
- exactly 4 digits: one lookup + one 4-byte store (`writePadded4`).
- 8 digits: two quads packed into one long + one 8-byte store
  (`writePadded8`).
- `writePositiveLong` splits by `EIGHT_DIGITS` (100_000_000), then 4-digit
  chunks via `divide10000` (a multiply-shift, `(int) (((long) v * 1759218605L)
  >> 44)`, no `idiv`), and has a cheap `value <= Integer.MAX_VALUE` fast path
  that delegates to the int formatter.

## Why it is faster

Per-digit `idiv` is tens of cycles and serializing. The packed approach emits
4 digits per divide-by-10000 (one multiply-shift) and per 4-byte store: a full
`long` costs ~3 divisions + ~3 stores instead of up to 19 divisions + 19
stores. dia-log's old `writeLong` divided by 10 per digit.

## What dia-log did before

`JsonNumberWriter.writeLong` wrote one digit per byte, dividing by 10 each
step into a reusable 20-byte buffer; `writeInt` divided by 100 per step using
the two-digit `DIGIT_PAIRS` table.

Baseline fixture: `hr.hrg.dialog.core.perf.ClassicJsonNumberWriter` (the old
digit-by-digit implementations).

## What dia-log does now

`core/src/main/java/hr/hrg/dialog/core/JsonNumberWriter.java`:

- Adds the `DIGIT_TRIPLES[1000]` / `DIGIT_QUADS[10000]` tables (same packing
  as Fory, static init).
- `writeInt`: 4-digit chunks from the end of the 11-byte buffer, then a final
  1..4-digit chunk via `writeFinalChunk` (triples with skip / quad).
- `writeLong`: `value <= Integer.MAX_VALUE` fast path delegates to the int
  formatter (Fory's `writePositiveLong` range test); otherwise 4-digit chunks
  of the 20-byte buffer, then the final chunk.
- `putIntLE` performs the four little-endian byte stores. Byte order is the
  *output* byte order and is portable (unlike `String.value` access, which
  AGENTS.md forbids assuming).

Division by 10000 is written as a plain `/ 10000` — the JIT strength-reduces
division by a constant to a multiply-shift, so the explicit `divide10000`
magic constant is unnecessary here; `writeInt`/`writeLong` keep the
`DIGIT_PAIRS` table only for compatibility (the classic fixture uses its own).

## Verification

- `ForyPerfComparisonTest.ints_classicVsNew` and `longs_classicVsNew` compare
  old vs new against `String.valueOf` over edge values (powers of ten, digit
  boundaries, `Integer/Long.MAX_VALUE/MIN_VALUE`) plus 300 seeded random
  values.
- Existing `JsonNumberWriterTest` (int/long serialization) pins the public
  contract.
- Benchmark: `ForyPerfComparisonBenchmark` (`intClassic`/`intNew`,
  `longClassic`/`longNew`).
