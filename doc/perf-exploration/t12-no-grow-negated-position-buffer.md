# T12 — Fixed-capacity no-grow event buffer with negated-position writers

**Source technique:** Novel dia-log pattern (buffer management). Planned in
[`plans/no-grow-jsonlogwriter-placeholder.md`](../../plans/no-grow-jsonlogwriter-placeholder.md)
and implemented across its steps 1–5; this record documents the completed
foundation (steps 1–4) and the event assembly (step 5) that builds on it.

## What Fory does (upstream source)

Fory's `Utf8JsonWriter` (Apache Fory commit 585eb16f, PR #3871) uses a
grow-on-demand buffer: `getBuffer()`/`getPosition()`/`setPosition()` around a
`byte[]` that reallocates when an event exceeds capacity. The writer owns the
buffer, but the buffer's identity can change mid-event, so callers re-read it
after any grow. `dia-log`'s pre-no-grow `ReusableByteArrayOutputStream`
followed the same shape: `grow(int)`, `ensure(int)`, `publish()` and
`resync()` managed a backing array that reallocated to the longest event.

## What dia-log did before

`ReusableByteArrayOutputStream` (pre-T12) grew to fit:

```java
// ReusableByteArrayOutputStream (before): write methods grow on demand
public void write(byte[] b, int off, int len) {
    if (pos + len > buf.length) { buf = grow(pos + len); }   // reallocates
    System.arraycopy(b, off, buf, pos, len);
    pos += len;
}
```

The event assembly (`JsonLogWriter.writeJsonEventDirect`) therefore checked
`if (pos + need > buf.length) { buf = rbo.grow(pos + need); limit = buf.length; }`
before every store group and re-read `buf = rbo.buf; limit = buf.length;`
after every variable-length write — the buffer could have been reallocated
under it. `publish()`/`resync()` synced the cursor around stream-mediated
delegations.

Costs: a reallocation + copy on any event exceeding the current capacity
(rare but unbounded), the re-read after every variable-length write, and the
inability to *finish* an oversized event — the buffer just kept growing.

## What dia-log does now

**Fixed capacity, no reallocation.** The backing array is allocated once
(`ReusableByteArrayOutputStream` constructor; appenders configure it via
`setEventBufferCapacity`, default 16 MiB) and never changes. All write methods
became no-grow: on overflow they throw
`hr.hrg.dialog.core.BufferFullException` (unchecked, so hot-path helpers stay
free of `throws`; catchable at the `JsonLogWriter` boundary, where a forgotten
catch propagates to the appender's `writeOut` for logback error handling).
`grow(int)`/`ensure(int)`/`publish()`/`resync()` were deleted in place.

**The negated-position contract.** The limit-aware writers added in steps 3–4
return the **new position** on success or **`-pos`** (the pre-call position,
negated) on overflow, so the caller accepts the result in the same cursor
local and recovers its pre-call position by negating:

```java
// WriteOps (now)
public static int writeEscapedJsonStringNoGrow(byte[] buf, int pos, int limit, String s) {
    if (s == null) {
        if (pos + JSON_NULL_LEN_BUF > limit) return -pos;
        LE_LONG.set(buf, pos, JSON_NULL_W0);      // packed "null" (T6/T8)
        return pos + JSON_NULL_LEN;
    }
    return DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, limit, s);
}
```

Partial writes past the returned position are harmless: `pos` is a
caller-owned local that reverts on overflow, so the buffer garbage is never
seen or flushed.

**One check per SWAR block.** The string writers keep their SWAR band
structure and add a `LIMIT_MARGIN = 1024` guard *between* blocks
(`pos + LIMIT_MARGIN > limit`), never inside: a 16-byte JSON-escape block
expands to at most 96 bytes, an 8-byte Latin-1 word to at most 16, both far
under 1024. The per-byte fallback (dirty blocks only, rare for log content)
keeps its own exact checks.

**The event assembly finalizes instead of growing** (`writeJsonEventDirect`
returns the final position; the appender resets the buffer, stores the
returned position into `rbo.pos`, appends the newline and flushes):
- value overflow → the caller restores `pos` and writes the packed `"V2BIG"`
  literal (`writeTooLargeField`) — the object stays open;
- a field key that no longer fits → `writeTooLargeAndClose` writes `}` — the
  already-written fields remain valid JSON;
- a buffer too small even for the ts prefix → a minimal `{}`.

Key files: `core/.../ReusableByteArrayOutputStream.java`,
`core/.../BufferFullException.java`, `core/.../WriteOps.java`,
`core/.../DirectJsonStringWriter.java` (no-grow overloads + `LIMIT_MARGIN`),
`core/.../StringByteExtractor.java` (`writeLatin1NoGrow`),
`logback/.../JsonLogWriter.java` (no-grow assembly + the two finalizers),
`logback/.../JsonLogWriterDev.java` (direct-buffer `writeExtraFields`),
`logback/.../JsonAppender.java` / `JsonAppenderRolling.java`
(`setEventBufferCapacity`, caller-owned cursor after `writeJsonEventDirect`).

## Why it is faster

- **No reallocation, no re-reads.** `buf`/`limit` are stable for the whole
  event, so the assembly snapshots them once into locals and C2 keeps them in
  registers — the re-read after every variable-length write is gone.
- **No allocation on overflow.** An oversized value is replaced by the packed
  `"V2BIG"` literal (one 8-byte store) instead of triggering a buffer
  reallocation; the remaining fields still serialize.
- **One compare per SWAR block.** The margin guard replaces per-byte/per-store
  capacity checks on the hot path; the per-byte fallback runs only for dirty
  blocks.
- **Unchecked exception keeps helpers lean.** `BufferFullException` is
  unchecked, so the no-grow write methods carry no `throws` clauses and stay
  inline-friendly; the single catch site is the event-assembly boundary.

The deliberate tradeoff: the margin guard is conservative — a long string is
only admitted while ≥ 1024 bytes of headroom remain, so on a 16 MiB buffer the
last 1024 bytes of capacity are unusable for variable-length values (0.006 %,
not configurable). The `-0 == 0` edge of the negated convention means overflow
is only detectable from position 0 upward; production call sites always write
after a key prefix (pos > 0).

## Verification

- `ReusableByteArrayOutputStreamTest` / `ReusableByteArrayOutputStreamDirectApiTest`:
  every write method throws `BufferFullException` past capacity, all-or-nothing
  (no partial copy, buffer length unchanged).
- `NoGrowWritersTest`: byte-identity against the pure `WriteOps` oracle across
  all escape samples and every length band 0..50; the negated-position
  contract — a limit sweep asserts "result ≤ limit or == -inputPos"; exact-fit
  and conservative-overflow cases; long strings through the 16-byte block loop
  and 8-byte word loop.
- `JsonLogWriterDirectBufferTest`: byte-identical direct-vs-stream output for
  every event shape (the no-grow path must not change normal-event bytes);
  tiny buffers assert `BufferFullException`.
- `JsonAppenderTest` / `JsonAppenderRollingTest`:
  `setEventBufferCapacity` validation (≥ 64, default 16 MiB), buffer does not
  grow after an event.
- `mvn -o -pl core,logback test` — full suite green (642 core + 117 logback).
