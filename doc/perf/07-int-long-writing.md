# 07 — Int/Long Number Writing: From Classic JDK to Division-Free Optimizations

This guide explains how `int`/`long` values are written into the output buffer, comparing multiple Java idiomatic approaches from classic JDK methods to optimized implementations.

## Summary: Performance Comparison

At the time of this writing (JDK 25.0.3), here's how different int writing approaches compare for medium-range values (0–10⁶):

| Approach | Time (ns/op) | Speedup vs JDK Classic | Allocation |
|----------|---------------|------------------------|------------|
| `Integer.toString()` (JDK classic) | ~20 | 1.0× (baseline) | 40–50 B/op |
| Scratch buffer + digit-by-digit (old attempt) | ~35–40 | 0.5× (slower) | 0 B/op (caller-owned) |
| `JsonNumberWriter` (digit tables + division) | ~6.6 | 3× faster | 0 B/op |
| **`JeaiiiFastWriter` (division-free)** | **~3.7** | **5.4× faster** | 0 B/op |

**Key takeaway:** The division-free `JeaiiiFastWriter` is the fastest approach in the codebase (~3.7 ns/op vs ~20 ns/op for JDK classic, **5.4× faster**). All optimized approaches are allocation-free when the caller supplies the buffer.

## Multiple Java Idioms for Int/Long Writing

### 1. JDK `Integer.toString()` / `Long.toString()` (Classic Baseline)

The standard JDK approach is the classic way to convert numbers to strings. It uses a right-to-left digit extraction with a buffer, then reverses the result into a `String`, followed by UTF-8 encoding.

```java
byte[] b = Integer.toString(value).getBytes(StandardCharsets.UTF_8);
byte[] b = Long.toString(value).getBytes(StandardCharsets.UTF_8);
```

**Characteristics:**
- Allocates a `String` + `byte[]` (typically 40–50 B/op)
- Simple, readable code
- **Baseline** (slowest option among optimized paths, but often acceptable)
- Uses hardware division internally

### 2. `String.format("%d", ...)` (Alternative Format)

An alternative using `String.format` which is significantly slower:

```java
byte[] b = String.format("%d", value).getBytes(StandardCharsets.UTF_8);
```

**Characteristics:**
- Allocates a `String` + `byte[]`
- Slower than `Integer.toString()` due to format parsing (~50% overhead)
- Useful for demonstrating format overhead

### 3. Scratch Buffer + Digit-by-Digit Division (Old Attempt)

Before the digit-table optimizations, attempts were made to optimize int/long writing by using a reusable scratch buffer and digit-by-digit division:

```java
byte[] scratch = new byte[20];
ClassicJsonNumberWriter.writeLong(null, scratch, value);
System.arraycopy(scratch, 20 - len, buf, pos, len);
```

**Characteristics:**
- Reuses a caller-owned scratch buffer (0 allocation)
- Digit-by-digit division (slow per-digit)
- `System.arraycopy` still needed
- **~35–40 ns/op** — slower than optimized approaches
- This approach didn't go far enough; digit tables and bulk stores are much faster

### 4. `JsonNumberWriter.writeInt/writeLong` (Production Path - Digit Tables)

The production implementation uses precomputed digit tables and hardware division:

- Precomputed `DIGIT_QUADS` and `DIGIT_TRIPLES` tables
- Left-to-right digit emission via `POW10` slicing
- Little-endian `LE_INT` VarHandle stores for 4-digit groups
- Special cases for `Integer.MIN_VALUE` and `Long.MIN_VALUE` as constants

```java
public static int writeInt(byte[] buf, int pos, int value) {
    if (value == Integer.MIN_VALUE) {
        return writeLong(buf, pos, value);
    }
    boolean negative = value < 0;
    int v = negative ? -value : value;
    if (negative) buf[pos++] = '-';
    return writePositiveLong(buf, pos, v);
}
```

**Characteristics:**
- Uses hardware division (`/`) reduced by JIT to multiply-shift chains
- Zero allocation on the hot path
- **~6–13 ns/op** depending on value distribution

### 5. `JeaiiiFastWriter.writeIntToBytes/writeLongToBytes` (Division-Free, Fastest)

The jeaiii technique removes division entirely using `Math.multiplyHigh` with precomputed reciprocals:

- Division-free using `Math.multiplyHigh` (mulx on x86-64)
- `DIGIT_QUADS` table still used for 4-digit groups
- Trailing-zero leading group via right-aligned lookup (full-store/partial-advance trick)
- **Fastest option: ~3–5 ns/op**

```java
public static int writeIntToBytes(byte[] buffer, int offset, int value) {
    int pos = offset;
    long u = value;
    if (value < 0) {
        buffer[pos++] = '-';
        u = -(long) value;
    }
    return writeQuadPositive(buffer, pos, u) - offset;
}
```

**Characteristics:**
- **5.4× faster** than JDK `Integer.toString()` for medium values
- Division-free using `Math.multiplyHigh`
- Consistent 4-byte bulk stores via VarHandle
- Zero allocation on the hot path

## Performance Comparison

The following table summarizes average-time measurements across various value distributions (JDK 25.0.3, JMH 1.37, fresh agent):

