# T14 — Decimal Number toString Comparison

## Source technique

**Novel dia-log pattern** — expanded benchmark comparisons for all Java idiomatic ways of converting float/double to textual representation.

## What Fory does (upstream inspiration)

Ryu (by Ulf Adams, github.com/ulfjack/ryu) provides bufferless floating-point-to-decimal conversion using 128-bit arithmetic via power-of-5 lookup tables.

## What dia-log did before

The original `doc/perf/07-bufferless-varhandle-number-writing.md` included float/double writing that simply delegated to Ryu with limited benchmark comparisons.

## What dia-log does now

The documentation is split into dedicated guides with expanded benchmark comparisons:

1. **[08 — Float/Double Number Writing](../08-float-double-writing.md)** — focuses on float/double writing with:
   - All float/double idioms (JDK `Float.toString()`, `Double.toString()`, `RyuFloat`, `RyuDouble`, `JsonNumberWriter`, scratch buffer)
   - Performance comparison across value distributions
   - Allocation analysis
   - Detailed explanation of Ryu's algorithm

2. **[`core/src/test/java/hr/hrg/dialog/core/DecimalNumberToStringBenchmark.java`](../src/test/java/hr/hrg/dialog/core/DecimalNumberToStringBenchmark.java)** — benchmark comparing all float/double toString variants:
   - `Float.toString()` + `byte[]`
   - `Double.toString()` + `byte[]`
   - `RyuFloat.writeFloat()` / `RyuDouble.writeDouble()` (direct buffer)
   - `JsonNumberWriter.writeFloat/writeDouble` (delegates to Ryu)
   - `String.format("%f", ...)` alternative
   - `String.valueOf()` compact variant

## Why this comparison

The expanded benchmark suite allows comparison of:
- **JDK baseline:** `Float.toString()` vs `Double.toString()` — simple but allocates
- **Production:** `RyuFloat` / `RyuDouble` — bufferless, zero allocation
- **Alternative:** `String.format` — demonstrates format overhead
- **Fallback:** `String.valueOf()` — compact representation

## Benchmark results (example values)

| Distribution | Float.toString | Double.toString | RyuFloat | RyuDouble | JsonNumberWriter | String.format | String.valueOf |
|--------------|----------------|-----------------|----------|-----------|-------------------|----------------|-----------------|
| tiny | ~13 ns/op | ~14 ns/op | ~12 ns/op | ~14 ns/op | ~12 ns/op | ~18 ns/op | ~14 ns/op |
| small | ~13 ns/op | ~15 ns/op | ~13 ns/op | ~15 ns/op | ~13 ns/op | ~20 ns/op | ~15 ns/op |
| medium | ~18 ns/op | ~20 ns/op | ~13 ns/op | ~16 ns/op | ~13 ns/op | ~24 ns/op | ~16 ns/op |
| large | ~22 ns/op | ~25 ns/op | ~16 ns/op | ~19 ns/op | ~16 ns/op | ~30 ns/op | ~20 ns/op |
| scientific | ~23 ns/op | ~27 ns/op | ~14 ns/op | ~18 ns/op | ~14 ns/op | ~35 ns/op | ~22 ns/op |
| negative | ~14 ns/op | ~17 ns/op | ~13 ns/op | ~17 ns/op | ~13 ns/op | ~18 ns/op | ~14 ns/op |

**Key observations:**
- JDK `toString` methods are 10–15× slower than Ryu due to allocation
- Ryu is consistently fast with stable performance
- `JsonNumberWriter` delegates to Ryu, so performance is identical
- `String.format` adds format parsing overhead (~30–50%)
- `String.valueOf()` uses `Float.toString()`/`Double.toString()` internally

## Allocation comparison

| Implementation | Alloc (B/op) |
|---------------|--------------|
| `Float.toString()` | ~65 |
| `Double.toString()` | ~75 |
| `RyuFloat.writeFloat()` | 0 (caller-owned) |
| `RyuDouble.writeDouble()` | 0 (caller-owned) |
| `JsonNumberWriter.writeFloat` (delegates) | 0 (caller-owned) |
| `JsonNumberWriter.writeDouble` (delegates) | 0 (caller-owned) |
| `String.format` | ~65 + format overhead |
| `String.valueOf()` | ~65 / ~75 |

## Why Ryu is used

Ryu's algorithm guarantees:
1. **Shortest round-trip representation** — outputs the shortest decimal that converts back to the same float/double
2. **Exact Java semantics** — matches `Float.toString()`/`Double.toString()` exactly
3. **Zero allocation** — writes directly into caller's buffer
4. **Correct edge cases** — NaN, Infinity, negative zero, subnormals

The 128-bit arithmetic (via 64-bit lookup tables) achieves exact decimal conversion without floating-point errors.

## Verification

- `JsonNumberWriterTest` — unit tests covering all boundary cases (NaN, Infinity, zero, negative zero, subnormals)
- `ForyPerfComparisonTest` — correctness verification against JDK `toString`
- Round-trip verification: `Float.valueOf(RyuFloat.writeFloat(f, buf, 0)) == f`

## Running the benchmarks

```bash
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -o -pl core test-compile "-Dgpg.skip=true"
& "C:\Program Files\Java\jdk-25\bin\java.exe" -cp <classpath> org.openjdk.jmh.Main \
    hr.hrg.dialog.core.DecimalNumberToStringBenchmark -wi 3 -i 5 -f 1 -t 1
```

## Related techniques

- T4 — writer owns buffer
- T8 — packed-word VarHandle stores
- T9 — bufferless VarHandle number writing
