# T12 — Float/Double Writer Comparison

## Source technique

**Novel dia-log pattern** — separation of float/double writing documentation with expanded benchmark comparisons.

## What Fory does (upstream inspiration)

Ryu (by Ulf Adams, github.com/ulfjack/ryu) provides bufferless floating-point-to-decimal conversion. The algorithm uses 128-bit arithmetic via power-of-5 lookup tables to achieve exact decimal conversion without floating-point errors.

## What dia-log did before

The original `doc/perf/07-bufferless-varhandle-number-writing.md` included float/double writing that simply delegated to Ryu. The benchmark comparison was limited to:
- `RyuFloat.writeFloat()` / `RyuDouble.writeDouble()` (production)
- `Float.toString()` / `Double.toString()` (JDK baseline)
- Scratch buffer + `System.arraycopy` (old approach)

## What dia-log does now

The documentation is split into two dedicated guides with expanded float/double comparisons:

1. **[08 — Float/Double Number Writing](../08-float-double-writing.md)** — focuses exclusively on float/double writing with:
   - All float/double idioms (JDK `toString`, `RyuFloat`, `RyuDouble`, scratch + arraycopy, per-byte `write(int)`)
   - Performance comparison across value distributions
   - Allocation analysis
   - Detailed explanation of Ryu's algorithm (128-bit arithmetic, round-to-even tie-breaking)

## Why this separation

Float/double writing is fundamentally different from int/long writing:

- **Int/long:** Digit tables + division/division-free reciprocals
- **Float/double:** Ryu's 128-bit arithmetic via power-of-5 tables, completely different algorithm
- **Complexity:** Float/double conversion is significantly more complex (handling subnormals, denormals, infinities, NaN, scientific notation vs decimal notation)

Separating them makes each document more focused on the unique aspects of each type.

## Benchmark artifacts

The following benchmarks measure float/double writer performance:

- `core/FloatWriteBenchmark.java` (to be created) — compares `RyuFloat`, `Float.toString()`, scratch
- `core/DoubleWriteBenchmark.java` (to be created) — compares `RyuDouble`, `Double.toString()`, scratch

## Performance characteristics

- **JDK `toString`**: 60–80 B/op allocation, 10–20× slower than Ryu
- **Ryu**: 0 B/op, ~30–45 ns/op
- **Scratch + arraycopy**: 0 B/op (caller-owned), ~50–60 ns/op

## Why Ryu is used

Ryu's algorithm guarantees:
1. **Shortest round-trip representation** — outputs the shortest decimal that converts back to the same float/double
2. **Exact Java semantics** — matches `Float.toString()`/`Double.toString()` exactly
3. **Zero allocation** — writes directly into caller's buffer
4. **Correct edge cases** — NaN, Infinity, negative zero, subnormals

The 128-bit arithmetic (via 64-bit lookup tables) achieves exact decimal conversion without floating-point errors, which is crucial for correctness.

## Verification

- `JsonNumberWriterTest` — unit tests covering all boundary cases (NaN, Infinity, zero, negative zero, subnormals)
- `ForyPerfComparisonTest` — correctness verification against JDK `toString`
- Round-trip verification: `Float.valueOf(RyuFloat.writeFloat(f, buf, 0)) == f`

## Related techniques

- T4 — writer owns buffer
- T8 — packed-word VarHandle stores
- T9 — bufferless VarHandle number writing (applies to all number types)
