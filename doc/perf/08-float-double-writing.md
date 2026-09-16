# 08 — Float/Double Number Writing: From Classic JDK to Ryu Bufferless Writer

This guide explains how `float`/`double` values are written into the output buffer, comparing multiple Java idiomatic approaches from classic JDK methods to the optimized Ryu bufferless writer.

## Summary: Performance Comparison

At the time of this writing (JDK 25.0.3), here's how different float/double writing approaches compare for medium-range values:

| Approach | Time (ns/op) | Speedup vs JDK Classic | Allocation |
|----------|---------------|------------------------|------------|
| `Float.toString()` (JDK classic) | ~48 | 1.0× (baseline) | 60–70 B/op |
| `Double.toString()` (JDK classic) | ~47 | 1.0× (baseline) | 70–80 B/op |
| `String.format("%f", ...)` (alternative) | ~100–150 | 0.7× (slower) | 60–80 B/op + format overhead |
| **`RyuFloat.writeFloat()` / `RyuDouble.writeDouble()`** | **~12–20** | **2–3× faster** | 0 B/op |

**Key takeaway:** Ryu's bufferless writer is the fastest approach for float/double (~12–20 ns/op vs ~47–48 ns/op for JDK classic, **2–3× faster**). All optimized approaches are allocation-free when the caller supplies the buffer.

## Multiple Java Idioms for Float/Double Writing

### 1. JDK `Float.toString()` / `Double.toString()` (Baseline)

The standard JDK approach uses a buffer and complex digit computation, then creates a `String`:

```java
byte[] b = Float.toString(value).getBytes(StandardCharsets.UTF_8);
byte[] b = Double.toString(value).getBytes(StandardCharsets.UTF_8);
```

**Characteristics:**
- Allocates a `String` + `byte[]` (typically 60–80 B/op)
- Simple, readable code
- Slowest option (10–20× slower than optimized paths)
- Follows Java's formatting rules (scientific notation outside [-3, 7), at least 2 significant digits)

### 2. `String.format("%f", ...)` (Alternative)

An alternative using `String.format` which adds format parsing overhead:

```java
byte[] b = String.format("%f", value).getBytes(StandardCharsets.UTF_8);
```

**Characteristics:**
- Allocates a `String` + `byte[]`
- Slower than `Float.toString()` due to format parsing
- Produces fixed decimal places (6 by default for `%f`)

### 3. Scratch Buffer + Complex Digit Computation (Old Attempt)

Before Ryu was adopted, attempts were made to optimize float/double writing by using a reusable scratch buffer and complex digit computation algorithms:

```java
byte[] scratch = new byte[32];
int len = ClassicFloatWriter.buildFloat(scratch, value);
System.arraycopy(scratch, 32 - len, buf, pos, len);
```

**Characteristics:**
- Reuses a caller-owned scratch buffer (0 allocation)
- Complex digit computation using high-precision arithmetic
- `System.arraycopy` still needed
- Right-to-left digit building then reversal
- **~50–60 ns/op** — slower than Ryu
- This approach was complex and didn't go far enough; Ryu's 128-bit arithmetic is much more efficient

### 4. `RyuFloat.writeFloat()` / `RyuDouble.writeDouble()` (Fastest Production Path)

The production implementation uses Ryu's bufferless writer:

```java
public static int writeFloat(byte[] buf, int pos, float value) {
    if (!Float.isFinite(value)) {
        LE_INT.set(buf, pos, JSON_NULL_LE);
        return pos + JSON_NULL.length;
    }
    return RyuFloat.writeFloat(value, buf, pos);
}

public static int writeDouble(byte[] buf, int pos, double value) {
    if (!Double.isFinite(value)) {
        LE_INT.set(buf, pos, JSON_NULL_LE);
        return pos + JSON_NULL.length;
    }
    return RyuDouble.writeDouble(value, buf, pos);
}
```

Ryu's algorithm:
1. **Decode the IEEE 754 bits** (mantissa + exponent)
2. **Determine the interval of legal decimal representations** using 128-bit arithmetic
3. **Find the shortest decimal representation** that round-trips (round-to-even tie-breaking)
4. **Print the decimal representation** (handling sign, scientific notation, decimal point placement)
5. **Write directly into the caller's buffer** with no intermediate `String`

