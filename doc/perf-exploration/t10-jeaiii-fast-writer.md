# T10 — Jeaiii division-free `int`/`long` writer (pairs → quads → trailing-zero)

**Source technique:** [jeaiii/itoa](https://github.com/jeaiii/itoa) — integer
to ASCII via **reciprocal multiplication** instead of hardware division: the
value is sliced most-significant-first into digit groups, and each group is
computed with a fixed-point multiply by a precomputed magic constant
(`ceil(2^N / d)` shifted right by `N`) rather than `idiv`.

## What jeaiii does

The original writes two-digit pairs from a 100-entry table with a branch ladder
per digit count, computing each quotient with a `64×32 → 64` reciprocal
multiply. The idea that matters here is **division-free group extraction**; the
C macro form of the magic constants (`+ 1 + N/6 - N/8` correction terms) is
deliberately *not* ported — it is tuned for 32/33-bit shifts and is easy to get
wrong (see the bug history below).

dia-log uses the cleaner 64-bit form: for divisor `d = 10^k` (`1 <= k <= 8`),
`M = ceil(2^64 / d)` fits a positive `long`, and
`Math.multiplyHigh(v, M) == v / d` **exactly** for every `0 <= v < 2^64 / d`
(all operands non-negative, so signed `multiplyHigh` is the unsigned high
product with no correction terms). `Math.multiplyHigh` compiles to a single
`mulx`/high-word move on x86-64 — no `idiv`, no 128-bit emulation.

## What dia-log did before

- **`JsonNumberWriter` (T5, Fory-style)** — the previous fast writer:
  `DIGIT_QUADS[10000]` + `DIGIT_TRIPLES[1000]`, one hardware division per
  4-digit group (the JIT strength-reduces `/ 10000` to a multiply-shift) plus
  `writeTriple`'s leading-zero-skip logic. Measured 2.2–8.9 ns for int and
  3.1–13.9 ns for long (see benchmark results below).
- **First `JeaiiiFastWriter` attempt — buggy.** The original reciprocal
  implementation confused the leading *pair* with the leading *digit* and used
  divisors like `4294968` (≈ 2³²/1000) where `2³²/100` or `2³²/10` was needed.
  The existing boundary/exhaustive tests caught it immediately: 41 failures
  (e.g. `100 → "001"`, `5123456 → "5123495"`) and 24
  `ArrayIndexOutOfBoundsException`s (`write2` fed values ≥ 100).
- **Corrected pairs version** — the fixed 2-digit-pair writer: 200-byte
  `TWO_DIGITS_LE` table (always L1-resident), one `short` store per pair,
  division-free. Preserved as a benchmark fixture:
  `core/src/test/java/hr/hrg/dialog/core/perf/JeaiiiPairsWriter.java`
  (with `JeaiiiPairsWriterTest`).

## What dia-log does now

`core/src/main/java/hr/hrg/dialog/core/JeaiiiFastWriter.java` — the quad
writer, evolved through four measured stages:

| Stage | Shape | Tables | Leading 1..3 digits |
| --- | --- | --- | --- |
| 1. pairs | 1 `short` store per 2 digits | 200 B | `write2` pair |
| 2. quad | 1 `int` store per 4 digits | 40 KB + 4 KB | `writeTriple` (skip byte + switch) |
| 3. + tiered fast path | `<10` byte, `<100` short, `<1000` triple, `<10000` quad | same | same |
| 4. **+ trailing-zero (current)** | every group is one store | 40 KB + 4 KB + 200 B | `TRAILING_TRIPLES` right-aligned, 4-byte store |

Current shape (`writeQuadPositive`, lines 190–234):

- **`< 10`** — one plain byte store.
- **`< 100`** — one `short` store from `TWO_DIGITS_LE` (200 B).
- **`< 1000`** — one `LE_INT` store from `TRAILING_TRIPLES` (3 significant
  digits right-aligned + one trailing `'0'`), advance 3.
- **`< 10000`** — one `LE_INT` store from `DIGIT_QUADS`.
- **5..10 digits** — leading group (1..4 digits) via `DIGIT_QUADS` (4 digits)
  or `TRAILING_TRIPLES` (1..3 digits, trailing bytes overwritten by the next
  quad), then 4-digit quads. Digit count from the `decimalDigits` ladder;
  each group quotient from `Math.multiplyHigh(v, MAGIC[k])`.

`long` (up to 19 digits, `writeQuadPositiveLong`): split at `10^9` with
`Math.multiplyHigh(u, SPLIT_MAGIC)` + a one-overshoot signed-remainder repair
(division-free split), then `writeQuadPositive(hi)` + `writeQuadPadded9(lo)`
(1 leading digit + 2 quads, zero-padded). `Long.MIN_VALUE` copies a
precomputed literal.

**Buffer contract:** a 1..3 digit result is written as a full 4-byte word, so
the caller must leave ≥ 4 bytes of room at `offset`; the trailing `'0'` bytes
land past the returned position and are overwritten by the next store or never
observed. Documented on `writeIntToBytes`/`writeLongToBytes`.

## Why it is faster

1. **No hardware division at all.** `multiplyHigh` (1 instruction) replaces
   every `idiv`/strength-reduced division. `JsonNumberWriter` pays ~1 division
   per 4-digit group; this writer pays 0.
2. **Fewer, wider stores.** 4 digits per `LE_INT` store; a 10-digit int is 3
   stores instead of 5 short stores (pairs) or 1 division + 3 stores
   (JsonNumberWriter).
3. **Tiered fast path never computes a digit count below 10000** — and the
   digit-count branches are cheap anyway (1–4 well-predicted comparisons;
   measured `tiny` = 0.89 ns ≈ a bare comparison + store).
4. **Trailing-zero leading group removes the last variable-width write.**
   `writeTriple`'s skip-extract + shift + `switch` + 1..3 byte stores becomes
   one 4-byte store; safe because the caller's buffer always has slack
   (`write more than pos is moved` — the overwrite trick, cf. T8).
5. **Cache.** The 200 B pair table is L1-resident; the 44 KB digit tables fit
   L2. Isolated microbenchmark (`DigitGroupStoreBenchmark`) swept the whole
   40 KB table vs a 1 KB slice: ~6% difference (within noise) — the L1→L2
   misses for ~5 loads per value are hidden by out-of-order execution. The
   larger table is a non-issue on this hardware.

## Digit-count discussion (resolved by measurement)

A digit writer must know its digit count to advance the position. The ladder
(`decimalDigits`) costs 1–9 predictable comparisons. Two alternatives were
considered and rejected on evidence:

- **Branchless `Long.numberOfLeadingZeros` + table** — same cost for small
  values, unnecessary complexity; the measured `tiny`/`small` paths already
  match the pairs writer's minimal path.
- **Unified 80 KB trailing-zero table with embedded length** — one branch for
  `< 10000`, but a bigger table and a 4-byte store for 1-digit values would
  regress the tiny path the user explicitly cares about (line numbers).

The tiered fast path (`< 10/100/1000/10000`) is the cheapest way to encode the
digit count for short values, and `DIGIT_QUADS` *cannot* skip leading zeros
(fixed 4-byte packing), which is exactly why `TRAILING_TRIPLES` (right-aligned,
trailing pad) exists for the 1..3 digit leading group.

## Verification

- **Exhaustive** (standalone, against a division-based reference): all `2^32`
  int values; `[0, 2×10^9)` + its negative mirror, 50 M random longs,
  every `10^k` boundary (0..18), `Long.MIN/MAX_VALUE` — `ALL OK` on every
  stage.
- **Unit tests**: `JeaiiiFastWriterTest` (85), `JeaiiiFastWriterLongTest`
  (115), `JeaiiiPairsWriterTest` (102) — boundaries, exhaustive small ranges,
  split windows, seeded random sweeps. Full `core` suite: 624 tests, 0
  failures.
- **Zero allocation**: all custom writers write into the caller's `byte[]`;
  no allocation on the hot path.

## Benchmark results

`IntWriteBenchmark` / `LongWriteBenchmark` (4 legs: `jeaiiiPairs`,
`jeaiiiQuad`, `jsonNumberWriter`, `standardToString`), JDK 25.0.3, JMH 1.37,
`-wi 3 -i 6 -f 2` (12 samples), AMD Ryzen 9 7945HX. Full table in
[bench-jeaiii-writer.txt](bench-jeaiii-writer.txt); average time ns/op:

| int distribution | jeaiiiPairs | jeaiiiQuad | jsonNumberWriter | standardToString |
| --- | --- | --- | --- | --- |
| tiny (0–9) | 0.883 | 0.888 | 2.223 | 10.904 |
| small (0–99) | 1.380 | 1.378 | 2.854 | 11.671 |
| medium (0–10⁶) | 4.662 | **2.745** | 6.212 | 17.115 |
| full | 7.167 | **5.082** | 8.510 | 18.607 |
| negative | 7.346 | **4.846** | 8.072 | 18.951 |

| long distribution | jeaiiiPairs | jeaiiiQuad | jsonNumberWriter | standardToString |
| --- | --- | --- | --- | --- |
| tiny (0–99) | 1.548 | 1.535 | 3.144 | 11.750 |
| medium (0–10⁹) | 6.723 | **5.210** | 8.150 | 18.626 |
| timestamp (0–10¹²) | 7.839 | **4.396** | 7.889 | 19.524 |
| full (19-digit) | 13.085 | **8.226** | 13.433 | 28.592 |
| negative | 13.045 | **7.771** | 12.719 | 30.408 |

`jeaiiiQuad` is fastest on **every** distribution for both int and long:
~1.2–2.5× vs `JsonNumberWriter`, and strictly dominant over the pairs variant
(which it matches on tiny/small and beats by 1.3–1.8× on medium+).

Stage-by-stage gain on `jeaiiiQuad` (avg ns/op, cross-run, so approximate):

| stage | int medium | int full | long timestamp | long full |
| --- | --- | --- | --- | --- |
| pairs (stage 1) | 4.66 | 7.17 | 7.84 | 13.08 |
| quad + fast path (stage 3) | 3.67 | 5.33 | 5.24 | 9.30 |
| **quad + trailing-zero (stage 4)** | **2.75** | **5.08** | **4.40** | **8.23** |

The trailing-zero stage (4) was worth ~25% on int `medium` and ~16% on long
`timestamp`; tiny/small are unchanged because they never use the leading-group
path.

## Benchmark fixtures and artifacts

- `core/.../perf/JeaiiiPairsWriter` (+ test) — stage-1 pairs implementation,
  the preserved "old implementation".
- `IntWriteBenchmark`, `LongWriteBenchmark` — 4-way comparison.
- `DigitGroupStoreBenchmark` — isolated store-width vs table-size tradeoff.
- `bench-jeaiii-writer.txt` — final run artifact.
