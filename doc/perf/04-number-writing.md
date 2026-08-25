# 04 — Number writing: bufferless, VarHandle digit stores

`int`/`long`/`float`/`double` values appear in every event (`ts`, `errHash`,
KV values). The naive path is `String.valueOf(...)` → allocation + UTF-8
encode. The fast path formats digits **directly into the destination buffer at
the caller offset** — no `String`, no boxed number, no scratch `byte[]`, no
`System.arraycopy`.

## The digit tables

Digit formatting uses precomputed ASCII tables (T5, ported from Fory):

- `DIGIT_QUADS[i]` packs the 4 ASCII digits of `i` little-endian into one
  `int` → one `LE_INT` VarHandle store writes 4 digits at once.
- `DIGIT_TRIPLES[i]` packs 1..3 significant digits plus a leading-zero skip
  count → one lookup + 1..3 byte stores for the first group.

## Writing left-to-right with a `POW10` slice

`writeLong(byte[] buf, int pos, long value)`:

1. count the digits (`decimalDigits` — a comparison ladder, 1..19);
2. slice the **most-significant group** with a `POW10[digits - lead]` divisor
   (a 1..3-digit group via `DIGIT_TRIPLES`, a 4-digit group via `LE_INT`);
3. emit each remaining 4-digit group most-significant-first with one
   `LE_INT.set(buf, pos, DIGIT_QUADS[chunk])` store.

Writing most-significant-first means the digits land **in place** — no
right-to-left build into a scratch tail and no `arraycopy` shift. The old
approach (build right-aligned into a reusable buffer, then `arraycopy`) touched
two memory regions per number; this touches one.

```java
public static int writeLong(byte[] buf, int pos, long value) {
    if (value == Long.MIN_VALUE) { /* LE_LONG/LE_INT stores of the constant */ }
    boolean negative = value < 0;
    long v = negative ? -value : value;
    if (negative) buf[pos++] = '-';
    // digits = decimalDigits(v); leading group via POW10; then LE_INT quads...
    return pos + <digits written>;
}
```

Edge cases are constants, not branches on the hot path: `Long.MIN_VALUE` is
stored with precomputed `LE_LONG`/`LE_INT` words; `Integer.MIN_VALUE` just
delegates to the long path; non-finite float/double writes `"null"` with one
`LE_INT` store.

`float`/`double` are formatted by Ryu, which already writes at a caller offset
(`RyuFloat.writeFloat(value, buf, pos)`), so they too need no scratch buffer.

## Zero allocation, verified

The production direct path formats every number with **0 B/op** allocation
(the `ForyPerfEventBenchmark.eventNewDirect` leg measures ≈ 10⁻³ B/op). The
`OutputStream` fallback overloads (API compatibility only) build into a small
per-call local `byte[]` — a documented 32–40 B/op trade-off on the fallback
path, never on the hot path.

## What it buys, measured

End-to-end event writing (the direct path) is 0.095 → 0.064 µs/op (−33%) vs
the recorded baseline, and number formatting is byte-identical to the
digit-by-digit reference across hundreds of random values. Details in
[`t9-bufferless-varhandle-number-writing.md`](../perf-exploration/t9-bufferless-varhandle-number-writing.md).

## Going further: division-free reciprocals (T10, `JeaiiiFastWriter`)

The T5/T9 writer still pays one hardware division per 4-digit group (the JIT
strength-reduces `/ 10000`, but it is still a multiply-shift chain). The jeaiii
technique (github.com/jeaiii/itoa) removes the division entirely: for a divisor
`d = 10^k`, `M = ceil(2^64 / d)` fits a positive `long`, and
`Math.multiplyHigh(v, M)` returns the exact quotient for every `v < 2^64 / d` —
a single `mulx`/high-word move on x86-64.

Two details make it work in practice:

- **Division-free `long` split.** A 19-digit `long` cannot be divided by `10^9`
  with a plain 64-bit reciprocal (the dividend exceeds the exactness bound
  `2^64 / d`). Instead: `hi = multiplyHigh(u, M9)` is `floor(u / 10^9)` or one
  too high, and a single signed-remainder repair (`if (lo < 0) { hi--; lo +=
  10^9; }`) makes the split exact. Each half then fits the exactness bound.
- **Trailing-zero leading group.** `DIGIT_QUADS` is fixed 4 digits and cannot
  skip leading zeros, so the 1..3 digit leading group uses a *right-aligned*
  table (`TRAILING_TRIPLES`): one `LE_INT` store writes the significant digits
  first and a trailing `'0'` that is overwritten by the next group or lies past
  the returned position. The caller's buffer must leave ≥ 4 bytes of room at
  the offset (the production callers already check
  `pos + MAX_INT_BYTES / MAX_LONG_BYTES > limit` before the store). This is the
  same "full-store/partial-advance" overwrite trick as T8.

The digit count is decided by a comparison ladder (`< 10 / < 100 / < 1000 /
< 10000`) — for short values those 1–4 well-predicted branches are cheaper than
any branchless alternative, and short values (line numbers, levels, durations)
are exactly the case that must be fast.

Result (isolated writer benchmark, ns/op): int `medium` 6.21 → 2.75, long
`timestamp` 7.89 → 4.40 vs `JsonNumberWriter`, and `jeaiiiQuad` is fastest on
every distribution for both int and long. Details:
[`t10-jeaiii-fast-writer.md`](../perf-exploration/t10-jeaiii-fast-writer.md).
