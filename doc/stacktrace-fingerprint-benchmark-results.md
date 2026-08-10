# StacktraceFingerprintBenchmark Results

This document summarizes benchmark results for [logback/src/test/java/hr/hrg/dialog/logback/StacktraceFingerprintBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/StacktraceFingerprintBenchmark.java), focused on two operations together:

- stacktrace serialization into JSON
- deterministic fingerprint generation

It compares three approaches:

- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java)
- `Throwable.printStackTrace(...)` into an in-memory string buffer (`StringWriter`) and then writing that string to JSON

## Benchmark context

The benchmark methods are:

- `writeWithJsonLogWriter()`
  - Uses `JsonLogWriter.writeJsonEvent(...)` (includes `errHash` and optimized stack writer path).
- `writeWithJsonLogWriterClassic()`
  - Uses `JsonLogWriterClassic.writeJsonEvent(...)` (includes `errHash` and generator-driven path).
- `writeWithPrintStackTraceStringWriter()`
  - Computes `errHash` with `JavaStackSanitizerLogback.fingerprint(...)`.
  - Captures stacktrace with `Throwable.printStackTrace(PrintWriter(new StringWriter(...)))`.
  - Writes that full string into JSON (`stack` field).

Noise control:

- All variants write to reusable in-memory `ByteArrayOutputStream` buffers.
- No file I/O or console I/O is used during measurement.
- The throwable is prebuilt once per benchmark thread.

## Run details

Environment and command:

- JDK: `25.0.3`
- JMH: `1.37`
- GC profiler: `-prof gc`
- Warmup: `3 x 1s`
- Measurement: `5 x 1s`
- Forks: `1`

Run command:

```text
java --add-opens java.base/java.lang=ALL-UNNAMED -cp <classpath> org.openjdk.jmh.Main hr.hrg.dialog.logback.StacktraceFingerprintBenchmark.* -prof gc -wi 3 -i 5 -f 1
```

Raw output captured to [bench-stacktrace-fingerprint-output.txt](bench-stacktrace-fingerprint-output.txt).

## Measured results

Average-time mode is the most direct per-log-line comparison.

| Benchmark                                 | Avg time       | Throughput     | Alloc norm       | GC count | GC time |
| ----------------------------------------- | -------------- | -------------- | ---------------- | -------- | ------- |
| `writeWithJsonLogWriter`                  | `11.786 us/op` | `0.086 ops/us` | `2216.082 B/op`  | `1`      | `3 ms`  |
| `writeWithJsonLogWriterClassic`           | `11.960 us/op` | `0.084 ops/us` | `2496.083 B/op`  | `1`      | `2 ms`  |
| `writeWithPrintStackTraceStringWriter`    | `5.675 us/op`  | `0.178 ops/us` | `20472.039 B/op` | `24`     | `21 ms` |

Notes:

- `GC count` and `GC time` are totals across measured iterations (not per operation).
- `Alloc norm (B/op)` is the best indicator of per-call allocation pressure.

## What the numbers tell us

### 1. printStackTrace-to-String is faster here, but dramatically more allocation-heavy

In this run, `writeWithPrintStackTraceStringWriter` is about `2.1x` faster than both writer-based paths in average-time mode.

But allocation cost is much higher:

- ~`20.5 KB/op` for printStackTrace-string path
- ~`2.2 KB/op` to `2.5 KB/op` for JsonLogWriter / JsonLogWriterClassic

That is roughly `8x-9x` higher allocation per operation for the printStackTrace path.

### 2. GC pressure is much higher with printStackTrace string capture

The string-based path shows significantly higher GC activity during the same measurement window:

- GC count total: `24` vs `1`
- GC time total: `21 ms` vs `2-3 ms`

This is expected because `printStackTrace` builds a large intermediate character buffer/string before JSON writing.

### 3. JsonLogWriter vs JsonLogWriterClassic remain close in CPU, with lower allocation for JsonLogWriter

Between the two structured writers:

- CPU performance is close (`11.786` vs `11.960 us/op`).
- `JsonLogWriter` allocates less (`2216 B/op` vs `2496 B/op`, about 11% lower).

So even when CPU is similar, the direct writer still gives lower steady-state memory churn.

## Why this trade-off can happen

The benchmark reveals a non-obvious trade-off:

- `printStackTrace(StringWriter)` can be CPU-efficient in this microbenchmark, likely due to highly optimized JDK internals for formatting stacktrace text.
- But that efficiency comes with a large temporary-string allocation footprint that increases GC pressure.
- The structured writer paths do more controlled/sanitized formatting work and can be slower per call, but they avoid very large transient string objects.

In practical systems, allocation pressure often matters as much as raw single-call speed because it affects tail latency and GC behavior under load.

## Practical implications

- If your priority is minimizing allocation churn and GC overhead under sustained high log volume, the structured writers (`JsonLogWriter` / `JsonLogWriterClassic`) are safer choices.
- If your priority is minimum CPU per call and you can tolerate much higher temporary allocation, the `printStackTrace`-string path may look attractive in isolated microbenchmarks.
- For production decisions, run a macro-level load test (multiple threads, realistic exception rates, and your target GC) because GC pressure often dominates at scale.

## Closer profiling breakdown

