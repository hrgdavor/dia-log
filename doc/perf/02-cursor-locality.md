# 02 — Cursor locality: the writer-owns-buffer pattern

The single most important structural decision in the writer: **the event is
assembled into a reusable `byte[]` by code that keeps `buf`/`pos`/`limit` in
registers**, instead of being pushed through an `OutputStream`.

## Why

An `OutputStream` hides its buffer. Every `write(int)`/`write(byte[])` is a
virtual call; the JIT cannot keep the destination position live across the
call, cannot see what bytes were written, and each call re-checks capacity.
Writing a log event field-by-field through `OutputStream` costs a virtual call
per token plus an `arraycopy` per token.

The alternative: pull the buffer and cursor into **stack locals**:

```java
byte[] buf = rbo.buf;     // the reusable event buffer
int pos = 0;              // the cursor
int limit = rbo.limit;    // capacity

// straight-line stores, all through the SAME locals
buf[pos++] = ',';
WriteOps.LE_LONG.set(buf, pos, KEY_LEVEL_W0);
pos += 8;
```

Now C2 sees `buf`, `pos`, `limit` as register-resident values for the whole
event: stores are direct, capacity checks are one compare, and the JIT can
inline every small static helper. The cursor is published back to the buffer
object exactly once at the end.

## The pattern, concretely

1. **Snap the cursor** at the start: `byte[] buf = rbo.buf; int pos = 0; int
   limit = rbo.limit;`.
2. **Check capacity inline before a store group**, not per byte:
   `if (pos + need > limit) { rbo.grow(pos + need); buf = rbo.buf; limit = rbo.limit; }`
   — the grow is the cold path, inside the `if`.
3. **Store directly** into `buf[pos..]` with byte stores and VarHandle word
   stores; advance `pos` by the bytes consumed.
4. **Re-read the cursor** after any call that can grow or publish
   (`buf = rbo.buf; limit = rbo.limit;`).
5. **Publish once** at the end (`rbo.pos = pos`).

## Why capacity is checked with the *whole word-slot* in mind

A packed key is stored as one 8-byte word per window, and the **overwrite
trick** writes a full 8-byte word even when only 1..7 bytes are meaningful (the
cursor then advances by the *real* length; the extra bytes are overwritten by
the next store or are past the flush boundary). Because of that, the capacity
check must reserve **whole 8-byte word slots** — `packedKeyBytes(len) =
(len + 7) & ~7` — not the literal key length. The generated `KEY_X_LEN_BUF`
constant *is* that rounded size, so the check reads a constant:

```java
if (pos + 1 + KEY_LOGGER_LEN_BUF > limit) {
    rbo.grow(pos + 1 + KEY_LOGGER_LEN_BUF);
    buf = rbo.buf;
}
```

## Anti-patterns

- **Returning `int[]`/cursor objects from capacity checks** — forces C2 to
  treat `buf`/`pos`/`limit` as heap-aliased; register residency is lost.
- **Hiding the cursor in a `ThreadLocal`** — hidden state, per-thread cost,
  re-entrancy corruption.
- **A helper method between the cursor and the byte stores in steady state** —
  the store path must be straight-line or a `static` method the JIT inlines.

## What it buys, measured

End-to-end event writing through the direct cursor is ~2-3× faster than the
stream-mediated path at zero allocation (see
[`../perf-exploration/fory-perf-benchmark-results.md`](../perf-exploration/fory-perf-benchmark-results.md)
and the T7 records in [`doc/perf-exploration/`](../perf-exploration/)).
