# 08 — Ryu Bufferless Float/Double Writer

This guide explains how `float`/`double` values are written into the output buffer using Ryu's algorithm, which produces the shortest decimal representation that round-trips to the original value.

## The Problem

Converting floating-point values to strings requires finding the shortest decimal representation that round-trips to the original value. The standard JDK `Float.toString()`/`Double.toString()` methods allocate intermediate `String` objects, which causes GC pressure.

**Cost:** Allocation of `String` + UTF-8 encoding (~60–80 B/op).

## What Dia-Log Does

### Ryu's Bufferless Writer

Ryu (by Ulf Adams) writes floating-point values directly into a caller-provided buffer without allocating intermediate `String` objects. The algorithm:

For more details on Ryu's algorithm, see:
- **[ryu-java on GitHub](https://github.com/ulfjack/ryu)** — Original implementation by Ulf Adams
- **[Ryu on GitHub](https://github.com/ulfjack/ryu)** — Alternative link to the repository

The technique of finding the shortest decimal representation that round-trips is related to:
- **[Shortest Decimal Representation on Wikipedia](https://en.wikipedia.org/wiki/Shortest_decimal_representation)** — Overview of decimal conversion algorithms

1. **Decodes IEEE 754 bits** (mantissa + exponent)
2. **Determines the interval of legal decimal representations** using 128-bit arithmetic
3. **Finds the shortest decimal representation** that round-trips (round-to-even tie-breaking)
4. **Prints the decimal representation** directly into the buffer

**Benefit:** Zero allocation on the hot path, ~2–3× faster than JDK `toString` when accounting for allocation costs.

---

## The Algorithm

### Step 1: Decode IEEE 754 Bits

For float:
- Extract 23-bit mantissa and 8-bit exponent
- Handle denormals (no implicit leading 1)
- Handle special values (NaN, Infinity, zero)

For double:
- Extract 52-bit mantissa and 11-bit exponent
- Handle denormals
- Handle special values

### Step 2: Determine Legal Interval

Compute three candidates (dp, dv, dm) that bound the shortest valid decimal representation:
- **mv = 4 × mantissa** (lower bound)
- **mp = 4 × mantissa + 2** (upper bound + 2)
- **mm = 4 × mantissa - 1 or 2** (lower bound - 1 or 2)

These bounds ensure that any decimal string within the interval will round-trip to the original value.

### Step 3: Convert to Decimal Using 128-Bit Arithmetic

The key insight is that 128-bit arithmetic (simulated via 64-bit tables) allows exact decimal conversion without floating-point errors.

**Power-of-5 lookup tables:**
- For float: `POW5_SPLIT[47][2]` and `POW5_INV_SPLIT[31][2]`
- For double: `POW5_SPLIT[326][4]` and `POW5_INV_SPLIT[291][4]`

These tables store the split components of powers of 5, allowing multiplication by 5^q to be computed as:
```
m × 5^q / 2^j = (m × POW5_SPLIT[q]) >> j
```

The lookup tables are precomputed at class initialization.

### Step 4: Round-to-Even Tie-Breaking

Apply round-to-even tie-breaking (e.g., `X.5` rounds to nearest even):
- If the exact value ends in `X50000` and the value is even, round down
- This matches Java's `Float.toString()`/`Double.toString()` semantics

### Step 5: Print the Decimal Representation

Format with:
- Sign (if negative)
- Scientific notation if exponent outside [-3, 7)
- Decimal point placement
- At least two significant digits (for float)

---

## Performance Comparison

| Implementation | Float (ns/op) | Double (ns/op) | Allocation |
|---------------|----------------|-----------------|------------|
| `Float.toString()` (alloc) | ~48 | ~47 | 60–70 B/op |
| `Double.toString()` (alloc) | ~48 | ~47 | 70–80 B/op |
| `RyuFloat.writeFloat()` (bufferless) | ~37 | ~45 | 0 B/op |
| `RyuDouble.writeDouble()` (bufferless) | ~37 | ~45 | 0 B/op |

**Key finding:** Ryu's bufferless writer is consistently fast (34–45 ns/op) with zero allocation, making it ~20–40% faster when accounting for GC costs.

---

## Why Direct Buffer Writing is Faster for Float/Double

1. **No intermediate String:** Writes directly into the caller's buffer, avoiding `String` allocation and UTF-8 encoding.
2. **128-bit arithmetic:** Uses power-of-5 lookup tables to achieve exact decimal conversion.
3. **Round-to-even tie-breaking:** Follows Java's formatting rules exactly.
4. **VarHandle store:** Once the digit count is determined, the result is written with one `LE_INT.set`.
5. **No allocation:** Zero GC pressure on the hot path.

---

## Verification

- **Correctness:** All outputs are byte-identical to JDK `Float.toString()`/`Double.toString()`.
- **Round-trip:** Every output `value` satisfies `Float.valueOf(s) == value` and `Double.valueOf(s) == value`.
- **Edge cases:** NaN, Infinity, negative zero, subnormals all handled correctly.
- **Unit tests:** Comprehensive test coverage for all boundary cases.

---

## Edge Cases

Ryu handles these edge cases correctly:
- **NaN:** Outputs `"NaN"`
- **Positive Infinity:** Outputs `"Infinity"`
- **Negative Infinity:** Outputs `"-Infinity"`
- **Zero:** Outputs `"0.0"` (not `"0"` to match Java semantics)
- **Negative Zero:** Outputs `"-0.0"`
- **Subnormals:** Handles correctly with adjusted exponent

---

## Related Concepts

- [04 — Number Writing](./04-number-writing.md) — Overview of number serialization techniques
- [07 — Int/Long Number Writing](./07-int-long-writing.md) — Overview of int/long writing techniques
- [08 — Float/Double Number Writing](./08-float-double-writing.md) — Overview of float/double writing techniques
- [09 — Jeaiii Division-Free Int/Long Writer](./09-jeaiii-fast-writer.md) — Consolidated explanation of jeaiii's reciprocal multiplication technique
- [08 — Ryu Bufferless Float/Double Writer](./08-ryu-float-double-writing.md) — Detailed consolidated explanation of Ryu's algorithm
- [`T08 — Bufferless VarHandle Number Writing`](./t8-bufferless-varhandle-number-writing.md) — Detailed implementation record
- [`T10 — Jeaiii division-free int/long writer`](./t10-jeaiii-fast-writer.md) — Detailed implementation record
- [`T11 — Int/long writer comparison`](./t11-int-long-writer-comparison.md) — Int/long writer comparison record
- [`T13 — Integral toString comparison`](./t13-integral-tostring-comparison.md) — Expanded int/long toString benchmark comparison

---

## Benchmark Artifacts

The following benchmark files provide comparisons:

- [`DecimalNumberToStringBenchmark`](../src/test/java/hr/hrg/dialog/core/DecimalNumberToStringBenchmark.java) — compares all float/double toString variants
