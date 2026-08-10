# JsonLogWriter Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java)

Historical optimization timeline, previous runs, and step-by-step gains are tracked in:

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

- [bench-json-log-writer-latest-output.txt](bench-json-log-writer-latest-output.txt)
- [bench-json-log-writer-latest.json](bench-json-log-writer-latest.json)

## Latest results

| Benchmark method              | includeThrowable | Avg time    | Throughput   | Alloc norm    |
| ----------------------------- | ---------------- | ----------- | ------------ | ------------- |
| writeWithJsonLogWriter        | false            | 0.540 us/op | 1.840 ops/us | 344.004 B/op  |
| writeWithJsonLogWriterClassic | false            | 0.667 us/op | 1.505 ops/us | 784.005 B/op  |
| writeWithJsonLogWriter        | true             | 1.898 us/op | 0.531 ops/us | 480.013 B/op  |
| writeWithJsonLogWriterClassic | true             | 2.194 us/op | 0.458 ops/us | 1040.015 B/op |

## Current interpretation

1. JsonLogWriter is faster than JsonLogWriterClassic in both throwable and non-throwable cases.
2. JsonLogWriter allocates less than JsonLogWriterClassic across both parameter values.
3. Throwable inclusion remains the dominant cost multiplier for both implementations.

## Current recommendation

Use JsonLogWriter as the default high-throughput path, and continue validating throwable-heavy behavior with this benchmark after stack/fingerprint changes.
