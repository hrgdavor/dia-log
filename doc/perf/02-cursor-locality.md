# 02 — Cursor locality: the writer-owns-buffer pattern

The single most important structural decision in the writer: **the event is
assembled into a reusable `byte[]` by code that keeps `buf`/`pos` in
registers**, instead of being pushed through an `OutputStream`.

## Why

An `OutputStream` hides its buffer. Every `write(int)`/`write(byte[])` is a
virtual call; the JIT cannot keep the destination position live across the
call, cannot see what bytes were written, and each call re-checks capacity.
Writing a log event field-by-field through `OutputStream` costs a virtual call
per token plus an `arraycopy` per token.

The alternative: pull the buffer and cursor into **stack locals**. The event
buffer is fixed-capacity (no-grow — see
[`t12-no-grow-negated-position-buffer.md`](../perf-exploration/t12-no-grow-negated-position-buffer.md)):
`limit` is computed once at the top of the assembly, and `buf`/`limit` never
change, so there is nothing to re-fetch:

```java
byte[] buf = rbo.buf;        // the reusable event buffer (fixed capacity)
int pos = 0;                 // the cursor
int limit = buf.length - RESERVE;   // hard boundary, computed once (T12)

// straight-line stores, all through the SAME locals
buf[pos++] = ',';
WriteOps.LE_LONG.set(buf, pos, KEY_LEVEL_W0);
pos += 8;
```

Now C2 sees `buf` and `pos` as register-resident values for the whole event:
stores are direct, capacity checks are one compare against `limit`, and the
JIT can inline every small static helper. The cursor is published back to
the buffer object exactly once at the end.

## The pattern, concretely

1. **Snap the cursor** at the start: `byte[] buf = rbo.buf; int pos = 0;`
   and compute the stable boundary `int limit = buf.length - RESERVE;`.
2. **Check capacity inline before a store group**, not per byte:
   `if (pos + need > limit) { /* finalize — replace the value or close */ }`
   The limit-aware writers do the check *between* SWAR blocks
   (`pos + LIMIT_MARGIN > limit`, one compare per 16-byte block, never per
   byte) and return the **negated position** (`-pos`) on overflow so the
   caller restores its cursor and finalizes (T12).
3. **Store directly** into `buf[pos..]` with byte stores and VarHandle word
   stores; advance `pos` by the bytes consumed.
4. **Never re-read the buffer.** `buf`/`limit` are stable — the buffer never
   reallocates, so there are no `buf = rbo.buf;` re-reads after a write.
5. **Publish once** at the end (`rbo.pos = pos`).

## The negated-position contract

Every variable-length writer targeting the no-grow buffer
(`WriteOps.writeEscapedJsonStringNoGrow`, `WriteOps.writeRawNoGrow`,
`DirectJsonStringWriter.writeJsonStringNoGrow`, `StringByteExtractor.writeLatin1NoGrow`)
returns the **new position** on success, or **`-pos`** (the pre-call
position, negated) on overflow:

```java
pos = writeEscapedJsonStringNoGrow(buf, pos, limit, value);
if (pos < 0) {
    pos = -pos;                            // restore pre-call position
    pos = writeTooLargeField(buf, pos);    // "V2BIG" — value overflow
}
```

Why negated (not `-1` or an output parameter):

- The caller accepts the result in the **same cursor local** — no separate
  "did it overflow?" boolean, no conditional assign, no extra parameter.
- On overflow, negating recovers the exact position before the write, so the
  caller overwrites from the right offset.
- **Partial buffer writes are harmless**: the chunked loop may store bytes
  past the returned position before the margin check fires, but `pos` is
  caller-owned and reverts to its pre-call value, so that buffer garbage is
  never seen or flushed. The buffer is never overflown — the chunked
  `pos + MARGIN > limit` check fires while a full chunk remains, and `MARGIN`
  exceeds the worst-case expansion of one chunk.

The intermediate body writers (`writeEscapedLatin1NoGrow`,
`writeEscapedCharsNoGrow`) return `-internalPos` after partial writes; the
quoted top-level (`writeJsonStringNoGrow`) normalizes to `-start`, which is
the magnitude callers rely on.

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
    throw new BufferFullException();       // no room for the ',' + key prefix
}
```

## Anti-patterns

- **Returning `int[]`/cursor objects from capacity checks** — forces C2 to
  treat `buf`/`pos` as heap-aliased; register residency is lost.
- **Hiding the cursor in a `ThreadLocal`** — hidden state, per-thread cost,
  re-entrancy corruption.
- **A helper method between the cursor and the byte stores in steady state** —
  the store path must be straight-line or a `static` method the JIT inlines.
- **Growing the event buffer** — reallocation breaks `buf`/`limit`
  register residency, allocates on the hot path, and forces re-reads. The
  buffer is fixed; an oversized value is replaced with the `"V2BIG"`
  placeholder or the object is closed (T12).

## What it buys, measured

End-to-end event writing through the direct cursor is ~2-3× faster than the
stream-mediated path at zero allocation (see
[`../perf-exploration/fory-perf-benchmark-results.md`](../perf-exploration/fory-perf-benchmark-results.md)
and the T7 records in [`doc/perf-exploration/`](../perf-exploration/)).
