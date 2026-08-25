# No-grow `ReusableByteArrayOutputStream` + `"V2BIG"` placeholder — overview

Goal: a fixed-size, non-resizing event buffer (default **16 MiB**, configurable
per appender). When a value overflows it is replaced by the JSON literal
`"V2BIG"`, so most top-level keys are still written; when a field's key no longer
fits, the object closes with `}`. `grow()` / `ensure()` are removed from
`ReusableByteArrayOutputStream`, and the three writers that used them
(`StringByteExtractor`, `DirectJsonStringWriter`, `EscapedJsonStringWriter`)
become no-grow.

This plan is split into one file per standalone step — each is independently
implementable and testable, **in order**:

| # | File | Title | Prereqs |
|---|------|-------|---------|
| 1 | [`no-grow-jsonlogwriter-placeholder/01-appender-buffer-capacity.md`](no-grow-jsonlogwriter-placeholder/01-appender-buffer-capacity.md) | Appender buffer-capacity config | none |
| 2 | [`no-grow-jsonlogwriter-placeholder/02-bufferfull-exception-no-grow-buffer.md`](no-grow-jsonlogwriter-placeholder/02-bufferfull-exception-no-grow-buffer.md) | `BufferFullException` + no-grow buffer | 1 |
| 3 | [`no-grow-jsonlogwriter-placeholder/03-limit-aware-writers.md`](no-grow-jsonlogwriter-placeholder/03-limit-aware-writers.md) | Limit-aware writers (SWAR preserved) | 1–2 |
| 4 | [`no-grow-jsonlogwriter-placeholder/04-limit-aware-writeops.md`](no-grow-jsonlogwriter-placeholder/04-limit-aware-writeops.md) | Limit-aware `WriteOps` API | 1–3 |
| 5 | [`no-grow-jsonlogwriter-placeholder/05-jsonlogwriter-event-assembly.md`](no-grow-jsonlogwriter-placeholder/05-jsonlogwriter-event-assembly.md) | `JsonLogWriter.writeJsonEventDirect` no-grow assembly | 1–4 |
| 6 | [`no-grow-jsonlogwriter-placeholder/06-test-benchmark-cleanup.md`](no-grow-jsonlogwriter-placeholder/06-test-benchmark-cleanup.md) | Test/benchmark cleanup | 1–5 |

Review/proposals: [`no-grow-jsonlogwriter-placeholder-refinement.md`](no-grow-jsonlogwriter-placeholder-refinement.md).

## Shared conventions (read once, apply everywhere)

### Reservation / `limit` contract

- `RESERVE = JsonNumberWriter.MAX_DOUBLE_BYTES (25) + 1 (for '}') + 1 (for '\n') = 27`.
  Also exceeds `"V2BIG".length` (7), so one placeholder **and** any number always
  fit in the tail.
- `int limit = buf.length - RESERVE;` computed **once** at the top of
  `writeJsonEventDirect`. `limit` is stable — `buf` never reallocates.
- Writers must not read `buf.length` and must not grow; `limit` is the hard boundary.
- The closing `'}'` and `'\n'` always fit (the `+1` `+1` in `RESERVE`).

### Negated-position contract

All limit-aware methods return the **new position** on success, or **`-pos`** (the
pre-call position, negated) on overflow. The caller accepts the result in the same
cursor local:

```java
pos = writeEscapedJsonStringNoGrow(buf, pos, limit, value);
if (pos < 0) {
    pos = -pos;                           // restore pre-call position
    pos = writeTooLargeField(buf, pos);   // "V2BIG" — value overflow
}
```

Partial writes past the returned position are harmless: `pos` is caller-owned and
reverts on overflow, so buffer garbage is never seen or flushed.

### Packed-long overwrite trick (three invariants)

Used for every fixed-string literal (`KEY_X_W0..`, `PLACEHOLDER_W0`,
`JSON_NULL_W0`, …):

1. Full 8-byte store per word (never a partial tail store).
2. Reserve a full 8-byte slot (`*_LEN_BUF` = byte length rounded up to 8) in capacity checks.
3. Advance `pos` by the meaningful length, not 8.

### `LIMIT_MARGIN = 1024`

Headroom for one worst-case SWAR block (96 B JSON-escape, 32 B Latin-1→UTF-8).
The check `pos + LIMIT_MARGIN > limit` goes **between** SWAR blocks, never inside.

### The two finalizers

- `writeTooLargeAndClose(buf, pos)` → writes `}` (1 byte). A field's key doesn't
  fit; skip the field and close the object.
- `writeTooLargeField(buf, pos)` → writes `"V2BIG"` (7 bytes,
  `PLACEHOLDER_W0 = 0x0022474942325622L`). Value overflow; object stays open.
