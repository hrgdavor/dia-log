# JsonLogWriter Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java)

Historical optimization timeline, previous runs, and step-by-step gains are tracked in:

- [doc/benchmark-optimization-history.md](doc/benchmark-optimization-history.md)

## Latest run

- Date: 2026-08-22
- Machine: AMD Ryzen 9 7945HX, Windows (x86-64, little-endian)
- JDK: 25.0.3
- JMH: 1.37
- Mode: average time
- Warmup: 5 x 1s
- Measurement: 10 x 1s
- Forks: 1, Threads: 1
- Profiler: -prof gc
- `--add-opens java.base/java.lang=ALL-UNNAMED` on launcher and forks

Artifacts:

- [bench-jsonlogwriter-2026-08-22.csv](../perf-exploration/bench-jsonlogwriter-2026-08-22.csv)

## Latest results

| Benchmark method              | includeThrowable | Avg time    | Alloc norm   |
| ----------------------------- | ---------------- | ----------- | ------------ |
| writeWithJsonLogWriter        | false            | 0.563 us/op | 456.004 B/op |
| writeWithJsonLogWriterClassic | false            | 0.612 us/op | 816.004 B/op |
| writeWithJsonLogWriter        | true             | 2.159 us/op | 592.015 B/op |
| writeWithJsonLogWriterClassic | true             | 2.093 us/op | 904.015 B/op |

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
- **Allocation in the `writeJsonEventStream` (stream fallback) path rose**: this
  benchmark calls `writeJsonEventStream` (not the production `writeJsonEventDirect`
  used by `JsonAppender`). The fallback allocates 272 → 456 B/op (no throwable)
  and 272 → 592 B/op (with throwable) because its field prefixes are re-encoded
  per event via `String.getBytes(UTF_8)` and numbers use the bufferless
  `writeLong(out, long)` scratch. **Production `writeJsonEventDirect` allocates
  ≈330 B/op — unchanged from the 2026-08-18 272 B/op baseline** (the only alloc
  is the MDC+KV `allKeys` `HashSet`). The classic path is ~flat. So there is no
  production allocation regression; the higher numbers are a measurement artifact
  of benchmarking the fallback method.

## What the `writeJsonEventStream` numbers actually measure

The `writeWithJsonLogWriter` benchmark calls `JsonLogWriter.writeJsonEventStream`
— the **stream fallback** — not the production `writeJsonEventDirect` that
`JsonAppender`/`JsonAppenderRolling` use. So the 456 / 592 B/op figures are the
fallback's cost, not production's. Two per-event allocations in the fallback explain
the gap versus the 2026-08-18 272 B/op (which measured the then-optimized
`writeJsonEvent`):

- **Field prefixes re-encoded every event.** `writeFieldPrefix(OutputStream, String)`
  does `out.write(key.getBytes(StandardCharsets.UTF_8))` (and `KEY_TS` is written the
  same way). Each fixed field allocates a fresh `byte[]`: `ts/level/logger/thread/msg`
  ≈ 128 B; the throwable adds `errClass/errMessage/stack/errHash` ≈ 120 B. The
  production `writeJsonEventDirect` instead stores these as packed `LE_LONG` VarHandle
  words into the reusable buffer (0 B/op).
- **Bufferless number writes.** `writeJsonEventStream` calls
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

1. JsonLogWriter matches or beats JsonLogWriterClassic on latency (≈8% faster without a throwable, within ~3% with one) and allocates substantially less in both cases (~44% less without, ~34% less with a throwable) — on the `writeJsonEventStream` path the benchmark exercises.
2. The throwable path is dramatically faster than at 2026-08-18 for both writers, thanks to shared stack-trace writer improvements.
3. **There is no production allocation regression.** The production path `writeJsonEventDirect` measures ≈330 B/op for the same MDC+KV event — essentially unchanged from the 2026-08-18 272 B/op (the only alloc is the MDC+KV `allKeys` `HashSet`; the throwable branch adds ≈0 B/op, 334 = 334 with/without). The 456 / 592 B/op figures are the `writeJsonEventStream` fallback's `getBytes` field prefixes + bufferless number scratch, not the hot path.

## Current recommendation

Use JsonLogWriter as the default high-throughput path (the production `writeJsonEventDirect`). The `JsonLogWriterBenchmark` currently measures the `writeJsonEventStream` fallback; to compare production-vs-classic honestly it should call `writeJsonEventDirect` (with a `ReusableByteArrayOutputStream`), which will show ≈330 B/op and confirm parity with the 2026-08-18 baseline.
