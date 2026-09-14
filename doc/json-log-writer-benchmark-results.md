# JsonLogWriter Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java](logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java) (benchmark comparison baseline, lives in `src/test`)

Historical optimization timeline, previous runs, and step-by-step gains are tracked in:

- [doc/benchmark-optimization-history.md](doc/benchmark-optimization-history.md)

## Latest run

- Date: 2026-09-14
- Machine: AMD Ryzen 9 7945HX, Windows (x86-64, little-endian)
- JDK: 25.0.3
- JMH: 1.37
- Mode: average time + throughput
- Warmup: 5 x 1s
- Measurement: 10 x 1s
- Forks: 1, Threads: 1
- Profiler: -prof gc
- `--add-opens java.base/java.lang=ALL-UNNAMED` on the launcher; forked VMs
  inherit the launcher options

Since the 2026-08-22 run, `writeWithJsonLogWriter` exercises the production
`JsonLogWriter.writeJsonEventDirect` path (mirroring `JsonAppender#writeOut`
into a reusable `ReusableByteArrayOutputStream`) instead of the
`writeJsonEventStream` fallback. The classic leg is unchanged.

Artifacts:

- [bench-jsonlogwriter-2026-09-14.csv](../perf-exploration/bench-jsonlogwriter-2026-09-14.csv)
- [bench-jsonlogwriter-2026-08-22.csv](../perf-exploration/bench-jsonlogwriter-2026-08-22.csv) (previous run, stream fallback)

## Latest results

| Benchmark method              | includeThrowable | Avg time    | Alloc norm   |
| ----------------------------- | ---------------- | ----------- | ------------ |
| writeWithJsonLogWriter        | false            | 0.331 us/op | 96.00 B/op   |
| writeWithJsonLogWriterClassic | false            | 0.580 us/op | 544.00 B/op  |
| writeWithJsonLogWriter        | true             | 1.431 us/op | 96.01 B/op   |
| writeWithJsonLogWriterClassic | true             | 1.940 us/op | 632.01 B/op  |

### Comparison with the 2026-08-18 run

| Benchmark method              | includeThrowable | Avg 08-18 | Avg 08-22 | Alloc 08-18 | Alloc 08-22 |
| ----------------------------- | ---------------- | --------- | --------- | ----------- | ----------- |
| writeWithJsonLogWriter        | false            | 0.507     | 0.563     | 272         | 456         |
| writeWithJsonLogWriterClassic | false            | 0.610     | 0.612     | 784         | 816         |
| writeWithJsonLogWriter        | true             | 5.706     | 2.159     | 272         | 592         |
| writeWithJsonLogWriterClassic | true             | 6.218     | 2.093     | 872         | 904         |

Notable changes since 2026-08-18 (full write-up in
[`doc/perf-exploration/json-log-writer-rerun-2026-08-22.md`](../perf-exploration/json-log-writer-rerun-2026-08-22.md)):

- **Throwable path ≈2.6–3.0× faster for both writers** (shared stack-trace
  writer improvements). `JsonLogWriter` 5.706 → 2.159 us/op; classic 6.218 →
  2.093 us/op.
- **No-throwable latency lead narrowed**: `JsonLogWriter` 0.507 → 0.563 us/op
  (~11% slower); classic flat. JSON is now ~8% faster than classic without a
  throwable (was ~17%).
