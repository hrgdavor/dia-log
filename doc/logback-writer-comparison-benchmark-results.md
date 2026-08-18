# Logback Writer Comparison Benchmark Results

This document reports the head-to-head benchmark of the three logback writing paths:

- [logback/src/test/java/hr/hrg/dialog/logback/LogbackWriterComparisonBenchmark.java](logback/src/test/java/hr/hrg/dialog/logback/LogbackWriterComparisonBenchmark.java)

| Variant | What it is |
|---|---|
| `defaultPatternLog` | Stock logback `PatternLayoutEncoder` with the default console pattern (`%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`, plus `%ex` when a throwable is attached) |
| `optimizedJsonLog` | Production `JsonLogWriter` — direct `OutputStream` writer, pre-encoded keys, zero-allocation fast paths |
| `jacksonEncoderLog` | `JsonLogWriterClassic` — Jackson `JsonGenerator`-based encoder, used purely as a testing baseline for "a Jackson encoder" |

All three write the **same** `LoggingEvent` (timestamp, level, logger, thread, message with
arguments, a small MDC map, two statement key/value pairs, optional throwable), so the
comparison isolates the serialization strategy.

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
- Fast paths with `--add-opens java.base/java.lang=ALL-UNNAMED`

## Latest results

### Average time (`avgt`, us/op) and throughput (`thrpt`, ops/us)

| Variant | without trace | with trace |
|---|---|---|
| `defaultPatternLog` | 0.125 us/op — 7.564 ops/us | 1.566 us/op — 0.955 ops/us |
| `optimizedJsonLog` | 0.490 us/op — 2.034 ops/us | 5.937 us/op — 0.162 ops/us |
| `jacksonEncoderLog` | 0.632 us/op — 1.431 ops/us | 6.430 us/op — 0.156 ops/us |

### Allocation (`gc.alloc.rate.norm`, B/op)

| Variant | without trace | with trace |
|---|---|---|
| `defaultPatternLog` | 728 B/op | 11264–15392 B/op (thrpt/avgt runs) |
| `optimizedJsonLog` | 208 B/op | 208 B/op |
| `jacksonEncoderLog` | 696 B/op | 784 B/op |

## Conclusions

1. **The optimized JSON writer is allocation-immune to traces: 208.003 → 208.042 B/op.**
   The throwable branch adds ~0 B/op per event because the benchmark (like production)
   materializes the throwable and its `ThrowableProxy` once, and `JsonLogWriter` streams
   the cached proxy array through the single-pass write+fingerprint — no `getStackTrace()`
   clone, no per-frame `StackTraceElement[]`, no hasher allocation. The 208 B/op floor is
   the shared MDC+KV processing (`allKeys` dedup set, MDC `entrySet()` iteration).

2. **The default pattern encoder is the allocation hotspot, and it explodes with traces:**
   728 B/op without a trace (date rendering, `%logger{36}` abbreviation, per-event
   `byte[]`), but **11264–15392 B/op with one** — rendering the stack trace via `%ex`
   builds the full trace string and intermediate objects per event (~15–20× the no-trace
   cost). On a GC-pressure-sensitive hot path, every traced event through the default
   encoder costs ≈ 12–15 KB of garbage.

3. **Speed vs. allocation trade-off is inverted between the default encoder and the JSON
   writers.** Without a trace the default pattern encoder is the fastest (0.125 us/op —
   it only formats ~75 chars) while allocating 3.5× more than the optimized writer.
   With a trace the default encoder is also faster than both JSON writers (1.566 vs
   5.937/6.430 us/op) but allocates **50–70× more**. The JSON writers spend CPU on
   escaping and direct byte emission; the default encoder spends allocation on string
   building.

4. **The optimized writer beats the Jackson-based encoder in both dimensions:** 208 vs
   696–784 B/op (3–4× less) at comparable CPU cost with traces (5.937 vs 6.430 us/op),
   and 0.490 vs 0.632 us/op without. The Jackson `JsonGenerator` path pays for generator
   state, intermediate buffers, and per-field machinery.

