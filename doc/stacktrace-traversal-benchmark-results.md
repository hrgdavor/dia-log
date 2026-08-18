# Stacktrace Traversal Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [core/src/test/java/hr/hrg/dialog/core/StackTraceTraversalBenchmark.java](core/src/test/java/hr/hrg/dialog/core/StackTraceTraversalBenchmark.java)

Historical optimization timeline is tracked in:

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

- [bench-stacktrace-traversal-latest-output.txt](bench-stacktrace-traversal-latest-output.txt)
- [bench-stacktrace-traversal-latest.json](bench-stacktrace-traversal-latest.json)

## Latest results

| Benchmark method                                 | Avg time    | Throughput   | Alloc norm    |
| ------------------------------------------------ | ----------- | ------------ | ------------- |
| benchmarkThrowableStackTraceArray                | 3.018 us/op | 0.313 ops/us | 1496.045 B/op |
| benchmarkStackWalkerEAFriendly                   | 3.481 us/op | 0.289 ops/us | 2640.051 B/op |
| benchmarkStackWalkerNonEAFriendly                | 3.490 us/op | 0.280 ops/us | 2576.079 B/op |
| benchmarkThrowableStackTraceArrayWyhashZeroAlloc | 3.731 us/op | 0.277 ops/us | 1632.052 B/op |
| benchmarkThrowableStackTraceArrayWyhashFallback  | 3.904 us/op | 0.273 ops/us | 3000.055 B/op |

## Current interpretation

1. Direct Throwable stacktrace array traversal with String.hashCode remains the fastest in this benchmark.
2. StackWalker variants are slower and allocate more than direct array traversal in this micro-shape.
3. WyHash paths provide full-stream fingerprint semantics at measurable CPU cost relative to direct String.hashCode traversal.
4. UTF-8 fallback hashing remains the highest-allocation hashing variant.

## Current recommendation

Use the direct stacktrace array path for minimal per-call cost when String-level hashing is sufficient; use WyHash stream semantics when canonical full-trace fingerprint behavior is required.