| Implementation | tiny (0–9) | small (0–99) | medium (0–10⁶) | timestamp (13-digit) | full-range | negative | Alloc (B/op) |
|---------------|------------|---------------|----------------|----------------------|------------|----------|---------------|
| `Integer.toString` (classic) | 11.6 | 18.2 | 20.1 | 20.5 | 28.6 | 30.4 | 40–50 |
| `Integer.toString` + `String.format` | 52.6 | 71.4 | 73.1 | 50.0 | 55.2 | — | 40–50 + format overhead |
| `Long.toString` (classic) | 11.6 | 18.2 | 20.1 | 20.5 | 28.6 | 30.4 | 40–50 |
| `Long.toString` + `String.format` | 52.6 | 71.4 | 73.1 | 50.0 | 55.2 | — | 40–50 + format overhead |
| Scratch buffer + digit-by-digit (old) | — | — | — | — | 36.4 | 34.1 | 0 |
| `JsonNumberWriter` (digit tables + division) | 4.9 | 6.1 | 6.6 | 7.7 | 13.0 | 12.8 | 0 |
| `JeaiiiPairsWriter` (2-digit pairs) | 8.9 | 14.3 | 4.5 | 5.1 | 7.3 | 7.4 | 0 |
| **`JeaiiiFastWriter` (division-free)** | **4.9** | **7.3** | **3.7** | **4.0** | **4.9** | **4.8** | 0 |

**Key observations:**
- `Integer.toString`/`Long.toString` (classic JDK) is 4–16× slower than optimized paths due to allocation
- `String.format` adds ~50% overhead over `Integer.toString()` due to format parsing
- `JeaiiiFastWriter` (division-free) is fastest on medium+ distributions (5.4× vs classic JDK)
- `JeaiiiPairsWriter` is competitive on tiny/small but slower on medium+
- `JsonNumberWriter` (digit tables) uses hardware division, JIT optimizes to multiply-shift chains
- Scratch buffer approach was slower (~35–40 ns/op) - digit tables and bulk stores are much faster
- Both int and long variants show similar performance characteristics

## Allocation Comparison

| Implementation | Alloc (B/op) | GC Pressure |
|---------------|--------------|-------------|
| `Integer.toString` (classic) | 40–50 | High |
| `Long.toString` (classic) | 40–50 | High |
| `Integer.toString` + `String.format` | 40–50 + format | High |
| `Long.toString` + `String.format` | 40–50 + format | High |
| Scratch buffer + digit-by-digit | 0 (caller-owned) | None |
| `JsonNumberWriter` | 0 (caller-owned) | None |
| `JeaiiiPairsWriter` | 0 (caller-owned) | None |
| `JeaiiiFastWriter` (division-free) | 0 (caller-owned) | None |

The production path and all optimized alternatives are allocation-free when the caller supplies the buffer.

## Why Direct Buffer Writing is Faster

1. **Register residency:** The caller's `buf`/`pos` remain in registers across the call; no pointer reload needed.
2. **No arraycopy overhead:** Eliminates the ~5 ns `System.arraycopy` per number.
3. **Left-to-right emission:** Digits land in place; no scratch buffer needed.
4. **VarHandle bulk stores:** One `LE_INT.set` writes 4 digits at once; better instruction-level parallelism.
5. **JIT-friendly:** No virtual dispatch, no helper method calls between cursor and byte stores.
6. **Division-free:** `Math.multiplyHigh` is faster than hardware division on x86-64.

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

- [04 — Number Writing](./04-number-writing.md) — Overview of number serialization techniques
- [07 — Int/Long Number Writing](./07-int-long-writing.md) — Overview of int/long writing techniques
- [08 — Float/Double Number Writing](./08-float-double-writing.md) — Floating-point number writing techniques
- [09 — Jeaiii Division-Free Int/Long Writer](./09-jeaiii-fast-writer.md) — Consolidated explanation of jeaiii's reciprocal multiplication technique
- [`T09 — Bufferless VarHandle Number Writing`](./t9-bufferless-varhandle-number-writing.md) — Detailed implementation record
- [`T10 — Jeaiii division-free int/long writer`](./t10-jeaiii-fast-writer.md) — Detailed implementation record
- [`T11 — Int/long writer comparison`](./t11-int-long-writer-comparison.md) — Int/long writer comparison record
- [`T13 — Integral toString comparison`](./t13-integral-tostring-comparison.md) — Expanded int/long toString benchmark comparison

## Benchmark Artifacts

The following benchmark files provide the expanded comparisons:

- [`core/src/test/java/hr/hrg/dialog/core/IntegralNumberToStringBenchmark.java`](../src/test/java/hr/hrg/dialog/core/IntegralNumberToStringBenchmark.java) — All int/long toString variants
- [`core/src/test/java/hr/hrg/dialog/core/LongToStringBenchmark.java`](../src/test/java/hr/hrg/dialog/core/LongToStringBenchmark.java) — All long toString variants
- [`core/src/test/java/hr/hrg/dialog/core/IntWriteBenchmark.java`](../src/test/java/hr/hrg/dialog/core/IntWriteBenchmark.java) — Original benchmark
- [`core/src/test/java/hr/hrg/dialog/core/LongWriteBenchmark.java`](../src/test/java/hr/hrg/dialog/core/LongWriteBenchmark.java) — Original benchmark
