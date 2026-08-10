# Stacktrace Traversal Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [core/src/test/java/hr/hrg/dialog/core/StackTraceTraversalBenchmark.java](core/src/test/java/hr/hrg/dialog/core/StackTraceTraversalBenchmark.java)

Historical optimization timeline is tracked in:

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

- [bench-stacktrace-traversal-latest-output.txt](bench-stacktrace-traversal-latest-output.txt)
- [bench-stacktrace-traversal-latest.json](bench-stacktrace-traversal-latest.json)

## Latest results

| Benchmark method                                 | Avg time    | Throughput   | Alloc norm    |
| ------------------------------------------------ | ----------- | ------------ | ------------- |
| benchmarkThrowableStackTraceArray                | 3.048 us/op | 0.316 ops/us | 1496.022 B/op |
| benchmarkStackWalkerEAFriendly                   | 3.440 us/op | 0.267 ops/us | 2640.025 B/op |
| benchmarkStackWalkerNonEAFriendly                | 3.527 us/op | 0.251 ops/us | 2576.026 B/op |
| benchmarkThrowableStackTraceArrayWyhashZeroAlloc | 3.568 us/op | 0.256 ops/us | 1632.025 B/op |
| benchmarkThrowableStackTraceArrayWyhashFallback  | 3.759 us/op | 0.230 ops/us | 3032.027 B/op |

## Current interpretation

1. Direct Throwable stacktrace array traversal with String.hashCode remains the fastest in this benchmark.
2. StackWalker variants are slower and allocate more than direct array traversal in this micro-shape.
3. WyHash paths provide full-stream fingerprint semantics at measurable CPU cost relative to direct String.hashCode traversal.
4. UTF-8 fallback hashing remains the highest-allocation hashing variant.

## Current recommendation

Use the direct stacktrace array path for minimal per-call cost when String-level hashing is sufficient; use WyHash stream semantics when canonical full-trace fingerprint behavior is required.
