# 07 — Int/Long Number Writing: Direct Buffer, VarHandle Stores, and Allocations

This guide explains how `int`/`long` values are written directly into the output buffer without intermediate scratch buffers or `System.arraycopy` operations. It compares multiple Java idiomatic ways of writing numbers and analyzes their performance and allocation characteristics.

## The Problem with Naive Number Writing

The traditional approach to writing numbers involves:

```java
// Old approach: build in scratch, then copy
byte[] scratch = new byte[20];
int len = JsonNumberWriter.buildLong(scratch, value);
System.arraycopy(scratch, 20 - len, buf, pos, len);
```

**Costs:**
- Scratch buffer allocation (even if caller-owned)
- Right-to-left digit building loop
- `System.arraycopy` for every number

This pattern touches two memory regions per number and prevents the JIT from optimizing the hot path.

## What Dia-Log Does

```java
// New approach: write directly at offset
int newPos = JsonNumberWriter.writeInt(buf, pos, value);
```

**Benefits:**
- No scratch buffer
- Left-to-right digit emission
- One `VarHandle` store per 4-digit group
- No `System.arraycopy`

## Multiple Java Idioms for Int/Long Writing

### 1. JDK `Integer.toString()` / `Long.toString()` (Baseline)

The standard JDK approach uses a right-to-left digit extraction with a buffer and then reverses the result into a `String`, followed by UTF-8 encoding.

```java
byte[] b = Integer.toString(value).getBytes(StandardCharsets.UTF_8);
byte[] b = Long.toString(value).getBytes(StandardCharsets.UTF_8);
```

**Characteristics:**
- Allocates a `String` + `byte[]` (typically 40–50 B/op)
- Simple, readable code
- Slowest option (4–16× slower than optimized paths)

### 2. `String.format("%d", ...)` (Alternative)

An alternative using `String.format` which is significantly slower:

```java
byte[] b = String.format("%d", value).getBytes(StandardCharsets.UTF_8);
```

**Characteristics:**
- Allocates a `String` + `byte[]`
- Slower than `Integer.toString()` due to format parsing (~50% overhead)
- Useful for demonstrating format overhead

### 3. `JsonNumberWriter.writeInt/writeLong` (Production Path)

The production implementation uses:
- Precomputed `DIGIT_QUADS` and `DIGIT_TRIPLES` tables
- Left-to-right digit emission via `POW10` slicing
- Little-endian `LE_INT` VarHandle stores for 4-digit groups
- Special cases for `Integer.MIN_VALUE` and `Long.MIN_VALUE` as constants

**Characteristics:**
- Uses hardware division (`/`) reduced by JIT to multiply-shift chains
- Zero allocation on the hot path
- ~6–13 ns/op depending on value distribution

### 4. `JeaiiiFastWriter.writeIntToBytes/writeLongToBytes` (Division-Free)

The jeaiii technique removes division entirely using `Math.multiplyHigh` with precomputed reciprocals.

**Characteristics:**
- Division-free using `Math.multiplyHigh` (mulx on x86-64)
- `DIGIT_QUADS` table still used for 4-digit groups
- Trailing-zero leading group via right-aligned lookup
- Fastest option (~3–5 ns/op)

### 5. `JeaiiiPairsWriter.writeIntToBytes` (Two-Digit Pair Variant)

An earlier jeaiii variant using 2-digit pairs instead of 4-digit quads:

**Characteristics:**
- Smaller table (200 bytes vs 40 KB)
- One short store per 2-digit pair
- Slower on medium+ value distributions (1.8× vs FastWriter)
- Preserved as a benchmark fixture

## Performance Comparison

The following table summarizes average-time measurements across various value distributions (JDK 25.0.3, JMH 1.37, fresh agent):

| Implementation | tiny (0–9) | small (0–99) | medium (0–10⁶) | timestamp (13-digit) | full-range | negative | Alloc (B/op) |
|---------------|------------|---------------|----------------|----------------------|------------|----------|---------------|
| `Integer.toString` (alloc) | 11.6 | 18.2 | 20.1 | 20.5 | 28.6 | 30.4 | 40–50 |
| `Long.toString` (alloc) | 11.6 | 18.2 | 20.1 | 20.5 | 28.6 | 30.4 | 40–50 |
| `Integer.toString` + `String.format` | 52.6 | 71.4 | 73.1 | 50.0 | 55.2 | — | 40–50 + format overhead |
| `Long.toString` + `String.format` | 52.6 | 71.4 | 73.1 | 50.0 | 55.2 | — | 40–50 + format overhead |
| `JsonNumberWriter` (T5) | 4.9 | 6.1 | 6.6 | 7.7 | 13.0 | 12.8 | 0 |
| `JeaiiiPairsWriter` | 8.9 | 14.3 | 4.5 | 5.1 | 7.3 | 7.4 | 0 |
| `JeaiiiFastWriter` (division-free) | **4.9** | **7.3** | **3.7** | **4.0** | **4.9** | **4.8** | 0 |

