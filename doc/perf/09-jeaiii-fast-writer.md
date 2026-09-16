# 09 — Jeaiii Division-Free Int/Long Writer

This guide explains the alternative integer-to-string writer that uses reciprocal multiplication instead of hardware division for digit extraction.

## The Problem

Traditional integer-to-string conversion uses hardware division:

```java
while (n >= 10) {
    n /= 10;      // Hardware division
    digits.push(n % 10);
}
```

**Cost:** Hardware `idiv` is slow (20–80 cycles on x86-64).

## What Dia-Log Does

### Jeaiii's Reciprocal Multiplication

Instead of division, use precomputed magic constants with `Math.multiplyHigh`:

For more details on reciprocal multiplication and magic constants, see:
- [jeaiii/itoa on GitHub](https://github.com/jeaiii/itoa) — Original implementation by jeaiii

The technique of using reciprocal multiplication to avoid hardware division is a classic optimization. See also:
- **[Division Algorithm on Wikipedia](https://en.wikipedia.org/wiki/Division_algorithm#Reciprocal_multiplication)** — Overview of division-free algorithms

```java
// For divisor d = 10^k, precompute:
// M = ceil(2^64 / d)
// Then: q = multiplyHigh(v, M) == v / d  (exactly!)

long magic = 0xC74520EE;  // For d = 10^9
long q = multiplyHigh(value, magic);  // Division-free!
long r = value - q * 1_000_000_000L;  // Remainder
```

**Benefit:** `Math.multiplyHigh` compiles to a single `mulx` instruction (1 cycle) instead of `idiv` (20–80 cycles).

---

## The Techniques

### Tiered Fast Path (1–4 digits)

For common cases (1–4 digits), use specialized tables to avoid full quad extraction:

```java
if (n < 10) {
    buf[pos++] = (byte)(n + '0');  // 1 byte store
    return pos;
}
if (n < 100) {
    LE_SHORT.set(buf, pos, TWO_DIGITS_LE[n]);  // 1 short store
    pos += 2;
    return pos;
}
if (n < 1000) {
    LE_INT.set(buf, pos, TRAILING_TRIPLES[n]);  // 3 significant + trailing '0'
    pos += 3;
    return pos;
}
// Fall back to quads for 4+ digits
```

### Stage 4: Trailing-Zero Leading Group (Current Implementation)

The 1–3 digit leading group is written as a full 4-byte word with trailing zeros:

```java
// TRAILING_TRIPLES[123] = 0x33333000  // "1230" (trailing '0' is overwritten)
LE_INT.set(buf, pos, TRAILING_TRIPLES[n]);
pos += 4;  // Advance by full 4 bytes, not 3
```

**Benefit:** One fixed-width store instead of variable-length byte stores. The trailing `'0'` bytes are either overwritten by the next group or never observed.

### Stage 1: Pairs Writer (2 digits per store)

```java
// 200-byte table of little-endian pairs
short[] TWO_DIGITS_LE = new short[200];

// Write pairs from least significant
while (n >= 100) {
    n /= 100;
    pos = TWO_DIGITS_LE[n % 200];  // One short store
}
```

**Performance:** Fast, but requires 2 stores per 2 digits.

### Stage 2: Quads Writer (4 digits per store)

```java
// 40 KB DIGIT_QUADS table (4 bytes per 0..9999)
int[] DIGIT_QUADS = new int[10000];

// Division-free 4-digit extraction
long magic = 0xC74520EE;  // For d = 10^9
long q = multiplyHigh(value, magic);
int group = (int)q;  // One int store
```

**Performance:** 4 digits per store, no division.

---

## Performance Comparison

| Distribution | jsonNumberWriter | jeaiiiQuad | Speedup |
|--------------|------------------|------------|---------|
| Int: 0–10⁶ | 6.212 ns/op | 2.745 ns/op | 2.3× |
| Int: full | 8.510 ns/op | 5.082 ns/op | 1.7× |
| Long: timestamp | 7.889 ns/op | 4.396 ns/op | 1.8× |
| Long: full | 13.433 ns/op | 8.226 ns/op | 1.6× |

**Key finding:** Jeaiii's quad writer is **1.6–2.3× faster** than the Fory-style `JsonNumberWriter` on full-range inputs.

---

## Trade-Offs

### Advantages
- **No hardware division** — 1 cycle vs 20–80 cycles
- **Fewer stores** — 4 digits per int store
- **Tiered fast path** — 0.89 ns for single-digit values
- **Trailing-zero leading group** — Fixed 4-byte stores throughout

### Disadvantages
- **Larger tables** — 44 KB total (40 KB quads + 4 KB triples + 200 B pairs)
- **More complex code** — Multiple fast paths and tables

**Verdict:** The performance gain (1.6–2.3×) outweighs the table size on modern CPUs with sufficient L2 cache.

---

## Verification

- **Exhaustive testing:** All 2³² int values tested against division-based reference
- **Unit tests:** 85+ tests covering boundaries and random values
- **Benchmark:** `DigitGroupStoreBenchmark` measures cache effects of table sizes
- **Zero allocation:** All hot paths use caller-owned buffers with no GC allocations

---

## Related Concepts

- [04 — Number Writing](./04-number-writing.md) — Overview of number serialization techniques
- [07 — Int/Long Number Writing](./07-int-long-writing.md) — Overview of int/long writing techniques
- [09 — Jeaiii Division-Free Int/Long Writer](./09-jeaiii-fast-writer.md) — Consolidated explanation of jeaiii's reciprocal multiplication technique
- [`T10 — Jeaiii division-free int/long writer`](../t10-jeaiii-fast-writer.md) — Detailed implementation record
[`JsonNumberWriter`](../JsonNumberWriter.java) — Fory-style division-based writer (still used for compatibility)