To explain why `printStackTrace` appears faster despite much higher allocation, the benchmark was extended with component-level methods and run in `avgt` mode with GC profiling.

Run artifact:

- [bench-stacktrace-fingerprint-deep-avgt.json](bench-stacktrace-fingerprint-deep-avgt.json)

### Component-level numbers (average time)

| Component method                         | Avg time      | Alloc norm       | GC count | GC time |
| ---------------------------------------- | ------------- | ---------------- | -------- | ------- |
| `eventGetThrowableProxyOnly`             | `0.001 us/op` | `~0 B/op`        | `0`      | `0 ms`  |
| `eventGetFormattedMessageOnly`           | `0.000 us/op` | `~0 B/op`        | `0`      | `0 ms`  |
| `fingerprintOnly`                        | `0.630 us/op` | `1280.004 B/op`  | `17`     | `21 ms` |
| `printStackTraceToStringOnly`            | `2.160 us/op` | `16488.015 B/op` | `34`     | `29 ms` |
| `proxyToStackTraceArrayOnly`             | `0.022 us/op` | `88.000 B/op`    | `26`     | `22 ms` |
| `sanitizedStackWriteOnly`                | `0.676 us/op` | `88.005 B/op`    | `1`      | `2 ms`  |
| `writeWithJsonLogWriter`                 | `11.799 us/op`| `2216.082 B/op`  | `1`      | `2 ms`  |
| `writeWithJsonLogWriterClassic`          | `11.936 us/op`| `2496.083 B/op`  | `1`      | `3 ms`  |
| `writeWithPrintStackTraceStringWriter`   | `5.650 us/op` | `20472.039 B/op` | `24`     | `21 ms` |

### What this deeper profile suggests

1. The speed lead of `writeWithPrintStackTraceStringWriter` does not come from lower memory work.
  - It allocates roughly `20 KB/op`, almost an order of magnitude above structured writers.

2. The direct `printStackTrace` string build itself is relatively fast in CPU terms for this micro-case (`2.160 us/op`), likely due to highly optimized JDK text formatting internals.

3. In the structured paths, the dominant cost is not basic event field access.
  - `eventGetThrowableProxyOnly` and `eventGetFormattedMessageOnly` are effectively free in this benchmark.
  - The expensive part is the full structured throwable+JSON pipeline (`~11.8-11.9 us/op` total).

4. Fingerprinting is non-trivial (`0.630 us/op`, `1280 B/op`) and is shared by all variants in this benchmark design.
  - It is a meaningful part of cost, but it does not by itself explain the entire gap.

5. The observed trade-off is therefore:
  - `printStackTrace` path: lower CPU time here, much higher allocation and GC churn.
  - Structured writer paths: higher CPU time here, much lower allocation pressure and GC activity.

### Recommendation for next profiling pass

To pin down the remaining structured-writer CPU cost even further, add one more benchmark variant that emits exactly the same stack payload in both paths (same textual format), then compare:

- string production cost only
- JSON escaping/encoding cost only
- full end-to-end cost

That controls for payload-shape differences and will isolate whether most extra CPU is in sanitization logic, string extraction strategy, or JSON emission.

## Controlled payload-shape experiment

A follow-up run normalized JSON field writing so both variants use the same JSON emission method (`writeSharedJsonFields(...)`) and differ mostly in stack string generation source.

Run artifact:

- [bench-stacktrace-controlled-avgt.json](bench-stacktrace-controlled-avgt.json)

### Controlled results (average time)

| Method                                             | Avg time      | Alloc norm       | GC count | GC time |
| -------------------------------------------------- | ------------- | ---------------- | -------- | ------- |
| `sanitizedStackToStringOnly`                       | `0.556 us/op` | `5488.004 B/op`  | `44`     | `39 ms` |
| `printStackTraceToStringOnly`                      | `2.099 us/op` | `16488.015 B/op` | `35`     | `31 ms` |
| `writeWithSanitizedStackStringWriterControlled`    | `3.314 us/op` | `7320.023 B/op`  | `18`     | `21 ms` |
| `writeWithPrintStackTraceStringWriterControlled`   | `5.800 us/op` | `20704.041 B/op` | `24`     | `22 ms` |
| `writeWithJsonLogWriter`                           | `11.679 us/op`| `2216.081 B/op`  | `1`      | `2 ms`  |
| `writeWithJsonLogWriterClassic`                    | `12.223 us/op`| `2496.085 B/op`  | `1`      | `2 ms`  |

### Revised conclusion after control

The earlier observation that `printStackTrace` was faster does not hold once payload writing is normalized.

1. For string generation alone, sanitized stack string creation is significantly faster than `printStackTrace` (`0.556` vs `2.099 us/op`) and allocates much less (`5.5 KB/op` vs `16.5 KB/op`).

2. For controlled end-to-end JSON writing, sanitized string path is also faster (`3.314` vs `5.800 us/op`) and allocates less (`7.3 KB/op` vs `20.7 KB/op`).

3. This indicates the original `writeWithPrintStackTraceStringWriter` advantage came from comparing non-equivalent pipelines, not from `printStackTrace` being inherently superior for this use case.

In short: when both paths are made comparable, `printStackTrace` is slower and creates much more GC pressure.
