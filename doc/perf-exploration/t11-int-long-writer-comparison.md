# T11 — Int/Long Writer Comparison

## Source technique

**Novel dia-log pattern** — separation of int/long writing documentation with expanded benchmark comparisons.

## What Fory does (upstream inspiration)

The Apache Fory project (commit 585eb16f) introduced digit tables and bufferless number writing. The jeaiii project (github.com/jeaiii/itoa) introduced division-free integer-to-ASCII conversion using `Math.multiplyHigh`.

## What dia-log did before

The original `doc/perf/07-bufferless-varhandle-number-writing.md` combined int/long/float/double writing into a single document. The benchmark comparison included:
- `JsonNumberWriter` (T5 digit tables + division)
- `JeaiiiPairsWriter` and `JeaiiiFastWriter` (division-free)
- `Integer.toString()` / `Long.toString()` (JDK baseline)
- Scratch buffer + `System.arraycopy` (old approach)

## What dia-log does now

The documentation is split into two dedicated guides:

1. **[07 — Int/Long Number Writing](../07-int-long-writing.md)** — focuses exclusively on int/long writing with expanded comparisons including:
   - All int/long idioms (JDK, JsonNumberWriter, Jeaiii variants, scratch)
   - Performance comparison across multiple value distributions
   - Allocation analysis
   - Detailed explanation of the algorithms used

2. **[08 — Float/Double Number Writing](../08-float-double-writing.md)** — focuses exclusively on float/double writing with:
   - All float/double idioms (JDK, Ryu, scratch, per-byte write)
   - Performance comparison
   - Allocation analysis
   - Detailed explanation of Ryu's algorithm

## Why this separation

Int/long and float/double writing have fundamentally different optimization strategies:

- **Int/long:** Uses digit tables (`DIGIT_QUADS`, `DIGIT_TRIPLES`) + division or division-free `multiplyHigh` reciprocals. The key optimization is 4-digit bulk stores via `LE_INT` VarHandle.
- **Float/double:** Delegates to Ryu's bufferless writer which uses 128-bit arithmetic via power-of-5 tables for exact decimal conversion. No digit tables needed; the algorithm is entirely different.

Separating them makes each document more focused and easier to maintain.

## Benchmark artifacts

The following benchmarks measure int/long writer performance:

- `core/IntWriteBenchmark.java` — compares `JsonNumberWriter`, `JeaiiiPairsWriter`, `JeaiiiFastWriter`, `Integer.toString()`
- `core/LongWriteBenchmark.java` — compares `JsonNumberWriter`, `JeaiiiPairsWriter`, `JeaiiiFastWriter`, `Long.toString()`
- `core/LongWriteBenchmark.java` — also tests timestamp, negative, full-range distributions

Run with:
```bash
java -cp <classpath> org.openjdk.jmh.Main hr.hrg.dialog.core.IntWriteBenchmark -wi 3 -i 5 -f 1
```

## Related techniques

- T5 — packed digit tables
- T9 — bufferless VarHandle number writing
- T10 — jeaiii division-free writer

## Verification

- `JsonNumberWriterTest` — unit tests covering all boundary cases
- `ForyPerfComparisonTest` — correctness verification against reference
- `AllocationBenchmark` — confirms 0 B/op on the hot path