**Key observations:**
- `Integer.toString`/`Long.toString` is 4–16× slower than optimized paths due to allocation
- `String.format` adds ~50% overhead over `Integer.toString()` due to format parsing
- `JeaiiiFastWriter` (division-free) is fastest on medium+ distributions (1.8× vs T5)
- `JeaiiiPairsWriter` is competitive on tiny/small but slower on medium+
- `JsonNumberWriter` (T5) uses hardware division, JIT optimizes to multiply-shift chains
- Both int and long variants show similar performance characteristics

## Allocation Comparison

| Implementation | Alloc (B/op) | GC Pressure |
|---------------|--------------|-------------|
| `Integer.toString` | 40–50 | High |
| `Long.toString` | 40–50 | High |
| `Integer.toString` + `String.format` | 40–50 + format | High |
| `Long.toString` + `String.format` | 40–50 + format | High |
| `JsonNumberWriter` | 0 (caller-owned) | None |
| `JeaiiiFastWriter` | 0 (caller-owned) | None |
| `JeaiiiPairsWriter` | 0 (caller-owned) | None |

The production path and all optimized alternatives are allocation-free when the caller supplies the buffer.

## Why Direct Buffer Writing is Faster

1. **Register residency:** The caller's `buf`/`pos` remain in registers across the call; no pointer reload needed.
2. **No arraycopy overhead:** Eliminates the ~5 ns `System.arraycopy` per number.
3. **Left-to-right emission:** Digits land in place; no scratch buffer needed.
4. **VarHandle bulk stores:** One `LE_INT.set` writes 4 digits at once; better instruction-level parallelism.
5. **JIT-friendly:** No virtual dispatch, no helper method calls between cursor and byte stores.

## Verification

- **Correctness:** `ForyPerfComparisonTest` compares byte-identical output against reference implementations.
- **Unit tests:** `JsonNumberWriterTest` covers all boundary cases (MIN/MAX values, negative, zero).
- **Benchmarks:** `IntWriteBenchmark` / `LongWriteBenchmark` measure performance across distributions.
- **Allocation:** `AllocationBenchmark` confirms 0 B/op on the hot path.

## Running the Benchmarks

To run the expanded benchmarks with all Java idiomatic toString variants:

```bash
# Compile the project first
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -o -pl core test-compile "-Dgpg.skip=true"

# Run the int benchmark
& "C:\Program Files\Java\jdk-25\bin\java.exe" -cp <classpath> org.openjdk.jmh.Main \
    hr.hrg.dialog.core.IntegralNumberToStringBenchmark -wi 3 -i 5 -f 1 -t 1

# Run the long benchmark
& "C:\Program Files\Java\jdk-25\bin\java.exe" -cp <classpath> org.openjdk.jmh.Main \
    hr.hrg.dialog.core.LongToStringBenchmark -wi 3 -i 5 -f 1 -t 1
```

**Benchmark classes:**
- `IntegralNumberToStringBenchmark` — All int/long toString variants
- `LongToStringBenchmark` — All long toString variants
- `IntWriteBenchmark` — Original benchmark
- `LongWriteBenchmark` — Original benchmark

**Distributions tested:**
- `tiny` (0–9 for int, 0–99 for long)
- `small` (0–99 for int, 0–10⁹ for long)
- `medium` (0–10⁶ for int, 0–10⁹ for long)
- `timestamp` (13-digit milliseconds)
- `full` (full range of int/long)
- `negative` (negative values)

## Related Concepts

- [04 — Number writing](04-number-writing.md) — Overview of number serialization techniques
- [08 — Float/Double Number Writing](./08-float-double-writing.md) — Floating-point number writing techniques
- `doc/perf-exploration/t9-bufferless-varhandle-number-writing.md` — Detailed implementation record
- `doc/perf-exploration/t10-jeaiii-fast-writer.md` — Division-free jeaiii writer details
- `doc/perf-exploration/t11-int-long-writer-comparison.md` — Int/long writer comparison record
- `doc/perf-exploration/t13-integral-tostring-comparison.md` — Expanded int/long toString benchmark comparison

## Benchmark Artifacts

The following benchmark files provide the expanded comparisons:

- [`core/src/test/java/hr/hrg/dialog/core/IntegralNumberToStringBenchmark.java`](../src/test/java/hr/hrg/dialog/core/IntegralNumberToStringBenchmark.java) — All int/long toString variants
- [`core/src/test/java/hr/hrg/dialog/core/LongToStringBenchmark.java`](../src/test/java/hr/hrg/dialog/core/LongToStringBenchmark.java) — All long toString variants
- [`core/src/test/java/hr/hrg/dialog/core/IntWriteBenchmark.java`](../src/test/java/hr/hrg/dialog/core/IntWriteBenchmark.java) — Original benchmark
- [`core/src/test/java/hr/hrg/dialog/core/LongWriteBenchmark.java`](../src/test/java/hr/hrg/dialog/core/LongWriteBenchmark.java) — Original benchmark

</content>
</function>
</tool_call>
<function=update_goal>
<parameter=action>
complete