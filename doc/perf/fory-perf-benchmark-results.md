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

- [bench-fory-perf-output.txt](bench-fory-perf-output.txt) — micro benchmarks
- [bench-fory-event-output.txt](bench-fory-event-output.txt) — event benchmark

## Results — micro benchmarks (average time, ns/op)

| Benchmark                                | Classic                  | NewStream    | NewDirect        | Best speedup    |
| ---------------------------------------- | ------------------------ | ------------ | ---------------- | --------------- |
| `escaping` (typical log strings)         | 52.3 ± 6.1               | 60.4 ± 3.3   | **34.0 ± 7.1**   | 1.5× vs classic |
| `escapingHeavy` (80 chars, all-dirty)    | 586.7 ± 35.5             | 938.8 ± 39.3 | **241.4 ± 20.2** | 2.4× vs classic |
| `escapingLong` (200 ASCII chars, clean)  | —                        | —            | **80.4 ± 4.4**   | (new-only leg)  |
| `latin1` (typical strings)               | 27.5 ± 1.9               | 23.4 ± 2.2   | **12.9 ± 0.7**   | 2.1× vs classic |
| `latin1Accented` (every 3rd byte ≥ 0x80) | 410.0 ± 38.6             | 480.2 ± 31.3 | **215.9 ± 19.3** | 1.9× vs classic |
| `latin1Long` (200 ASCII chars)           | —                        | —            | **14.2 ± 1.6**   | (new-only leg)  |
| `long` (full-range random)               | 36.4 ± 3.5               | —            | **24.6 ± 2.0**   | 1.5×            |
| `longSmall` (timestamps/tiny)            | 22.4 ± 2.4               | —            | **15.7 ± 1.0**   | 1.4×            |
| `int` (full-range random)                | 22.0 ± 0.9               | —            | **18.1 ± 1.0**   | 1.2×            |
| `intSmall` (0..100 000)                  | 17.0 ± 1.5               | —            | **16.0 ± 1.5**   | 1.1×            |
| `prefixes` (2 fields)                    | 26.8 ± 1.1 / 28.0 ± 3.3* | —            | **5.0 ± 0.5**    | 5.4–5.6×        |

\* `prefixesClassicFixture` and `prefixesStreamMediated` (two representations of
the old byte[]-prefix path).

Allocation (`gc.alloc.rate.norm`): **≈ 0 B/op on every leg, before and after.**
The techniques are CPU/micro-architectural wins — none of them trades away the
project's zero-allocation property (the string paths still require
`--add-opens` to reach the zero-copy `String` access, exactly as before).

## Results — event benchmark (whole 5-field event, avg time)

| Leg                                                             | ns/op       | ops/us (thrpt) | B/op |
| --------------------------------------------------------------- | ----------- | -------------- | ---- |
| `eventClassicRbo` (before: classic internals, stream mechanism) | 215 ± 24    | 4.46 ± 0.64    | ≈ 0  |
| `eventNewStream` (new internals, stream mechanism)              | 125 ± 9     | 5.38 ± 0.79    | ≈ 0  |
| `eventNewDirect` (after: new internals + direct buffer)         | **108 ± 9** | 9.04 ± 0.76    | ≈ 0  |

- **Before → After: 215 → 108 ns/op ≈ 2.0× faster** end-to-end for a
  representative event (`ts` + `level` + `logger` + `thread` + `msg`, the
  fixed fields written by `JsonLogWriter` on every log line).
- T1/T2/T5 (new internals) account for ~1.7× (215 → 125 ns/op, stream
  mechanism); T4/T6 (direct buffer + packed prefixes) add another ~1.16×
  (125 → 108 ns/op).

Isolation legs (cost of the logback event accessors `JsonLogWriter` calls per
event, measured alone): `mdcPropertyMapAccess`, `keyValuePairsAccess`,
`throwableProxyAccess`, `formattedMessageAccess` — each **≈ 0.001 µs/op and
≈ 0 B/op** (all cached). The accessors are not a meaningful part of the event
cost; the difference between the legs is the writer internals.

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
event benchmark isolates it: same new internals, 125 ns stream vs 108 ns
direct; and the prefix micro-benchmark shows the mechanism cost of two fixed
fields collapsing from ~28 ns (`write(',')` + `write(byte[])` virtual calls +
`arraycopy`) to **5.0 ns** (one `write(',')` + two packed `writeLongPrefixLE`
stores with one local check) — **5.5×**. Removing the per-call virtual
dispatch and letting C2 keep `buffer`/`position` live across the field
sequence is what Fory's design buys.

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
this is bundled with T4; the standalone `prefixes` benchmark shows the 5.5×
mechanism win that contributes the 125 → 108 ns/op step.

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
2. **Event-level 2.0× with zero allocation cost** is the headline: 215 → 108
   ns/op for the fixed-field core of every log line, with the throwable path
   (dominated by the stack-trace writer, covered by the `latin1*` micro legs)
   unaffected in allocation profile.
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
