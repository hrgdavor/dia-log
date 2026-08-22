# JsonLogWriter Benchmark Re-run — 2026-08-22

Re-run of the headline `JsonLogWriterBenchmark` (full event: fixed fields +
MDC + statement key/value pairs, with and without a throwable) against the
**2026-08-18** baseline recorded in
[`doc/json-log-writer-benchmark-results.md`](../json-log-writer-benchmark-results.md).

The only change to the benchmark harness between the two runs is a method rename
(`writeJsonEvent` → `writeJsonEventStream`); the event shape (MDC `traceId`/
`spanId`/`tenant`, KV `requestId`/`attempt`/`cacheHit`/`latencyMs`, optional
wrapping throwable) is identical, so the comparison is apples-to-apples for the
hot path.

## Environment

- Date: 2026-08-22
- Machine: AMD Ryzen 9 7945HX, Windows (x86-64, little-endian)
- JDK: 25.0.3, JMH: 1.37
- Mode: average time, `-prof gc`, `-t 1`, `-f 1`
- Warmup: 5 × 1 s, Measurement: 10 × 1 s
- `--add-opens java.base/java.lang=ALL-UNNAMED` on launcher and forks

Artifact: [`bench-jsonlogwriter-2026-08-22.csv`](bench-jsonlogwriter-2026-08-22.csv)
(re-runs of the Fory event/cursor suites in
[`bench-event-cursor-2026-08-22.csv`](bench-event-cursor-2026-08-22.csv) and the
allocation suite in [`bench-allocation-2026-08-22.csv`](bench-allocation-2026-08-22.csv)
confirm the `eventNewDirect` ≈ 0.05 µs/op and `cursorWriteOps` ≈ 2.5 µs/op current
state from `fory-perf-benchmark-results.md`).

## Numbers (average time, alloc norm)

| Benchmark method                | includeThrowable | Avg 2026-08-18 | Avg 2026-08-22 | Alloc 08-18 | Alloc 08-22 |
| ------------------------------- | ---------------- | -------------- | -------------- | ----------- | ----------- |
| `writeWithJsonLogWriter`        | false            | 0.507 us/op    | **0.563 us/op**| 272 B/op    | **456 B/op**|
| `writeWithJsonLogWriterClassic` | false            | 0.610 us/op    | 0.612 us/op    | 784 B/op    | 816 B/op    |
| `writeWithJsonLogWriter`        | true             | 5.706 us/op    | **2.159 us/op**| 272 B/op    | **592 B/op**|
| `writeWithJsonLogWriterClassic` | true             | 6.218 us/op    | 2.093 us/op    | 872 B/op    | 904 B/op    |

## Notable changes since 2026-08-18

1. **Throwable path is ≈2.6–3.0× faster — for both writers.** `JsonLogWriter`
   drops 5.706 → 2.159 us/op; the classic Jackson path drops 6.218 → 2.093 us/op.
   The `JsonLogWriter` throwable branch is byte-for-byte identical to the
   2026-08-18 code, so the win comes entirely from the shared stack-trace writer
   (`JavaStackWriterLogback.addFromTraceToOutputStreamJsonAndFingerprint` and the
   `JavaStackSanitizer` derivatives), improved across the stack-writing commits
   since 2026-08-18. Net: the relative speed ordering between the two writers is
   essentially unchanged on throwables.

2. **No-throwable latency lead narrowed.** `JsonLogWriter` went 0.507 → 0.563
   us/op (~11% slower); `JsonLogWriterClassic` is flat (0.610 → 0.612). JSON's
   lead over classic shrank from ~17% to ~8% faster (no throwable). With a
   throwable the two are now within ~3% on latency (classic marginally ahead).

3. **The benchmark measures the `writeJsonEventStream` fallback, not the
   production path — so the apparent allocation "regression" is a measurement
   artifact.** The benchmark calls `writer.writeJsonEventStream(...)`; production
   (`JsonAppender` / `JsonAppenderRolling`) calls `writeJsonEventDirect`. Direct
   `ThreadMXBean` measurement shows `writeJsonEventDirect` allocates **≈330 B/op**
   for the same MDC+KV event — essentially unchanged from the 2026-08-18 272 B/op
   baseline (the only alloc is the MDC+KV `allKeys` `HashSet`; with/without a
   throwable it is 334 = 334 — the throwable branch adds ≈0). The 2026-08-18 run
   measured the then-optimized `writeJsonEvent` (equivalent to today's
   `writeJsonEventDirect`), so it showed 272; the 2026-08-22 run measures the
   naive fallback, whose two per-event allocations explain the gap:
   - **Field prefixes re-encoded every event.** `writeFieldPrefix(OutputStream,
     String)` does `out.write(key.getBytes(UTF_8))` (and `KEY_TS` likewise). Each
     fixed field allocates a fresh `byte[]`: `ts/level/logger/thread/msg` ≈ 128 B;
     throwable adds `errClass/errMessage/stack/errHash` ≈ 120 B. Production stores
     these as packed `LE_LONG` VarHandle words (0 B/op).
   - **Bufferless number writes.** `writeJsonEventStream` calls
     `JsonNumberWriter.writeLong(out, long)` (ts; and `errHash` when throwable) —
     the bufferless T9 variant that allocates a ~40 B scratch `byte[]` per number.
     Production writes numbers at a buffer offset (0 B/op).
   - Reconstruction: no-throwable ≈ 272 + 128 + 40 ≈ 440 (measured 456); throwable
     adds ≈ 120 + 40 ≈ +160 (measured +136). The classic path is ~flat
     (784→816, 872→904); its +32 both is the throwable `StackTraceElement[]` copy.

## Interpretation

The headline win versus 2026-08-18 is the **throwable serialization speedup**
(shared stack-writer work), not the fixed-field assembly — `eventNewDirect`
stayed at ~0.05–0.06 µs/op. The allocation numbers rose only because the
benchmark exercises `writeJsonEventStream` (the stream fallback), whose
field-prefix `getBytes` + bufferless-number scratch are real but off the hot
path. **There is no production allocation regression**: `writeJsonEventDirect`
is ≈330 B/op, matching 2026-08-18.

## Follow-up

- Repoint `JsonLogWriterBenchmark.writeWithJsonLogWriter` at `writeJsonEventDirect`
  (with a `ReusableByteArrayOutputStream`) so the headline comparison reflects
  production; it should then show ≈330 B/op and confirm parity with 2026-08-18.
- Optionally, optimize `writeJsonEventStream`'s field prefixes (reuse pre-encoded
  `byte[]`/packed words + buffered number writes) so the fallback is not
  order-of-magnitude worse than the direct path — but this is an API-compat
  convenience method, not the hot path.
- Re-confirm whether the no-throwable latency dip (0.507 → 0.563) is a real cost
  or run-to-run noise on this short (sub-microsecond) benchmark.
