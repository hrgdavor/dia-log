# Stacktrace Write + Fingerprint Single-Pass Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [core/src/test/java/hr/hrg/dialog/core/StacktraceWriteAndFingerprintBenchmark.java](core/src/test/java/hr/hrg/dialog/core/StacktraceWriteAndFingerprintBenchmark.java)

Historical optimization iterations (v1-v5), fairness corrections, and prior deltas are tracked in:

- [doc/benchmark-optimization-history.md](doc/benchmark-optimization-history.md)

## Latest run

- Date: 2026-08-18
- Machine: AMD Ryzen 9 7945HX, Windows (x86-64, little-endian)
- JDK: 25.0.3
- JMH: 1.37
- Modes: throughput and average-time
- Warmup: 2 x 1s
- Measurement: 3 x 1s
- Forks: 1
- Profiler: -prof gc

Artifacts:

- [bench-stacktrace-write-fingerprint-singlepass-latest-output.txt](bench-stacktrace-write-fingerprint-singlepass-latest-output.txt)
- [bench-stacktrace-write-fingerprint-singlepass-latest.json](bench-stacktrace-write-fingerprint-singlepass-latest.json)

## Latest results

| Benchmark method               | Avg time    | Throughput   | Alloc norm  |
| ------------------------------ | ----------- | ------------ | ----------- |
| fingerprintOnlyPreparedTrace   | 0.587 us/op | 1.723 ops/us | 0.004 B/op  |
| writeOnlyRawNewline            | 4.673 us/op | 0.209 ops/us | 0.033 B/op  |
| writeOnlyJsonEscapedNewline    | 4.804 us/op | 0.208 ops/us | 0.033 B/op  |
| separatePassRawNewline         | 5.545 us/op | 0.183 ops/us | 0.038 B/op  |
| singlePassRawNewline           | 5.381 us/op | 0.194 ops/us | 0.035 B/op  |
| separatePassJsonEscapedNewline | 5.650 us/op | 0.179 ops/us | 0.039 B/op  |
| singlePassJsonEscapedNewline   | 5.425 us/op | 0.185 ops/us | 0.037 B/op  |

## Current interpretation

1. Single-pass paths remain at least as fast as separate-pass paths for both newline variants.
2. Single-pass paths keep allocation effectively near zero in this benchmark shape.
3. Fingerprint-only remains near-zero allocation with reusable streaming state.
4. The previous separate-pass allocation outlier (32 B/op) is gone: the fingerprint
   entry points now reuse a caller-owned hasher, so every path allocates ≤ 0.04 B/op.

## Current recommendation

Use single-pass APIs whenever both stack output and fingerprint are required:

- addFromTraceToOutputStreamAndFingerprint
- addFromTraceToOutputStreamJsonAndFingerprint
- addFromTraceToOutputStreamWithNewlineAndFingerprint