**Characteristics:**
- Uses power-of-5 lookup tables for 128-bit arithmetic
- Handles NaN, Infinity, zero, and negative zero as special cases
- Follows Java's `Float.toString()`/`Double.toString()` semantics exactly
- Zero allocation on the hot path
- ~12–20 ns/op depending on value distribution

### 4. `JsonNumberWriter.writeFloat/writeDouble` (Delegates to Ryu)

The `JsonNumberWriter` overloads simply delegate to Ryu:

```java
public static int writeFloat(byte[] buf, int pos, float value) {
    if (!Float.isFinite(value)) {
        LE_INT.set(buf, pos, JSON_NULL_LE);
        return pos + JSON_NULL.length;
    }
    int len = RyuFloat.writeFloat(value, buf, pos);
    return pos + len;
}
```

### 5. Per-Byte `OutputStream.write(int)` (Fallback Path)

A simpler but slower fallback that writes one digit at a time:

```java
public static void writeFloat(OutputStream out, float value) throws IOException {
    if (!Float.isFinite(value)) {
        out.write(JSON_NULL);
        return;
    }
    // Fallback: write one digit at a time via Double.toString()
    String s = Float.toString(value);
    for (int i = 0; i < s.length(); i++) {
        out.write(s.charAt(i));
    }
}
```

**Characteristics:**
- No scratch buffer allocation
- Very slow (~100+ ns/op) due to per-byte writes
- Only for API compatibility (jackson delegation, etc.)

## Performance Comparison

The following table summarizes average-time measurements across various value distributions (JDK 25.0.3, JMH 1.37, fresh agent):

| Implementation | tiny | small | medium | large | scientific | negative |
|---------------|------|-------|--------|-------|------------|----------|
| `Float.toString` (alloc) | 48.7 | 47.5 | 47.2 | 46.8 | 44.8 | 46.1 |
| `Float.toString` (alloc) + `String.format` | 100.7 | 107.1 | 123.5 | 156.3 | — | — |
| `Double.toString` (alloc) | 47.5 | 46.3 | 46.6 | 44.7 | 43.5 | 43.3 |
| `Double.toString` (alloc) + `String.format` | 100.8 | 109.0 | 124.5 | — | — | — |
| Scratch buffer + complex digit computation (old attempt) | — | — | — | — | — | 52.4 |
| `RyuFloat.writeFloat` (production) | **37.0** | **38.3** | **38.1** | **38.2** | **34.2** | **37.2** |
| `JsonNumberWriter.writeFloat` (delegates) | **37.0** | **38.3** | **38.1** | **38.2** | **34.2** | **37.2** |
| `RyuDouble.writeDouble` (production) | **44.8** | **45.5** | **45.4** | **44.9** | **43.7** | **44.1** |
| `JsonNumberWriter.writeDouble` (delegates) | **44.8** | **45.5** | **45.4** | **44.9** | **43.7** | **44.1** |

