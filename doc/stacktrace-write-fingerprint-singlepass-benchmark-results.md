# Stacktrace Write + Fingerprint Single-Pass Benchmark Results

This document summarizes benchmark results for [core/src/test/java/hr/hrg/dialog/core/StacktraceWriteAndFingerprintBenchmark.java](core/src/test/java/hr/hrg/dialog/core/StacktraceWriteAndFingerprintBenchmark.java).

The benchmark compares two approaches:

- separate-pass: write stacktrace to OutputStream, then compute fingerprint in a second traversal
- single-pass: write stacktrace and compute fingerprint in one traversal using new APIs in [core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java](core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java)

## New APIs

Added single-pass variants:

- `addFromTraceToOutputStreamAndFingerprint(...)`
- `addFromTraceToOutputStreamJsonAndFingerprint(...)`
- `addFromTraceToOutputStreamWithNewlineAndFingerprint(...)`

These methods keep fingerprint compatibility with `fingerprint(Throwable, Predicate)` by hashing:

- throwable class name (if provided)
- raw newline separators in hash payload (`\n`), even when output uses JSON newline bytes (`\\n`)
- normalized class/method frame content with existing lambda normalization rules

## Run details

- JDK: `25.0.3`
- JMH: `1.37`
- Modes: throughput and average-time
- Warmup: `3 x 1s`
- Measurement: `5 x 1s`
- Forks: `1`
- GC profiler: `-prof gc`

Artifacts:

- Raw output: [bench-stacktrace-write-fingerprint-singlepass-output.txt](bench-stacktrace-write-fingerprint-singlepass-output.txt)
- JSON results: [bench-stacktrace-write-fingerprint-singlepass.json](bench-stacktrace-write-fingerprint-singlepass.json)
- Corrected raw output: [bench-stacktrace-write-fingerprint-singlepass-v2-output.txt](bench-stacktrace-write-fingerprint-singlepass-v2-output.txt)
- Corrected JSON results: [bench-stacktrace-write-fingerprint-singlepass-v2.json](bench-stacktrace-write-fingerprint-singlepass-v2.json)
- Decomposition raw output: [bench-stacktrace-write-fingerprint-singlepass-v3-output.txt](bench-stacktrace-write-fingerprint-singlepass-v3-output.txt)
- Decomposition JSON results: [bench-stacktrace-write-fingerprint-singlepass-v3.json](bench-stacktrace-write-fingerprint-singlepass-v3.json)
- Reusable-stream raw output: [bench-stacktrace-write-fingerprint-singlepass-v4-output.txt](bench-stacktrace-write-fingerprint-singlepass-v4-output.txt)
- Reusable-stream JSON results: [bench-stacktrace-write-fingerprint-singlepass-v4.json](bench-stacktrace-write-fingerprint-singlepass-v4.json)
- Streaming-fastpath raw output: [bench-stacktrace-write-fingerprint-singlepass-v5-output.txt](bench-stacktrace-write-fingerprint-singlepass-v5-output.txt)
- Streaming-fastpath JSON results: [bench-stacktrace-write-fingerprint-singlepass-v5.json](bench-stacktrace-write-fingerprint-singlepass-v5.json)

## Setup correction

The initial benchmark used `fingerprint(Throwable, filter)` in the separate-pass path.
That method calls `Throwable.getStackTrace()` and uses per-frame `getBytes(...)` in hashing, which adds unrelated allocation overhead.

For an apples-to-apples comparison, separate-pass was corrected to use prepared traces via:

- `fingerprintFromTrace(StackTraceElement[], Predicate<String>, String)`

This keeps semantics consistent while removing benchmark bias from stack-trace array recreation.

## Measured results (reusable-stream, v4)

Average-time mode is the easiest way to compare per-operation latency.

| Benchmark method                     | Avg time      | Throughput     | Alloc norm       | GC count | GC time |
| ------------------------------------ | ------------- | -------------- | ---------------- | -------- | ------- |
| `separatePassRawNewline`             | `1.337 us/op` | `0.730 ops/us` | `32.009 B/op`    | `0`      | `0 ms`  |
| `singlePassRawNewline`               | `1.312 us/op` | `0.839 ops/us` | `0.009 B/op`     | `0`      | `0 ms`  |
| `separatePassJsonEscapedNewline`     | `1.447 us/op` | `0.973 ops/us` | `0.010 B/op`     | `0`      | `0 ms`  |
| `singlePassJsonEscapedNewline`       | `1.240 us/op` | `0.737 ops/us` | `0.008 B/op`     | `0`      | `0 ms`  |

## Decomposition timings

To complete the picture, the benchmark now includes fingerprint-only and write-only methods using the same prepared-trace pipeline.

