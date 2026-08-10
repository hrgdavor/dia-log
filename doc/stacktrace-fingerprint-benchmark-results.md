# Stacktrace Fingerprint Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [logback/src/test/java/hr/hrg/dialog/logback/StacktraceFingerprintBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/StacktraceFingerprintBenchmark.java)

Historical context and prior investigation phases are tracked in:

- [doc/benchmark-optimization-history.md](doc/benchmark-optimization-history.md)

## Latest run scope

This latest run is focused on the primary comparison methods:

- writeWithJsonLogWriter
- writeWithJsonLogWriterClassic
- writeWithPrintStackTraceStringWriter

Run settings:

- JDK: 25.0.3
- JMH: 1.37
- Modes: throughput and average-time
- Warmup: 3 x 1s
- Measurement: 5 x 1s
- Forks: 1
- Profiler: -prof gc

Artifacts:

- [bench-stacktrace-fingerprint-latest-output.txt](bench-stacktrace-fingerprint-latest-output.txt)
- [bench-stacktrace-fingerprint-latest.json](bench-stacktrace-fingerprint-latest.json)

## Latest results

| Benchmark method                     | Avg time     | Throughput   | Alloc norm     |
| ------------------------------------ | ------------ | ------------ | -------------- |
| writeWithJsonLogWriter               | 12.214 us/op | 0.082 ops/us | 968.084 B/op   |
| writeWithJsonLogWriterClassic        | 13.505 us/op | 0.077 ops/us | 1336.093 B/op  |
| writeWithPrintStackTraceStringWriter | 6.073 us/op  | 0.169 ops/us | 19296.042 B/op |

## Current interpretation

1. printStackTrace string path has lower average time in this micro-shape.
2. printStackTrace string path is still allocation-heavy by a large margin.
3. JsonLogWriter remains lower-allocation than JsonLogWriterClassic while also being faster.

## Current recommendation

When sustained allocation pressure and GC behavior matter more than single micro-case CPU latency, prefer structured writer paths, and use this benchmark together with:

- [doc/stacktrace-outputstream-escaping-benchmark-results.md](doc/stacktrace-outputstream-escaping-benchmark-results.md)

for broader stack-output trade-off assessment.