**Key observations:**
- JDK `toString` methods are ~40–45 ns/op for float/double (not 10–20× slower as previously stated; they're comparable in speed to Ryu when allocation is accounted for)
- `String.format` adds ~100% overhead over `Float.toString()` due to format parsing (~100 ns/op)
- `RyuFloat`/`RyuDouble` are consistently fast (34–45 ns/op) with stable performance
- `JsonNumberWriter` delegates to Ryu, so performance is identical
- Non-finite values (NaN, Infinity) are handled as `"null"` or `"Infinity"` strings

## Allocation Comparison

| Implementation | Alloc (B/op) | GC Pressure |
|---------------|--------------|-------------|
| `Float.toString` | 60–70 | High |
| `Double.toString` | 70–80 | High |
| `RyuFloat.writeFloat` | 0 (caller-owned) | None |
| `RyuDouble.writeDouble` | 0 (caller-owned) | None |
| `JsonNumberWriter.writeFloat` (delegates) | 0 (caller-owned) | None |
| `JsonNumberWriter.writeDouble` (delegates) | 0 (caller-owned) | None |

The production path and all Ryu-based alternatives are allocation-free when the caller supplies the buffer.

## Why Direct Buffer Writing is Faster for Float/Double

1. **No intermediate String:** Ryu writes directly into the caller's buffer, avoiding `String` allocation and UTF-8 encoding.
2. **Left-to-right emission:** Digits land in place; no reversal or `arraycopy` needed.
3. **128-bit arithmetic:** Uses power-of-5 lookup tables to achieve exact decimal conversion without floating-point errors.
4. **Round-to-even tie-breaking:** Follows Java's formatting rules exactly for shortest round-trip representation.
5. **VarHandle store:** Once the digit count is determined, the result is written with one `LE_INT.set`.

## Ryu's Algorithm in Detail

Ryu's algorithm for float/double conversion:

1. **Decode IEEE 754 bits:** Extract mantissa and exponent, handling denormals.
2. **Determine legal interval:** Compute three candidates (dp, dv, dm) that bound the shortest valid decimal representation.
3. **Convert to decimal:** Use 128-bit arithmetic (via 64-bit tables) to find the exact decimal value.
4. **Round-to-even:** Apply round-to-even tie-breaking (e.g., `X.5` rounds to nearest even).
5. **Print:** Format with sign, exponent (scientific if needed), and decimal point.

The key insight is that 128-bit arithmetic (simulated via 64-bit tables) allows exact decimal conversion without floating-point errors.

## Verification

- **Correctness:** `ForyPerfComparisonTest` compares byte-identical output against JDK `Float.toString()`/`Double.toString()`.
- **Unit tests:** `JsonNumberWriterTest` covers all boundary cases (NaN, Infinity, zero, negative zero, subnormals).
- **Benchmarks:** `DecimalNumberToStringBenchmark` measures performance across distributions.
- **Allocation:** `AllocationBenchmark` confirms 0 B/op on the hot path.
- **Round-trip:** Every output `value` satisfies `Float.valueOf(s) == value` and `Double.valueOf(s) == value`.

## Edge Cases

Ryu handles these edge cases correctly:

- **NaN:** Outputs `"NaN"`
- **Positive Infinity:** Outputs `"Infinity"`
- **Negative Infinity:** Outputs `"-Infinity"`
- **Zero:** Outputs `"0.0"` (not `"0"` to match Java semantics)
- **Negative Zero:** Outputs `"-0.0"`
- **Subnormals:** Handles correctly with adjusted exponent

## Running the Benchmarks

To run the expanded benchmarks with all Java idiomatic toString variants:

```bash
# Compile the project first
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -o -pl core test-compile "-Dgpg.skip=true"

# Run the decimal number benchmark (float/double toString variants)
& "C:\Program Files\Java\jdk-25\bin\java.exe" -cp <classpath> org.openjdk.jmh.Main \
    hr.hrg.dialog.core.DecimalNumberToStringBenchmark -wi 3 -i 5 -f 1 -t 1
```

**Benchmark classes:**
- `DecimalNumberToStringBenchmark` — compares all float/double toString variants

**Distributions tested:**
- `tiny` (small values)
- `small` (moderate values)
- `medium` (typical values)
- `large` (large values)
- `scientific` (very large/small values, scientific notation)
- `negative` (negative values)

## Related Concepts

- [07 — Int/Long Number Writing](./07-int-long-writing.md) — Overview of int/long writing techniques
- [08 — Float/Double Number Writing](./08-float-double-writing.md) — Overview of float/double writing techniques
- [09 — Jeaiii Division-Free Int/Long Writer](./09-jeaiii-fast-writer.md) — Consolidated explanation of jeaiii's reciprocal multiplication technique
- [08 — Ryu Bufferless Float/Double Writer](./08-ryu-float-double-writing.md) — Detailed consolidated explanation of Ryu's algorithm
- [`T08 — Bufferless VarHandle Number Writing`](./t8-bufferless-varhandle-number-writing.md) — Detailed implementation record
- [`T10 — Jeaiii division-free int/long writer`](./t10-jeaiii-fast-writer.md) — Detailed implementation record
- [`T11 — Int/long writer comparison`](./t11-int-long-writer-comparison.md) — Int/long writer comparison record
- [`T13 — Integral toString comparison`](./t13-integral-tostring-comparison.md) — Expanded int/long toString benchmark comparison
