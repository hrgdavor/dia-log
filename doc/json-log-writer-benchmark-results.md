# JsonLogWriter Benchmark Results (Latest State)

This document reports only the latest benchmark state for:

- [logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterBenchmark.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriterClassic.java)

Historical optimization timeline, previous runs, and step-by-step gains are tracked in:

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

- [bench-json-log-writer-latest-output.txt](bench-json-log-writer-latest-output.txt)
- [bench-json-log-writer-latest.json](bench-json-log-writer-latest.json)

## Latest results

| Benchmark method              | includeThrowable | Avg time    | Throughput   | Alloc norm    |
| ----------------------------- | ---------------- | ----------- | ------------ | ------------- |
| writeWithJsonLogWriter        | false            | 0.507 us/op | 2.069 ops/us | 272.004 B/op  |
| writeWithJsonLogWriterClassic | false            | 0.610 us/op | 1.662 ops/us | 784.004 B/op  |
| writeWithJsonLogWriter        | true             | 5.706 us/op | 0.169 ops/us | 272.039 B/op  |
| writeWithJsonLogWriterClassic | true             | 6.218 us/op | 0.162 ops/us | 872.043 B/op  |

Compared with the previous run, the optimized writer's allocation dropped from
344 → 272 B/op (no throwable) and 480 → 272 B/op (throwable), and the classic
writer's throwable path from 1040 → 872 B/op — the fingerprint entry points now
reuse a caller-owned hasher (see `doc/allocation-benchmark-results.md`), so no
`Wyhash64.Streaming` is allocated per event.

### Why throwable costs ≈ 0 B/op here

`writeWithJsonLogWriter` measures 272.004 B/op without a throwable and 272.039 B/op
with one — the throwable branch adds nothing. This is by design, not a measurement
artifact:

- The benchmark builds the throwable and its `ThrowableProxy` **once in `@Setup`**,
  outside the measured loop. The unavoidable trace costs (the `getStackTrace()`
  defensive clone, proxy construction, the cached `StackTraceElementProxy[]`) are
  paid exactly once per benchmark, not per event — exactly like production, where
  the throwable and its proxy already exist when the appender writes the event.
- Per measured event, `JsonLogWriter` reads the **cached** proxy array and streams it
  through the single-pass write+fingerprint: no `getStackTrace()` call, no per-frame
  `StackTraceElement[]` conversion, no hasher allocation (caller-owned field).
- The 272 B/op floor is the shared MDC+KV processing (the `allKeys` dedup `HashSet`
  built because MDC and KV pairs coexist, plus MDC `entrySet()` iteration) — identical
  with or without a throwable.

Contrast: `writeWithJsonLogWriterClassic` shows exactly **+88 B/op** for throwable
(784.004 → 872.043) — its per-event `new StackTraceElement[18]` conversion array
(16-byte header + 4 bytes/frame). The optimized writer avoids even that. The 88 B/op
`getStackTrace()` clone only appears in the core `fingerprint(Throwable, …)` API,
which `JsonLogWriter` never calls per event.

## Current interpretation

1. JsonLogWriter is faster than JsonLogWriterClassic in both throwable and non-throwable cases.
2. JsonLogWriter allocates less than JsonLogWriterClassic across both parameter values.
3. Throwable inclusion remains the dominant cost multiplier for both implementations.

## Current recommendation

Use JsonLogWriter as the default high-throughput path, and continue validating throwable-heavy behavior with this benchmark after stack/fingerprint changes.