## Where the time goes — why `optimizedJsonLog` is slower than `defaultPatternLog`

The optimized writer is allocation-faster but CPU-slower than the pattern encoder.
Decomposing the time budget (same event, warmed `nanoTime` micro-measurements on the
same JDK/machine; trace = 4 frames in the micro-shape):

| Component | measured cost |
|---|---|
| bulk `write(byte[])` of 133 bytes | 10.6 ns |
| same 133 bytes as **133 × `write(int)`** | **543.4 ns (≈51× slower)** |
| escaped write of the 48-char message (`EscapedJsonStringWriter`, batched segments) | 40.7 ns |
| raw `StringByteExtractor` strategy write of the same message (`writeLatin1`, per byte) | 257.0 ns |
| fixed JSON fields `ts..msg` (replica of the writer's fixed-field section) | 167.9 ns |
| full JSON event, plain (no MDC/KV/trace) | 181.1 ns |
| full JSON event, with MDC+KV | 377.9 ns |
| single-pass stack write+fingerprint (4 frames) | 324.2 ns |
| of which: write only | 254.1 ns |
| of which: fingerprint only (hash) | 101.3 ns |

### Findings

1. **The stack path writes one byte at a time — that is the dominant avoidable cost.**
   The single-pass stack writer emits each frame's class/method bytes through
   `StringByteExtractor.writeLatin1`, which loops `out.write(int)` **per byte** (543 ns
   per 133 bytes vs 10.6 ns for one bulk write — a ≈51× penalty). With a trace, this is
   paid for every frame × (class + dot + method). The fingerprint itself is cheap
   (101 ns / 4 frames); it is not the bottleneck.

2. **`EscapedJsonStringWriter` already batches** — its VarHandle fast path
   (`writeEscapedLatin1`) writes contiguous ASCII runs as bulk `write(byte[], off, len)`
   (40.7 ns for the message), which is why the fixed JSON fields are not the problem
   either (167.9 ns for all five).

3. **Output volume multiplies the write-call cost.** JSON emits ~2.2× more bytes than
   the default pattern (quotes, commas, field names, plus MDC/KV fields), and every one
   of those bytes goes through a write call — so both the count and the granularity
   work against the JSON writer.

4. **MDC+KV processing adds ~200 ns** (the `allKeys` dedup `HashSet`, MDC `entrySet()`
   iteration, and the extra escaped key/value writes) — the pattern encoder renders none
   of it.

5. The pattern encoder wins on CPU because its output is short (~100 B) and it performs
   **one** bulk `getBytes()` + **one** bulk write, appending the trace to its
   `StringBuilder` in bulk too — at the price of the 728 → ~12–15 KB/op allocation.

### Implication

Batching the ASCII segments in `StringByteExtractor.writeLatin1` the same way
`EscapedJsonStringWriter.writeEscapedLatin1` already does (write a contiguous run with
one `write(byte[], off, len)` call instead of per-byte `write(int)`) would remove the
dominant trace-path cost: class/method names are effectively always ASCII, so each frame's
string becomes a single bulk write. The trace event cost should then drop toward the
no-trace + hash/write-remainder budget rather than the current per-byte-write multiple.

## Current recommendation

- For low-allocation, low-GC-pressure paths (high throughput servers, structured logs),
  use `optimizedJsonLog` — its per-event allocation is constant and small (208 B/op)
  regardless of whether a throwable is attached.
- The default pattern encoder is the right choice only where raw single-event latency
  matters more than allocation, and traced events are rare; note that every traced event
  through the default encoder emits ~12–15 KB of garbage.
- The Jackson-based encoder (`jacksonEncoderLog`) serves as the reference/baseline
  "a Jackson encoder" path; it is dominated by the optimized writer on allocation and
  is no faster on CPU.
