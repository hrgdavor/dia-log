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
