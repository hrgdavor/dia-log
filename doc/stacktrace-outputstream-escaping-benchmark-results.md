# StacktraceOutputStreamEscapingBenchmark Results

This document summarizes benchmark results for [logback/src/test/java/hr/hrg/dialog/logback/StacktraceOutputStreamEscapingBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/StacktraceOutputStreamEscapingBenchmark.java).

The goal is to compare stacktrace JSON-string emission into an OutputStream using:

- optimized writer path with JSON-escaped newlines
- printStackTrace string capture followed by optimized JSON string escaping

## Compared methods

- optimizedOutputStreamEscapedNewlines
  - Uses [core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java](core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java) `addFromTraceToOutputStreamJson(...)`.
  - Writes directly into an in-memory OutputStream with escaped newline bytes.
- printStackTraceThenEscapedJsonStringWriter
  - Uses `Throwable.printStackTrace(PrintWriter(StringWriter))` to build stacktrace text.
  - Then uses [core/src/main/java/hr/hrg/dialog/core/EscapedJsonStringWriter.java](core/src/main/java/hr/hrg/dialog/core/EscapedJsonStringWriter.java) `writeJsonStringOrNull(...)` to emit JSON-safe text.

## Run details

- JDK: `25.0.3`
- JMH: `1.37`
- Modes: throughput and average-time
- Warmup: `3 x 1s`
- Measurement: `5 x 1s`
- Forks: `1`
- GC profiler: `-prof gc`

Artifacts:

- Raw output: [bench-stacktrace-outputstream-escaping-output.txt](bench-stacktrace-outputstream-escaping-output.txt)
- JSON report: [bench-stacktrace-outputstream-escaping.json](bench-stacktrace-outputstream-escaping.json)

## Measured results

| Benchmark method                             | Avg time      | Throughput     | Alloc norm        | GC count | GC time |
| -------------------------------------------- | ------------- | -------------- | ----------------- | -------- | ------- |
| `optimizedOutputStreamEscapedNewlines`       | `0.441 us/op` | `1.068 ops/us` | `88.003 B/op`     | `1`      | `2 ms`  |
| `printStackTraceThenEscapedJsonStringWriter` | `6.941 us/op` | `0.161 ops/us` | `18832.048 B/op`  | `21`     | `24 ms` |

## Interpretation

1. The optimized OutputStream path is substantially faster.
- Average-time: about `15.7x` faster (`6.941 / 0.441`).
- Throughput: about `6.6x` higher (`1.068 / 0.161`).

2. The printStackTrace + escaped-string path allocates dramatically more memory.
- `18832 B/op` vs `88 B/op`.
- About `214x` higher allocation per operation.

3. GC pressure is much higher in the printStackTrace path.
- GC count and total GC time are both significantly elevated in the measurement window.

4. For JSON logging workloads, direct stacktrace emission with escaped newlines is the clear winner for both latency and allocation behavior.

## Practical takeaway

If the goal is to emit stacktrace content into JSON output streams with minimal CPU and GC overhead, prefer the optimized direct writer path (`addFromTraceToOutputStreamJson`) over the printStackTrace-to-string pipeline.
