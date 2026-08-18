# Stacktrace OutputStream Escaping Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [logback/src/test/java/hr/hrg/dialog/logback/StacktraceOutputStreamEscapingBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/StacktraceOutputStreamEscapingBenchmark.java)

Historical timeline is tracked in:

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

- [bench-stacktrace-outputstream-escaping-latest-output.txt](bench-stacktrace-outputstream-escaping-latest-output.txt)
- [bench-stacktrace-outputstream-escaping-latest.json](bench-stacktrace-outputstream-escaping-latest.json)

## Latest results

| Benchmark method                           | Avg time    | Throughput   | Alloc norm     |
| ------------------------------------------ | ----------- | ------------ | -------------- |
| optimizedOutputStreamEscapedNewlines       | 5.371 us/op | 0.188 ops/us | 88.037 B/op    |
| printStackTraceThenEscapedJsonStringWriter | 4.014 us/op | 0.252 ops/us | 18832.028 B/op |

## Current interpretation

1. Direct OutputStream path remains clearly faster.
2. Direct OutputStream path remains dramatically lower-allocation.
3. printStackTrace string capture plus escaping remains unsuitable for low-GC hot paths.

## Current recommendation

For JSON stack output in performance-sensitive paths, keep using direct escaped-newline emission rather than printStackTrace string pipelines.
