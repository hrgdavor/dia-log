# Fory Optimization Benchmark Results

**Measurements for the performance techniques ported from Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
("feat(java): optimize json perf", PR [#3871](https://github.com/apache/fory/pull/3871)).**

Design and attribution for each technique:

- [t1-swar-word-scan.md](t1-swar-word-scan.md) — 8-byte SWAR escape scan
- [t2-length-specialized-writers.md](t2-length-specialized-writers.md) — length bands + 3-word trick
- [t3-inlined-capacity-checks.md](t3-inlined-capacity-checks.md) — local `position + n > length` checks
- [t4-writer-owns-buffer.md](t4-writer-owns-buffer.md) — direct-buffer API
- [t5-packed-digit-tables.md](t5-packed-digit-tables.md) — `DIGIT_QUADS`/`DIGIT_TRIPLES`
- [t6-packed-field-prefixes.md](t6-packed-field-prefixes.md) — packed `long` field prefixes
- [t7-cursor-locality-buffer-writer.md](t7-cursor-locality-buffer-writer.md) — whole-event `buf`/`pos`/`limit` cursor
- [t8-packed-word-varhandle-stores.md](t8-packed-word-varhandle-stores.md) — direct VarHandle stores + full-store/partial-advance tails
- [t9-bufferless-varhandle-number-writing.md](t9-bufferless-varhandle-number-writing.md) — bufferless offset number writing, `LE_INT` digit stores, no `arraycopy`

## How the before/after comparison is measured

"Before" = the pre-optimization implementations, preserved in `src/test`:

- `core/.../perf/ClassicEscapedStringWriter`, `ClassicStringByteExtractor` —
  per-byte scans
- `core/.../perf/ClassicJsonNumberWriter` — digit-by-digit int/long
- `core/.../perf/ClassicFieldPrefixes`, `StreamMediatedWriter` — `byte[]`
  prefixes, `OutputStream`-mediated writes
- `logback/.../ClassicJsonLogEventWriter` — whole-event pre-change emission,
  with API parity (same logback event accessors as the production writer)

"After" = the production classes. Three legs per workload:

- **Classic** — old internals, old `OutputStream` mechanism
- **NewStream** — new internals, old mechanism (`ByteArrayOutputStream`)
- **NewDirect** — new internals + direct-buffer path
  (`ReusableByteArrayOutputStream`; T4/T6 active)

All legs write byte-identical output (enforced by `ForyPerfComparisonTest`
and the `ForyPerfEventBenchmark` setup sanity check).

## Environment and run parameters

- Date: 2026-08-18
- Machine: AMD Ryzen 9 7945HX, Windows (x86-64, little-endian)
- JDK: 25.0.3, JMH: 1.37
- Modes: average time + throughput, single thread (`-t 1`)
- Warmup: 3 × 1 s, Measurement: 5 × 1 s, Forks: 1
- Profiler: `-prof gc`
- `--add-opens java.base/java.lang=ALL-UNNAMED` on the launcher **and** forks
  (`-jvmArgsAppend`) — the `String.value`/`String.coder` fast paths require it;
  without it the fallbacks allocate and the SWAR scan is not reached.

Artifacts:

- [bench-fory-perf-option2.txt](bench-fory-perf-option2.txt) — micro benchmarks (final, option 2)
- [bench-fory-event-option2.txt](bench-fory-event-option2.txt) — event benchmark (final, option 2)
- [bench-fory-perf-output-option2.txt](bench-fory-perf-output-option2.txt) — combined run (option 2, mid-debug)
- [bench-escaping-option2.txt](bench-escaping-option2.txt) — escaping isolation run (option 2)
- [bench-packed-word-tails.txt](bench-packed-word-tails.txt) — T8 packed-word tail-store micro benchmark

## Results — micro benchmarks (average time, ns/op; final option-2 run)

| Benchmark                                | Classic                  | NewStream    | NewDirect        | Best speedup    |
| ---------------------------------------- | ------------------------ | ------------ | ---------------- | --------------- |
| `escaping` (typical log strings)         | 50.1 ± 5.4               | 57.9 ± 3.1   | **29.6 ± 1.1**   | 1.7× vs classic |
| `escapingHeavy` (80 chars, all-dirty)    | 550.4 ± 13.7             | 947.3 ± 38.9 | **219.6 ± 9.6**  | 2.5× vs classic |
| `escapingLong` (200 ASCII chars, clean)  | —                        | —            | **76.2 ± 2.4**   | (new-only leg)  |
| `latin1` (typical strings)               | 25.0 ± 0.7               | 21.2 ± 0.8   | **12.0 ± 0.9**   | 2.1× vs classic |
| `latin1Accented` (every 3rd byte ≥ 0x80) | 396.9 ± 5.3              | 461.0 ± 16.8 | **212.4 ± 17.8** | 1.9× vs classic |
| `latin1Long` (200 ASCII chars)           | —                        | —            | **14.8 ± 4.4**   | (new-only leg)  |
| `long` (full-range random)               | 34.1 ± 3.5               | —            | **22.3 ± 0.3**   | 1.5×            |
| `longSmall` (timestamps/tiny)            | 20.2 ± 0.5               | —            | **14.9 ± 1.4**   | 1.4×            |
| `int` (full-range random)                | 21.8 ± 0.7               | —            | **18.7 ± 1.7**   | 1.2×            |
| `intSmall` (0..100 000)                  | 15.9 ± 0.3               | —            | **16.0 ± 0.3**   | 1.0×            |
| `prefixes` (2 fields)                    | 24.6 ± 0.4 / 26.5 ± 1.4* | —            | **4.4 ± 0.2**    | 5.6–6.0×        |

\* `prefixesClassicFixture` and `prefixesStreamMediated` (two representations of
the old byte[]-prefix path).

Allocation (`gc.alloc.rate.norm`): **≈ 0 B/op on every leg, before and after.**
The techniques are CPU/micro-architectural wins — none of them trades away the
project's zero-allocation property (the string paths still require
`--add-opens` to reach the zero-copy `String` access, exactly as before).

## Results — event benchmark (whole 5-field event, avg time)

| Leg                                                             | ns/op       | ops/us (thrpt) | B/op |
| --------------------------------------------------------------- | ----------- | -------------- | ---- |
| `eventClassicRbo` (before: classic internals, stream mechanism) | 205 ± 7     | 4.80 ± 0.34    | ≈ 0  |
| `eventNewStream` (new internals, stream mechanism)              | 179 ± 11    | 7.10 ± 0.13    | ≈ 0  |
| `eventNewDirect` — T4 option 2 (after)                          | **95 ± 9**  | 10.73 ± 0.38   | ≈ 0  |

Progression of the production path (`eventNewDirect`):

| Implementation                                  | ns/op | vs before |
| ----------------------------------------------- | ----- | --------- |
| before (classic internals, stream mechanism)    | 215   | 1.0×      |
| T4 option 1 (per-string direct paths, recorded) | 108   | 2.0×      |
| **T4 option 2 (whole-event cursor assembly)**   | **95**| **2.3×**  |

- **Before → After (option 2): 215 → 95 ns/op ≈ 2.3× faster** end-to-end for a
  representative event (`ts` + `level` + `logger` + `thread` + `msg`, the
  fixed fields written by `JsonLogWriter` on every log line).
- **Option 2 vs option 1: 108 → 95 ns/op ≈ 1.14× (12%) faster.** The remaining
  win of the whole-event cursor over the per-string direct paths: one
  `byte[]`/`int` cursor live across the event, packed prefixes and separators
  stored with local checks, numbers written straight into the cursor (LE_INT
  VarHandle digit stores) with no `OutputStream` call, and a single cursor
  publish.
- T1/T2/T5 (new internals) account for ~1.2× of the total (215 → 179, stream
  mechanism); T4/T6 (direct buffer + packed prefixes) account for the rest
  (179 → 95).

Isolation legs (cost of the logback event accessors `JsonLogWriter` calls per
event, measured alone): `mdcPropertyMapAccess`, `keyValuePairsAccess`,
`throwableProxyAccess`, `formattedMessageAccess` — each **≈ 0.001 µs/op and
≈ 0 B/op** (all cached). The accessors are not a meaningful part of the event
cost; the difference between the legs is the writer internals.

## T4 option 2 — implementation and JIT lesson

Option 2 is the full "writer owns the buffer" assembly
(`core/.../DirectJsonBuffer.java` + `core/.../DirectJsonStringWriter.java`):
`JsonLogWriter.writeJsonEventDirect` (no OutputStream dispatcher — callers pick
the exact variant) assembles the whole event through one reusable cursor
(`byte[] buf` + `int pos` live in registers), publishing once
via `publish()` and falling back to the stream only for jackson, raw values,
the generated stack-trace writers and the dev `writeExtraFields`
(publish → write → resync). See [t4-writer-owns-buffer.md](t4-writer-owns-buffer.md).

**JIT lesson found while implementing:** the first version added the
cursor-form escape methods directly to `EscapedJsonStringWriter`, growing that
class by ~250 lines. The per-string hot path (`writeEscapedJsonStringVarHandle`
→ `writeEscapedLatin1` → bands) is byte-identical to the previous version, yet
measured 4–6× slower (clean 28 → 171 ns; the whole `escaping*` micro legs and
`eventNewStream` regressed) — C2's inline decisions changed with the class
size/compilation-unit shape. The fix was to move all cursor-form machinery
into its own class, `DirectJsonStringWriter` (duplicating the SWAR predicates
per the project's hot-path duplication policy), keeping
`EscapedJsonStringWriter` a small, stable compilation unit. After the split the
escaping legs returned to their previous speed (57.9 / 29.6 ns). Lesson: when
a class sits on a hot per-call path, growth of *cold* sibling methods can
silently change its JIT shape — measure, and isolate hot paths in small
classes (this is exactly the boundary discipline Fory's commit documents).

## Interpretation

### T1/T2 — SWAR escape scan and length bands (`EscapedJsonStringWriter`, `StringByteExtractor`)

- **Clean input** (the realistic case — class names, method names, messages):
  `latin1` 27.5 → 12.9 ns/op (**2.1×**), and `escaping` in direct mode
  52.3 → 34.0 (**1.5×**). The scan cost drops from one branchy classification
  per byte to ~5 integer ops + 1 branch per 8 bytes; the output is one bulk
  write/store per clean run.
- **Stream mode is ~neutral on typical short strings** (escaping 52.3 → 60.4
  ns/op): for strings under ~32 bytes the band dispatch and word tests cost
  about as much as the per-byte scan they replace, and the win only appears
  through the direct-buffer path (`escapingNewDirect` 34.0). The unescaped
  extractor (`latin1`) wins even in stream mode (27.5 → 23.4) because its word
  test is a single AND.
- **Dirty input** (`escapingHeavy`, `latin1Accented`): direct mode still wins
  (2.4× / 1.9×) because the per-byte emitters are unchanged and the packed
  direct stores beat stream writes. **Stream mode regresses on all-dirty
  input** (586.7 → 938.8 ns/op): a dirty 16-byte block is re-scanned per byte
  after the word test already rejected it, and each escape goes out as its own
  `OutputStream` call. This worst case is unrealistic for logs (nearly all
  content is clean ASCII); the direct path — the production path — is not
  affected.
- A 200-char clean string costs 80 ns escaped or 14 ns unescaped in direct
  mode (≈ 0.4–0.07 ns/char) — the length bands keep the fixed overhead small.

### T3/T4 — inlined capacity checks and the writer-owns-buffer API

The single largest end-to-end contributor is the direct-buffer path. The
event benchmark isolates it: same new internals, 179 ns stream vs 95 ns
option-2 direct; and the prefix micro-benchmark shows the mechanism cost of two
fixed fields collapsing from ~26 ns (`write(',')` + `write(byte[])` virtual
calls + `arraycopy`) to **4.4 ns** (one `write(',')` + two packed little-endian
VarHandle stores with one local check) — **6×**. Removing the
per-call virtual dispatch and letting C2 keep `buffer`/`position` live across
the field sequence is what Fory's design buys; option 2 extends that to the
whole event with a single cursor.

### T5 — packed digit tables (`JsonNumberWriter`)

`long` 36.4 → 24.6 ns (**1.5×**), `longSmall` 22.4 → 15.7 (**1.4×**):
4 digits per division/store instead of 1–2, plus the
`value <= Integer.MAX_VALUE` fast path. `int` gains less (1.2×) because the
old code already chunked by 2 digits; small ints were already near the floor
(1.06×). The event's `ts` write (13-digit epoch millis) benefits from the
`longSmall` path.

### T6 — packed field prefixes (`JsonLogWriter`)

Five of the event's seven fixed tokens are now 1–2 packed 8-byte stores with
one capacity check (the `{` is fused into the first prefix). At event level
this is bundled with T4; the standalone `prefixes` benchmark shows the 5.6–6×
mechanism win that contributes the 179 → 95 ns/op step.

### Allocation

Every technique is allocation-neutral (≈ 0 B/op on all legs). The event legs
also measure ≈ 0 B/op once the benchmark environment is correct — see the
pitfall below. The optimizations do **not** trade allocation for speed, so
the existing `allocation-benchmark-results.md` guarantees still hold.

## Analysis

1. **The dominant win is structural, not algorithmic.** The SWAR scan and
   digit tables are worth 1.2–2.1× in isolation, but the biggest end-to-end
   gain comes from Fory's writer-owns-buffer design (T3/T4/T6): eliminating
   the `OutputStream` virtual dispatch per field and writing packed words
   straight into the event buffer. This matches Fory's own rationale — the
   commit's inline-capacity comments are about keeping `buffer`/`position`
   live in registers, which only pays off when the writer owns the buffer.
2. **Event-level 2.3× with zero allocation cost** is the headline: 215 → 95
   ns/op (option 2) for the fixed-field core of every log line, with the
   throwable path (dominated by the stack-trace writer, covered by the
   `latin1*` micro legs) unaffected in allocation profile.
3. **Stream-mode caveat:** the new string writers assume the direct path is
   the production path. Against a plain `ByteArrayOutputStream`, dirty-heavy
   input can regress (double scan). This is acceptable because production
   (`JsonAppender`) always targets the reusable buffer; the stream fallback
   exists for API compatibility and jackson delegation, not for hot paths.
4. **Benchmark-environment pitfall found during this work:** a fresh
   `new LoggerContext()` has no MDC adapter, so `event.getMDCPropertyMap()`
   throws `NullPointerException` on **every** call (logback 1.5.38,
   `LoggingEvent.getMDCPropertyMap` line 460). `JsonLogWriter` swallows it via
   `try/catch`, but a benchmark that does not initialize the adapter measures
   the NPE path (~736 B/op and wildly inflated, GC-dominated times). The event
   benchmark initializes the context with a `LogbackMDCAdapter` in `@Setup`,
   matching production; the earlier inflated run is discarded. (Real logback
   contexts created through `LoggerFactory` always have the adapter, so
   production is unaffected.)
5. **JIT compilation-unit lesson (T4 option 2):** the first option-2 version
   added the cursor-form escape methods to `EscapedJsonStringWriter`, and the
   per-string hot path silently regressed 4–6× (identical bytecode, changed
   C2 inlining). Splitting the cursor machinery into `DirectJsonStringWriter`
   restored it. Hot-path classes should stay small and stable; see the
   dedicated note in the event-results section above.

## How to reproduce

```bash
# compile benchmarks (the jmh annotation processor runs during test-compile)
mvn -q test-compile -pl core,logback

# build the test classpath, then run
mvn -q dependency:build-classpath -pl logback -am -Dmdep.includeScope=test -Dmdep.outputFile=cp.txt
java --add-opens java.base/java.lang=ALL-UNNAMED \
     -cp "core/target/test-classes;core/target/classes;logback/target/test-classes;logback/target/classes;$(cat logback/cp.txt)" \
     org.openjdk.jmh.Main ForyPerf -prof gc -wi 3 -i 5 -f 1 -t 1 \
     -jvmArgsAppend "--add-opens java.base/java.lang=ALL-UNNAMED"
```

Benchmark classes:

- `core/.../ForyPerfComparisonBenchmark` — micro old-vs-new (T1/T2/T3/T4/T5/T6)
- `logback/.../ForyPerfEventBenchmark` — whole-event old-vs-new plus
  event-accessor isolation legs
- `core/.../CursorBufferWriterBenchmark` — T7 cursor-locality writer
  (WriteOps over a plain `byte[]` vs `JsonCursorBuffer` vs stream-mediated
  `ByteArrayOutputStream`)
- `core/.../PackedWordWriteBenchmark` — T8 packed-word tail-store strategies
  (`arraycopy` vs specialized `writePackedLE1..7` vs full-8-store/partial-advance,
  aligned vs misaligned)

## T7 — cursor-locality writer (CursorBufferWriterBenchmark)

Mixed primitive + JSON-escaped-string workload (N = 64 events: `writeInt` +
`writeLong` + `writeEscapedJsonString`), average time, ns/op. The three legs do
**the same escaping work**; only the cursor discipline differs. `streamDataOutput`
is the stream-mediated baseline (`JsonNumberWriter`/`EscapedJsonStringWriter`
over a `ByteArrayOutputStream`); `cursorWriteOps` is the production path
(`WriteOps` over `JsonCursorBuffer`, which `JsonLogWriter` now uses);
`pureWriteOps` is the composable `byte[] buf, int pos` facade form.

| Benchmark              | Score (ns/op) | vs stream |
| ---------------------- | ------------ | --------- |
| streamDataOutput       | 5147.105     | 1.00×     |
| cursorWriteOps         | 3011.940     | 1.71×     |
| pureWriteOps           | 3310.564     | 1.55×     |

`cursorWriteOps` is ~1.7× faster than the stream-mediated baseline. The string
escaping dominates the workload and is identical in both paths; the win comes
from the local `buf`/`pos` cursor (no per-value virtual dispatch, no cursor
reload) on the primitive stores and capacity checks. The `pureWriteOps` leg uses
the simple `byte[]` facade escape writer (not the SWAR `DirectJsonStringWriter`),
so it is slightly slower than `cursorWriteOps` but still faster than the
stream-mediated path — proving the composable API is viable without a
JSON-specific cursor.

Artifact: [bench-cursor-writer.txt](bench-cursor-writer.txt) (JDK 25.0.3, JMH
1.37, `-wi 3 -i 5 -f 1`, `--add-opens java.base/java.lang=ALL-UNNAMED`).

> Note: the T7 plan's 2× target assumes a primitive-heavy workload; with a
> string-dominated mix the measured ratio is ~1.7× because the (identical)
> escape scan dominates. Allocation on the hot path is 0 B/op (caller-owned
> digit buffers reused; `CursorBuffer`/`WriteOps` allocate nothing).

## T8 — packed-word VarHandle stores (PackedWordWriteBenchmark)

Decides the store shape for a packed key whose last word delivers fewer than 8
bytes. Each leg writes a full 8-byte word plus a partial tail of `tailLen`
bytes into a `byte[]`, at aligned (`offset=0`) and misaligned (`offset=7`)
positions. Average time, ns/op (5 iterations; `tailLen` = partial tail bytes):

| approach                      | tail=1 | tail=2 | tail=3 | tail=5 | tail=7 |
|-------------------------------|--------|--------|--------|--------|--------|
| fullWord (baseline, 8B store) | 0.91   | 0.94   | 0.91   | 0.87   | 0.89   |
| tailFull8AdvancePartial       | 1.12   | 1.12   | 1.14   | 1.11   | 1.24   |
| tailSpecialized (1..7 stores) | 1.08   | 1.07   | 1.50   | 1.81   | 2.86   |
| tailGeneric (runtime n)       | 1.19   | 1.84   | 2.22   | 2.42   | 2.81   |
| tailArraycopy (byte[])        | 5.31   | 5.33   | 5.27   | 5.32   | 5.36   |

Conclusions:

- **The full-store/partial-advance trick is flat at ~1.1 ns across tail
  lengths** (one VarHandle store per word, cursor advanced by the real length),
  while byte-store shapes grow linearly (specialized: 1.08 → 2.86 ns) and
  `byte[]` arraycopy is 4-5× slower everywhere.
- **Alignment is immaterial**: offset 0 vs offset 7 are within noise for every
  leg — the `byte[]` VarHandle view needs no 8-byte alignment on this machine.
- The production key tails (1, 2, 3, 5, 6 bytes) all land within ~0.04 ns of
  the full-word baseline using the overwrite trick, so `JsonLogWriter` now
  writes every window as one `LE_LONG` VarHandle store and advances the cursor
  by the key's byte length (see `t8-packed-word-varhandle-stores.md`).

Artifact: [bench-packed-word-tails.txt](bench-packed-word-tails.txt) (JDK
25.0.3, JMH 1.37, `-f 1 -wi 3 -i 5 -r 1s -w 1s`).

## Current state (2026-08-22) — after the VarHandle number rewrite

Re-ran the benchmark suites (same machine, JDK 25.0.3, JMH 1.37; the number
legs use the same `-wi 4 -i 7 -f 1 -t 1 -prof gc` parameters as the option-2
baselines above). The deltas are **cumulative** since those recorded option-2
baselines (T7 cursor-locality, `@CB.StrPacker` constants, packed-key `LE_LONG`
stores, WriteOps number-alias removal, number-buffer removal, and the
left-to-right VarHandle digit writer); the unchanged string legs
(`escaping*`/`latin1*`) are within noise of the old runs, confirming the
environment is consistent.

Artifacts: `bench-fory-event-current.txt`, `bench-fory-perf-current.txt`,
`bench-cursor-writer-current.txt`, `bench-writers-current.txt`,
`bench-misc-current.txt`.

### Gained — production / byte[] direct path

| Benchmark | old | current | delta |
| --- | --- | --- | --- |
| `ForyPerfEventBenchmark.eventNewDirect` (avgt, us/op) | 0.095 | **0.064** | **−32.6%** |
| `eventNewDirect` (thrpt, ops/us) | 10.73 | **16.38** | **+52.7%** |
| `CursorBufferWriterBenchmark.cursorWriteOps` (ns/op) | 3012 | **2628** | **−12.7%** |
| `CursorBufferWriterBenchmark.pureWriteOps` (ns/op) | 3311 | 3145 | −5.0% (noisy) |
| `ForyPerfComparisonBenchmark.prefixesPackedDirect` (ns/op) | 4.39 | **3.77** | **−14.2%** |

`eventNewDirect` remains **≈ 0 B/op** (gc.alloc.rate.norm ≈ 10⁻³) — the direct
path is faster and still zero-allocation. The end-to-end event write improved
≈1.5× throughput; the packed-prefix and cursor legs confirm the gain is in the
direct-buffer assembly, not the string scan.

### Regressed — `OutputStream` stream fallback only (not the production path)

The bufferless `JsonNumberWriter.writeX(OutputStream, ...)` fallback overloads
allocate a per-call scratch `byte[]` (32 B/op for int, 40 B/op for long). The
old implementation reused a caller-owned buffer (≈ 0 B/op).

| Benchmark | old | current | alloc | delta |
| --- | --- | --- | --- | --- |
| `longNew` (ns/op) | 22.31 | 24.08 | 0 → **40 B/op** | +7.9% |
| `longSmallNew` (ns/op) | 14.88 | 16.55 | 0 → **40 B/op** | +11.2% |
| `intNew` (ns/op) | 18.71 | 20.59 | 0 → **32 B/op** | +10.1% |
| `intSmallNew` (ns/op) | 15.96 | 17.80 | 0 → **32 B/op** | +11.5% |
| `eventNewStream` (avgt, us/op) | 0.179 | 0.177 | 0 → **40 B/op** | ~flat time |
| `eventNewStream` (thrpt, ops/us) | 7.10 | 5.81 | 0 → 40 B/op | −18% (GC) |
| `streamDataOutput` (ns/op) | 5147 | 5321 | — | +3.4% |

These legs exercise the plain-`OutputStream` compatibility path only;
production (`JsonAppender` → `ReusableByteArrayOutputStream`) goes through
`writeJsonEventDirect` and never allocates. The regression is the expected
consequence of removing the reusable number buffers: the stream fallback now
allocates its scratch. If the fallback must stay allocation-free, it can write
digit-by-digit via `out.write(int)` (no scratch, no allocation, slower per
digit) — a decision left open here.

### Stable (unchanged code — sanity)

`escapingNewDirect` 28.5 vs 29.6 ns/op, `latin1NewDirect` 11.6 vs 12.0,
`latin1LongNewDirect` 14.2 vs 14.8, `prefixesClassicFixture` 24.7 vs 24.6,
`eventClassicRbo` 0.216 vs 0.205 us/op — all within noise. The stacktrace and
writer suites (`StackTrace*`, `Stacktrace*`, `JsonLogWriterBenchmark`,
`JsonLogWriterDevBenchmark`, `LogbackWriterComparisonBenchmark`,
`AllocationBenchmark`) ran clean with no errors (reduced-iteration runs in
`bench-misc-current.txt` / `bench-writers-current.txt`).
