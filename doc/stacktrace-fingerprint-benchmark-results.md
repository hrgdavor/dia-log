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

- [bench-stacktrace-fingerprint-latest-output.txt](bench-stacktrace-fingerprint-latest-output.txt)
- [bench-stacktrace-fingerprint-latest.json](bench-stacktrace-fingerprint-latest.json)

## Latest results

| Benchmark method                     | Avg time     | Throughput   | Alloc norm     |
| ------------------------------------ | ------------ | ------------ | -------------- |
| writeWithJsonLogWriter               | 14.607 us/op | 0.069 ops/us | 736.101 B/op   |
| writeWithJsonLogWriterClassic        | 16.044 us/op | 0.062 ops/us | 1200.110 B/op  |
| writeWithPrintStackTraceStringWriter | 5.584 us/op  | 0.177 ops/us | 19128.038 B/op |

Compared with the previous run, allocation dropped for the structured writers
(968 → 736 B/op for `writeWithJsonLogWriter`, 1336 → 1200 B/op for
`writeWithJsonLogWriterClassic`) — the fingerprint entry points now reuse a
caller-owned hasher, so no `Wyhash64.Streaming` is allocated per event.

## Current interpretation

1. printStackTrace string path has lower average time in this micro-shape.
2. printStackTrace string path is still allocation-heavy by a large margin.
3. JsonLogWriter remains lower-allocation than JsonLogWriterClassic while also being faster.

## Current recommendation

When sustained allocation pressure and GC behavior matter more than single micro-case CPU latency, prefer structured writer paths, and use this benchmark together with:

- [doc/stacktrace-outputstream-escaping-benchmark-results.md](doc/stacktrace-outputstream-escaping-benchmark-results.md)

for broader stack-output trade-off assessment.
