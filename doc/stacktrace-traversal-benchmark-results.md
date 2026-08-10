# StackTraceTraversalBenchmark Results

This document summarizes the latest benchmark results for `core/src/test/java/hr/hrg/dialog/core/StackTraceTraversalBenchmark.java` and explains the relative behavior of the five measured methods.

## Benchmark context

The benchmark class contains these methods:

- `benchmarkThrowableStackTraceArray()`
  - Traverses `Throwable.getStackTrace()` and hashes class and method names using `String.hashCode()`.
- `benchmarkThrowableStackTraceArrayWyhashZeroAlloc()`
  - Traverses `Throwable.getStackTrace()` and hashes the same strings using `Wyhash64.Streaming.update(String)`.
- `benchmarkThrowableStackTraceArrayWyhashFallback()`
  - Traverses `Throwable.getStackTrace()` and hashes the same names using `Wyhash64.Streaming.update(byte[])` after `getBytes(StandardCharsets.UTF_8)`.
- `benchmarkStackWalkerEAFriendly()`
  - Uses `StackWalker.walk()` with a stream pipeline and `mapToInt(...).sum()`.
- `benchmarkStackWalkerNonEAFriendly()`
  - Uses `StackWalker.walk()` with `forEach(...)` and a mutable accumulator array.

## Run details

The benchmark was executed with JMH under JDK 25 and GC profiling enabled. The full run command was:

```text
java --add-opens java.base/java.lang=ALL-UNNAMED -cp <classpath> org.openjdk.jmh.Main hr.hrg.dialog.core.StackTraceTraversalBenchmark.* -prof gc -wi 3 -i 5 -f 1
```

The output was saved to `bench-stackwalker-output.txt`.

## Measured results

This benchmark run was corrected so that `Wyhash64.Streaming` is created once per stack trace and then updated with each frame.

| Benchmark                                          | Avg time      | Throughput     | Alloc norm      | GC count | GC time |
| -------------------------------------------------- | ------------- | -------------- | --------------- | -------- | ------- |
| `benchmarkThrowableStackTraceArray`                | `3.076 us/op` | `0.327 ops/us` | `1496.022 B/op` | `4`      | `5 ms`  |
| `benchmarkStackWalkerEAFriendly`                   | `4.091 us/op` | `0.290 ops/us` | `2640.030 B/op` | `5`      | `8 ms`  |
| `benchmarkStackWalkerNonEAFriendly`                | `3.456 us/op` | `0.284 ops/us` | `2576.025 B/op` | `6`      | `9 ms`  |
| `benchmarkThrowableStackTraceArrayWyhashFallback`  | `3.801 us/op` | `0.257 ops/us` | `3032.027 B/op` | `7`      | `10 ms` |
| `benchmarkThrowableStackTraceArrayWyhashZeroAlloc` | `4.059 us/op` | `0.248 ops/us` | `1632.028 B/op` | `3`      | `4 ms`  |

> Notes: the average-time mode results are the most natural comparison here. Throughput and GC norms are consistent with the same ranking.

## What the numbers tell us

### 1. `benchmarkThrowableStackTraceArray` is currently the fastest and lowest-allocation path

- It completes in `3.368 us/op`, which is ~15% faster than the best `StackWalker` variant and ~22-28% faster than the WyHash variants.
- It also allocates only `~1.5 KB/op`, which is roughly half the allocation of `benchmarkStackWalkerEAFriendly` and one third of `benchmarkThrowableStackTraceArrayWyhashFallback`.

Why? Because `String.hashCode()` on stack frame names is cheap and often reuses cached hash values already stored on the `String` object.

### 2. `StackWalker` variants are slightly slower than the direct stack trace path

- `benchmarkStackWalkerEAFriendly` is marginally faster than `benchmarkStackWalkerNonEAFriendly`.
- Both allocate about `2.6 KB/op`, which is higher than the direct stack trace path because of `StackWalker` frame objects and stream infrastructure.
- `StackWalker.walk()` is already building a stream abstraction, and the JMH measurement includes the cost of the Java stream pipeline and lambda invocation overhead.
- The difference between the two `StackWalker` variants is within noise, so the pipeline-vs-forEach choice is not a strong performance factor here.

This means `StackWalker` is not yet winning on raw speed or allocation for this simple frame-hashing use case.

### 3. `WyHash` variants are slower than direct string hashing but much better behaved when they are used correctly

- `benchmarkThrowableStackTraceArrayWyhashFallback` now measures the intended use case: one `Wyhash64.Streaming` instance for the whole stack trace. It costs `3.801 us/op`.
- `benchmarkThrowableStackTraceArrayWyhashZeroAlloc` also uses one streaming instance for the whole trace and costs `4.059 us/op`.
- The fallback variant now allocates `3.0 KB/op`, while the zero-alloc variant allocates `1.6 KB/op`.

This shows the UTF-8 fallback path is still more expensive than the string path, but the overhead is lower than the original per-frame streaming version.

The important semantic difference is that the string path is effectively a per-frame hash, while the WyHash path is a single hash of the entire trace.

- `String.hashCode()` is fast here because it is operating on already-existing `String` values and can reuse cached hash codes inside the `String` objects.
- That makes the string path attractive for per-frame work, but it does not automatically produce a single canonical fingerprint for the whole trace.
- To use per-frame string hashes for deduplication, you must combine them yourself (for example with `31*acc + frameHash`). That combination is order-sensitive, harder to reason about, and more difficult to verify than a single streaming hash.
- A single `Wyhash64.Streaming` pass over the full trace treats the trace as one ordered sequence of values. This is semantically stronger for deduplication and trace fingerprinting because it directly encodes frame order and content into one hash.

Both WyHash paths are now measuring the cumulative hash of the entire trace, which is the correct semantic intent for deduplication or trace fingerprinting.

## Comparing WyHash and classic `String.hashCode()`

The benchmark confirms an important point:

- Classic `String.hashCode()` is likely faster in this scenario because the input is already `String` data.
- `String.hashCode()` benefits from cached values inside the `String` object and avoids extra encoding or streaming overhead.
- By contrast, WyHash is doing more work to produce one full-trace fingerprint instead of a per-frame value.
- That means `String.hashCode()` is a good low-cost path for direct frame-name hashing, but it is not as robust for canonical trace deduplication.

So for hashing `StackTraceElement` names directly, the JVM-optimized `String.hashCode()` path is the better choice. For producing a single trace fingerprint that is easy to verify and collision-resistant, the WyHash path is the stronger semantic match.

## Interpreting `Wyhash64.Streaming` labels

The term “zero alloc” in `benchmarkThrowableStackTraceArrayWyhashZeroAlloc()` refers to the string-to-byte extraction path, not the entire benchmark.

- The benchmark still allocates due to the `Throwable` stack trace capture.
- It now creates one `Wyhash64.Streaming` instance per stack trace, which is the correct semantic model for a single trace fingerprint.
- The zero-alloc variant simply avoids the explicit UTF-8 `byte[]` allocation.

## Practical implications

- Use `benchmarkThrowableStackTraceArray` style hashing when the input is already `String` names and you want the lowest latency and lowest allocation.
- If you need content-based or byte-oriented hashing for deduplication, WyHash is still useful, but expect a performance cost.
- The fallback path (`getBytes(StandardCharsets.UTF_8)`) is the most expensive WyHash variant and should be avoided unless byte-array input is required.
- The StackWalker API is more ergonomic and can be used safely, but it does not outperform direct array traversal in this microbenchmark.