- **The 08-22 numbers measured the stream fallback.** That run called the
  test-side `JsonLogWriterStream` helper — not the production
  `writeJsonEventDirect` used by `JsonAppender` — and its per-event
  `String.getBytes(UTF_8)` field prefixes + bufferless `writeLong(out, long)`
  scratch explain 456/592 B/op. Since 08-22, commit `6b1ad77` ("remove dedup
  code", ADR 012 — same day, after that run) removed the per-event `allKeys`
  key set from **both** the production and classic writers; that set was the
  272 B/op of the 08-18/08-22 `JsonLogWriter` numbers and the −272 B/op drop of
  the classic leg (816/904 → 544/632) since 08-22.
- **The 2026-09-14 run benchmarks the production path directly**:
  `writeJsonEventDirect` measures **96 B/op** (both throwable variants) and
  0.331 / 1.431 us/op — faster and lower-allocation than the 2026-08-18
  baseline (0.507/5.706 us/op, 272 B/op). The 96 B/op is a benchmark-harness
  artifact (3 × 32 B `Map.Entry` wrappers from the `Map.of(...)` MDC map —
  production MDC iteration costs at most one small iterator per event); the
  writer internals are zero-allocation.

## What the stream-fallback numbers actually measure

The `writeWithJsonLogWriter` benchmark calls the test-side helper
`JsonLogWriterStream.writeJsonEvent(...)` — the **stream fallback** (the method
was moved out of production `JsonLogWriter` into `src/test`) — not the production
`writeJsonEventDirect` that `JsonAppender`/`JsonAppenderRolling` use. So the
456 / 592 B/op figures are the fallback's cost, not production's. Two per-event
allocations in the fallback explain the gap versus the 2026-08-18 272 B/op (which
measured the then-optimized `writeJsonEvent`):

- **Field prefixes re-encoded every event.** `writeFieldPrefix(OutputStream, String)`
  does `out.write(key.getBytes(StandardCharsets.UTF_8))` (and `KEY_TS` is written the
  same way). Each fixed field allocates a fresh `byte[]`: `ts/level/logger/thread/msg`
  ≈ 128 B; the throwable adds `errClass/errMessage/stack/errHash` ≈ 120 B. The
  production `writeJsonEventDirect` instead stores these as packed `LE_LONG` VarHandle
  words into the reusable buffer (0 B/op).
- **Bufferless number writes.** The helper calls
  `JsonNumberWriter.writeLong(out, long)` (ts; and `errHash` when throwable) — the
  bufferless T9 variant that allocates a ~40 B scratch `byte[]` per number. Production
  `writeJsonEventDirect` writes numbers at a buffer offset (`writeLong(buf, pos, …)`,
  0 B/op).

MDC/KV keys and string values still go through the zero-alloc
`EscapedJsonStringWriter` / `writeStringDirect` path, identical to 2026-08-18, so they
add nothing over baseline. Reconstruction: no-throwable ≈ 272 (baseline `allKeys`
`HashSet`) + 128 (getBytes) + 40 (ts scratch) ≈ 440 (measured 456); throwable adds
≈ 120 (err* getBytes) + 40 (errHash scratch) ≈ +160 (measured +136).

Contrast (this part is real and unchanged): `writeWithJsonLogWriterClassic` shows
**+88 B/op** for throwable (816.004 → 904.015) — its per-event
`new StackTraceElement[…]` conversion array (16-byte header + 4 bytes/frame). The 88 B/op
`getStackTrace()` clone only appears in the core `fingerprint(Throwable, …)` API, which
`JsonLogWriter` never calls per event.

## Current interpretation

1. JsonLogWriter beats JsonLogWriterClassic on latency (0.331 vs 0.580 us/op without a throwable; 1.431 vs 1.940 with one) and allocates substantially less in both cases (96 vs 544/632 B/op) — on the production `writeJsonEventDirect` path the benchmark now exercises.
2. The throwable path is dramatically faster than at 2026-08-18 for both writers, thanks to shared stack-trace writer improvements (JsonLogWriter 5.706 → 1.431 us/op since the 08-18 baseline).
3. **There is no production allocation regression — and the benchmark now proves it directly.** `writeJsonEventDirect` measures **96 B/op** for the same MDC+KV event (the throwable branch adds ≈ 0 B/op). The ≈330 B/op figure quoted in the 2026-08-22 re-run predates commit `6b1ad77` (ADR 012, same day after that run), which removed the per-event `allKeys` key set — the estimate's only real allocation; the 96 B/op is a benchmark-harness artifact (3 × 32 B `Map.Entry` wrappers from the immutable `Map.of(...)` MDC map — in production, MDC iteration costs at most one small iterator per event). The 456 / 592 B/op figures remain the `JsonLogWriterStream` fallback's `getBytes` field prefixes + bufferless number scratch, not the hot path.

## Current recommendation

Use JsonLogWriter as the default high-throughput path (`JsonAppender` → the production `writeJsonEventDirect`). Since the 2026-09-14 run, `JsonLogWriterBenchmark` measures exactly that production path (with a `ReusableByteArrayOutputStream`, mirroring `JsonAppender#writeOut`), so the headline comparison is production-vs-classic: 96 B/op at 0.331–1.431 us/op vs 544–632 B/op at 0.580–1.940 us/op.
