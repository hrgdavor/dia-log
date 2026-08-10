# Stacktrace OutputStream Escaping Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [logback/src/test/java/hr/hrg/dialog/logback/StacktraceOutputStreamEscapingBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/StacktraceOutputStreamEscapingBenchmark.java)

Historical timeline is tracked in:

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

- [bench-stacktrace-outputstream-escaping-latest-output.txt](bench-stacktrace-outputstream-escaping-latest-output.txt)
- [bench-stacktrace-outputstream-escaping-latest.json](bench-stacktrace-outputstream-escaping-latest.json)

## Latest results

| Benchmark method                           | Avg time    | Throughput   | Alloc norm     |
| ------------------------------------------ | ----------- | ------------ | -------------- |
| optimizedOutputStreamEscapedNewlines       | 0.372 us/op | 2.528 ops/us | 88.003 B/op    |
| printStackTraceThenEscapedJsonStringWriter | 3.893 us/op | 0.240 ops/us | 18832.027 B/op |

## Current interpretation

1. Direct OutputStream path remains clearly faster.
2. Direct OutputStream path remains dramatically lower-allocation.
3. printStackTrace string capture plus escaping remains unsuitable for low-GC hot paths.

## Current recommendation

For JSON stack output in performance-sensitive paths, keep using direct escaped-newline emission rather than printStackTrace string pipelines.
