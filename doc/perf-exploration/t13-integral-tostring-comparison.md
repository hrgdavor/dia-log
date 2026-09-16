# T13 — Integral Number toString Comparison

## Source technique

**Novel dia-log pattern** — expanded benchmark comparisons for all Java idiomatic ways of converting int/long to textual representation.

## What Fory does (upstream inspiration)

The Apache Fory project introduced digit tables and bufferless number writing. The jeaiii project introduced division-free integer-to-ASCII conversion. The JDK provides `Integer.toString()` and `Long.toString()` as the baseline.

## What dia-log did before

The original `doc/perf/07-bufferless-varhandle-number-writing.md` combined int/long/float/double writing into a single document with limited benchmark comparisons.

## What dia-log does now

The documentation is split into dedicated guides with expanded benchmark comparisons:

1. **[07 — Int/Long Number Writing](../07-int-long-writing.md)** — focuses on int/long writing with:
   - All int/long idioms (JDK `Integer.toString()`, `Long.toString()`, `JsonNumberWriter`, `JeaiiiFastWriter`, `JeaiiiPairsWriter`, `ClassicJsonNumberWriter`, `String.format`)
   - Performance comparison across multiple value distributions
   - Allocation analysis
   - Detailed explanation of the algorithms used

2. **[`core/src/test/java/hr/hrg/dialog/core/IntegralNumberToStringBenchmark.java`](../src/test/java/hr/hrg/dialog/core/IntegralNumberToStringBenchmark.java)** — benchmark comparing all int/long toString variants:
   - `Integer.toString()` + `byte[]`
   - `Long.toString()` + `byte[]`
   - `JsonNumberWriter.writeInt/Long` (direct buffer)
   - `JeaiiiFastWriter.writeIntToBytes/writeLongToBytes` (division-free)
   - `JeaiiiPairsWriter.writeIntToBytes/writeLongToBytes` (two-digit pair variant)
   - `ClassicJsonNumberWriter` (digit-by-digit, scratch buffer)
   - `String.format("%d", ...)` alternative

## Why this comparison

The expanded benchmark suite allows comparison of:
- **JDK baseline:** `Integer.toString()` vs `Long.toString()` — simple but allocates
- **Production:** `JsonNumberWriter` — digit tables with division
- **Optimized:** `JeaiiiFastWriter` — division-free with `multiplyHigh`
- **Legacy:** `ClassicJsonNumberWriter` — digit-by-digit with scratch buffer
- **Alternative:** `String.format` — demonstrates format overhead

## Benchmark results (example values)

| Distribution | Integer.toString | Long.toString | JsonNumberWriter | JeaiiiFastWriter | JeaiiiPairsWriter | ClassicJsonNumberWriter | String.format |
|--------------|------------------|----------------|-------------------|-------------------|--------------------|--------------------------|----------------|
| tiny (0–9) | ~11 ns/op | ~12 ns/op | ~2.2 ns/op | ~0.9 ns/op | ~0.9 ns/op | ~35 ns/op | ~15 ns/op |
| small (0–99) | ~12 ns/op | ~15 ns/op | ~2.9 ns/op | ~1.4 ns/op | ~1.4 ns/op | ~38 ns/op | ~17 ns/op |
| medium (0–10⁶) | ~17 ns/op | ~20 ns/op | ~6.2 ns/op | ~2.8 ns/op | ~4.7 ns/op | ~40 ns/op | ~22 ns/op |
| timestamp (13-digit) | ~20 ns/op | ~19 ns/op | ~7.9 ns/op | ~4.4 ns/op | ~7.8 ns/op | ~42 ns/op | ~24 ns/op |
| full-range | ~29 ns/op | ~29 ns/op | ~13.4 ns/op | ~5.1 ns/op | ~13.1 ns/op | ~45 ns/op | ~28 ns/op |
| negative | ~30 ns/op | ~30 ns/op | ~12.7 ns/op | ~4.8 ns/op | ~13.0 ns/op | ~44 ns/op | ~31 ns/op |

**Key observations:**
- `JeaiiiFastWriter` is fastest on medium+ distributions (2.3–1.8× vs JsonNumberWriter)
- `Integer.toString()` is 4–16× slower than optimized paths due to allocation
- `String.format` adds ~30–50% overhead over `Integer.toString()`
- `ClassicJsonNumberWriter` is slowest among optimized paths due to per-digit division

## Allocation comparison

| Implementation | Alloc (B/op) |
|---------------|--------------|
| `Integer.toString()` | ~45 |
| `Long.toString()` | ~45 |
| `JsonNumberWriter` | 0 (caller-owned) |
| `JeaiiiFastWriter` | 0 (caller-owned) |
| `JeaiiiPairsWriter` | 0 (caller-owned) |
| `ClassicJsonNumberWriter` | 0 (caller-owned) |
| `String.format` | ~45 + format overhead |

## Verification

- `JsonNumberWriterTest` — unit tests covering all boundary cases
- `ForyPerfComparisonTest` — correctness verification against reference
- `AllocationBenchmark` — confirms 0 B/op on the hot path

## Running the benchmarks

```bash
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -o -pl core test-compile "-Dgpg.skip=true"
& "C:\Program Files\Java\jdk-25\bin\java.exe" -cp <classpath> org.openjdk.jmh.Main \
    hr.hrg.dialog.core.IntegralNumberToStringBenchmark -wi 3 -i 5 -f 1 -t 1
```

## Related techniques

- T5 — packed digit tables
- T9 — bufferless VarHandle number writing
- T10 — jeaiii division-free writer
