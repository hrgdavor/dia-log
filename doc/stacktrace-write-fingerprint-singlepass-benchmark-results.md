# Stacktrace Write + Fingerprint Single-Pass Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [core/src/test/java/hr/hrg/dialog/core/StacktraceWriteAndFingerprintBenchmark.java](core/src/test/java/hr/hrg/dialog/core/StacktraceWriteAndFingerprintBenchmark.java)

Historical optimization iterations (v1-v5), fairness corrections, and prior deltas are tracked in:

- [doc/benchmark-optimization-history.md](doc/benchmark-optimization-history.md)

## Latest run

- JDK: 25.0.3
- JMH: 1.37
- Modes: throughput and average-time
- Warmup: 3 x 1s
- Measurement: 5 x 1s
- Forks: 1
- Profiler: -prof gc

Artifacts:

- [bench-stacktrace-write-fingerprint-singlepass-latest-output.txt](bench-stacktrace-write-fingerprint-singlepass-latest-output.txt)
- [bench-stacktrace-write-fingerprint-singlepass-latest.json](bench-stacktrace-write-fingerprint-singlepass-latest.json)

## Latest results

| Benchmark method               | Avg time    | Throughput   | Alloc norm  |
| ------------------------------ | ----------- | ------------ | ----------- |
| fingerprintOnlyPreparedTrace   | 0.588 us/op | 1.877 ops/us | 0.004 B/op  |
| writeOnlyRawNewline            | 0.364 us/op | 2.806 ops/us | 0.002 B/op  |
| writeOnlyJsonEscapedNewline    | 0.362 us/op | 2.765 ops/us | 0.002 B/op  |
| separatePassRawNewline         | 1.311 us/op | 0.833 ops/us | 32.009 B/op |
| singlePassRawNewline           | 1.152 us/op | 0.860 ops/us | 0.008 B/op  |
| separatePassJsonEscapedNewline | 1.159 us/op | 0.861 ops/us | 0.008 B/op  |
| singlePassJsonEscapedNewline   | 1.144 us/op | 0.849 ops/us | 0.008 B/op  |

## Current interpretation

1. Single-pass paths remain faster than separate-pass paths for both newline variants.
2. Single-pass paths keep allocation effectively near zero in this benchmark shape.
3. Fingerprint-only remains near-zero allocation with reusable streaming state.
4. The raw separate-pass allocation outlier remains visible and should be treated as a known behavior to watch.

## Current recommendation

Use single-pass APIs whenever both stack output and fingerprint are required:

- addFromTraceToOutputStreamAndFingerprint
- addFromTraceToOutputStreamJsonAndFingerprint
- addFromTraceToOutputStreamWithNewlineAndFingerprint