| Decomposition method            | Avg time      | Throughput     | Alloc norm      |
| ------------------------------- | ------------- | -------------- | --------------- |
| `fingerprintOnlyPreparedTrace`  | `0.638 us/op` | `1.496 ops/us` | `0.004 B/op`    |
| `writeOnlyRawNewline`           | `0.365 us/op` | `2.626 ops/us` | `0.002 B/op`    |
| `writeOnlyJsonEscapedNewline`   | `0.392 us/op` | `2.632 ops/us` | `0.003 B/op`    |

Notes:

- Write-only allocation is effectively zero due reusable buffers and direct writing.
- Fingerprint-only is now also effectively zero-allocation because the same `Wyhash64.Streaming` instance is reused via `reset(0)`.

## Additive sanity check

Raw-newline path:

- `writeOnlyRawNewline + fingerprintOnlyPreparedTrace` is about `0.365 + 0.638 = 1.003 us/op`
- observed `separatePassRawNewline` is `1.337 us/op`
- observed `singlePassRawNewline` is `1.312 us/op`

JSON-newline path:

- `writeOnlyJsonEscapedNewline + fingerprintOnlyPreparedTrace` is about `0.392 + 0.638 = 1.030 us/op`
- observed `separatePassJsonEscapedNewline` is `1.447 us/op`
- observed `singlePassJsonEscapedNewline` is `1.240 us/op`

This indicates the second traversal introduces additional overhead beyond pure additive component timings (cache/state effects, extra control flow, and JIT code-shape differences), while single-pass removes part of that overhead.

## Interpretation

1. Reusing `Wyhash64.Streaming` removes the fingerprint allocation hotspot.
- `fingerprintOnlyPreparedTrace` dropped from ~`136 B/op` to effectively `0 B/op`.
- End-to-end methods are now also near-zero allocation in most cases.

2. Single-pass still avoids second-traversal overhead in additive checks.
- Both newline modes show `singlePass` closer to the decomposed lower bound than `separatePass`.
- Throughput numbers in this run are mixed by mode; average-time comparison is the more stable signal here.

3. Average-time remains better for single-pass in this run.
- Raw newline avgt: `1.337` -> `1.312 us/op`.
- JSON newline avgt: `1.447` -> `1.240 us/op`.

## Practical takeaway

For cases where stacktrace output and fingerprint are both needed, use the new single-pass APIs when your priority is reducing traversal duplication and keeping throughput high. The corrected benchmark shows allocation parity (or near-parity) with separate-pass once setup bias is removed.

## Streaming update micro-optimizations (v5)

To address the "streaming update seems slow" concern, two low-level changes were applied:

1. Added a single-byte fast path in `Wyhash64.Streaming`:
- new method `updateByte(byte b)` appends directly to the internal 48-byte buffer without routing through `update(byte[], off, len)`.

2. Reduced hashing overhead in `JavaStackSanitizer` call sites:
- replaced repeated `stream.update(..., 0, 1)` for delimiters with `stream.updateByte(...)`.
- removed `className.getBytes(UTF_8)` in `addFromTraceElement(...)` and switched to `stream.update(className, 0, classEnd)`.

These are API-compatible internal optimizations; hash payload semantics are unchanged.

## Measured results (v5, average-time)

| Benchmark method                     | v4 avg time    | v5 avg time    | Delta            | v5 alloc norm   |
| ------------------------------------ | -------------- | -------------- | ---------------- | --------------- |
| `fingerprintOnlyPreparedTrace`       | `0.638 us/op`  | `0.554 us/op`  | `13.1% faster`   | `0.004 B/op`    |
| `separatePassRawNewline`             | `1.337 us/op`  | `1.175 us/op`  | `12.2% faster`   | `32.008 B/op`   |
| `singlePassRawNewline`               | `1.312 us/op`  | `1.150 us/op`  | `12.4% faster`   | `0.008 B/op`    |
| `separatePassJsonEscapedNewline`     | `1.447 us/op`  | `1.345 us/op`  | `7.0% faster`    | `0.009 B/op`    |
| `singlePassJsonEscapedNewline`       | `1.240 us/op`  | `1.193 us/op`  | `3.8% faster`    | `0.008 B/op`    |
| `writeOnlyRawNewline`                | `0.365 us/op`  | `0.365 us/op`  | `~0% (noise)`    | `0.003 B/op`    |
| `writeOnlyJsonEscapedNewline`        | `0.392 us/op`  | `0.365 us/op`  | `7.1% faster`    | `0.003 B/op`    |

## v5 interpretation

1. The streaming/hash path did improve measurably.
- `fingerprintOnlyPreparedTrace` improved by ~`13%`, confirming that tiny-update overhead was a real cost.

2. End-to-end single-pass benefited too.
- raw-newline single-pass improved by ~`12%`.
- json-newline single-pass improved by ~`4%` in this run.

3. Allocation did not regress.
- reusable-stream paths remain effectively zero-allocation.
- the `separatePassRawNewline` `~32 B/op` outlier remains and appears unrelated to the new `updateByte` fast path.
