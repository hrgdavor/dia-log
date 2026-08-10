# JsonLogWriterBenchmark Results

This document summarizes benchmark results for [logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java) and explains the relative behavior of [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java) versus [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java).

## Benchmark context

The benchmark class contains two measured methods:

- `writeWithJsonLogWriter(Blackhole)`
  - Uses `JsonLogWriter.writeJsonEvent(ObjectMapper, ILoggingEvent, OutputStream)`.
  - Writes directly to a reusable in-memory output stream.
- `writeWithJsonLogWriterClassic(Blackhole)`
  - Uses `JsonLogWriterClassic.writeJsonEvent(JsonGenerator, ILoggingEvent, OutputStream)`.
  - Uses Jackson `JsonGenerator` on the same reusable in-memory output stream.

Both methods:

- Reuse one `ByteArrayOutputStream`-backed buffer per JMH thread and call `reset()` per invocation.
- Reuse one prepared `LoggingEvent` per thread with:
  - Message template and args.
  - MDC values.
  - Structured key-value pairs.
  - Optional throwable (controlled by `@Param includeThrowable`).
- Consume output size/checksum via `Blackhole` to avoid dead-code elimination.

This setup intentionally reduces I/O noise and focuses measurement on serializer behavior and allocation patterns.

## Run details

Environment and command:

- JDK: `25.0.3`
- JMH: `1.37`
- Mode: `Throughput` and `AverageTime`
- Warmup: `3 x 1s`
- Measurement: `5 x 1s`
- Forks: `1`
- GC profiler: `-prof gc`

Run command:

```text
java --add-opens java.base/java.lang=ALL-UNNAMED -cp <classpath> org.openjdk.jmh.Main hr.hrg.dialog.logback.JsonLogWriterBenchmark.* -prof gc -wi 3 -i 5 -f 1
```

Raw output captured to [bench-json-log-writer-output.txt](bench-json-log-writer-output.txt).

## Measured results

Average-time numbers are the easiest direct comparison for per-log-line cost.

| Benchmark                                  | includeThrowable | Avg time      | Throughput     | Alloc norm      | GC count | GC time |
| ------------------------------------------ | ---------------- | ------------- | -------------- | --------------- | -------- | ------- |
| `writeWithJsonLogWriter`                   | `false`          | `0.509 us/op` | `2.056 ops/us` | `344.004 B/op`  | `6`      | `10 ms` |
| `writeWithJsonLogWriterClassic`            | `false`          | `0.692 us/op` | `1.377 ops/us` | `816.005 B/op`  | `9`      | `16 ms` |
| `writeWithJsonLogWriter`                   | `true`           | `2.122 us/op` | `0.468 ops/us` | `1672.015 B/op` | `6`      | `10 ms` |
| `writeWithJsonLogWriterClassic`            | `true`           | `2.414 us/op` | `0.430 ops/us` | `2176.017 B/op` | `8`      | `14 ms` |

Notes:

- `GC count` and `GC time` are profiler totals over measured iterations, not per-operation metrics.
- `Alloc norm (B/op)` is the best direct allocation comparison.

## What the numbers tell us

### 1. JsonLogWriter is faster in both scenarios

Without throwable:

- `JsonLogWriter` is about 26% faster in average time (`0.509` vs `0.692 us/op`).
- Throughput is about 49% higher (`2.056` vs `1.377 ops/us`).

With throwable:

- `JsonLogWriter` is about 12% faster in average time (`2.122` vs `2.414 us/op`).
- Throughput is about 9% higher (`0.468` vs `0.430 ops/us`).

### 2. JsonLogWriter allocates substantially less

Without throwable:

- `344 B/op` vs `816 B/op` (about 58% lower allocation for `JsonLogWriter`).

With throwable:

- `1672 B/op` vs `2176 B/op` (about 23% lower allocation for `JsonLogWriter`).

This aligns with the direct-byte writing approach and reduced Jackson generator overhead on common fields.

### 3. Throwable path dominates total cost for both writers

Both implementations slow down and allocate more when throwable is included:

- Time increases by roughly `4x` for each writer.
- Allocation rises by about `1.3 KB/op` (`JsonLogWriter`) to `1.36 KB/op` (`Classic`).

This indicates stack-trace serialization is the primary hot path once exceptions are part of the event.

### 4. In-memory buffering keeps the comparison focused

Because both methods write to the same reusable memory buffer:

- No file-system or console latency contaminates the results.
- Differences mostly reflect serialization strategy, escaping/writing path, and temporary object creation.

## Practical implications

- For high-volume JSON logging, `JsonLogWriter` currently gives better latency and lower allocation pressure.
- When most log lines do not carry exceptions, the gain is larger.
- For exception-heavy workloads, `JsonLogWriter` still wins, but stack-trace formatting dominates total overhead, so additional optimization should target throwable serialization first.
- Keep this benchmark in CI/perf regression checks, especially around changes in:
  - stack trace writing,
  - key-value/MDC handling,
  - and string escaping/encoding paths.
