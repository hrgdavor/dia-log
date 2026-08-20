# T7 — Cursor-Locality Buffer Writer (Generalized Pattern)

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871))
generalized via design discussion for a reusable, composable buffer writer.

## What the pattern is

Fory's headline win is not SWAR or digit tables in isolation — it is moving the
write cursor from a heap-allocated object field to a **stack-local variable** for
the duration of a hot serialization loop. C2 can then keep `buffer`, `position`,
and `limit` in CPU registers, eliminating virtual dispatch on every value write,
cache-line reloads of cursor state, and redundant bounds checks.

This technique generalizes beyond JSON to any workload that serializes primitives,
UTF-8 strings, or raw bytes into a `byte[]` for periodic or batch IO.

## The hot-loop shape

Every serialization method follows this exact structure:

```java
public void writeMessage(Message m, CursorBuffer cb) throws IOException {
    // --- Pull locals once (cursor lives in registers for the whole method) ---
    byte[] buf = cb.buf;
    int pos = cb.pos;
    int limit = cb.limit;
    int maxSize = MAX_MESSAGE_SIZE;

    // --- Single cold-path check (rare: once per several KB) ---
    if (pos + maxSize > limit) {
        if (pos > 0) {
            cb.sink.write(buf, 0, pos);
            pos = 0;
        }
        if (buf.length < maxSize) {
            buf = new byte[Math.max(buf.length << 1, maxSize)];
            limit = buf.length;
        }
        cb.buf = buf;
        cb.limit = limit;
    }

    // --- Hot path: pure straight-line stores, zero method calls ---
    // Use existing optimized writers via WriteOps or inline equivalents.
    // Numbers use JsonNumberWriter.buildInt/buildLong into caller-owned buffers,
    // then bulk-copy at the cursor position (no per-byte shift stores).
    pos = WriteOps.writeInt(buf, pos, m.id, intBuffer);
    pos = WriteOps.writeLong(buf, pos, m.timestamp, longBuffer);

    // Strings use the SWAR + length-band writers (EscapedJsonStringWriter /
    // StringByteExtractor) writing straight into the backing array.
    pos = WriteOps.writeEscapedJsonString(buf, pos, m.name);

    // --- Publish cursor ONCE at the end ---
    cb.pos = pos;
}
```

The critical detail: `cb.pos = pos` is written **once per message** (or per
batch), not per field. The main loop owns the cursor; the `CursorBuffer` object
is a carrier, not a controller.

## The anti-pattern: returning arrays or objects from capacity checks

```java
// BAD — destroys cursor locality
int[] result = cb.ensure(pos, 4);
buf = result[0];  // forces alias analysis, escape analysis failure
pos = result[1];  // register spill
```

Returning an array forces the JIT to treat `buf`, `pos`, and `limit` as
potentially aliased heap objects. C2 must reload them from memory after the call,
defeating the register residency that makes the pattern fast.

The correct alternatives:

1. **Inline the cold path** (best): raw flush/grow logic inside the `if` block,
   no helper method at all.
2. **Primitive return** (acceptable): `int ensureCapacityAndFlush(...)` returns a
   primitive `int` — zero allocation, zero alias risk, stays in a register. C2
   treats the enclosing branch as uncommon and keeps locals resident.

## Reusing existing optimized implementations

The dia-log codebase already contains highly optimized primitives that fit the
cursor-locality pattern. A generalized `WriteOps` facade should delegate to
them, not replace them:

### Number writing — `JsonNumberWriter`

`JsonNumberWriter.buildInt` / `buildLong` already pack digits using
`DIGIT_QUADS` (4 digits per store) and `DIGIT_TRIPLES` (1–3 digits with a
leading-zero skip) into caller-owned reusable buffers. The output is end-aligned
(`buf[MAX_BYTES - len .. MAX_BYTES)`). A `WriteOps` wrapper bulk-copies the
result into the writer's backing array at the current cursor position:

```java
public static int writeInt(byte[] buf, int pos, int value, byte[] intBuf) {
    int len = JsonNumberWriter.buildInt(intBuf, value);
    System.arraycopy(intBuf, MAX_INT_BYTES - len, buf, pos, len);
    return pos + len;
}
```

Float/double formatting delegates to `RyuFloat` / `RyuDouble` (already used by
`JsonNumberWriter.writeFloat` / `writeDouble`).

### Packed little-endian stores — `DirectJsonBuffer` / `ReusableByteArrayOutputStream`

`DirectJsonBuffer.writePackedLE(long, int)` and `ReusableByteArrayOutputStream.writeLongPrefixLE`
already perform an inlined capacity check + switch-based fall-through stores.
For absolute-offset stores (T2 overlapping-store trick), `DirectJsonBuffer.putPackedLE`
writes at a given offset without moving the cursor.

### String writing — `EscapedJsonStringWriter` / `StringByteExtractor`

Both classes already have direct-buffer modes that use SWAR word scans and
length-band dispatch, writing straight into a caller-provided `byte[]`. A
`WriteOps` wrapper supplies the current cursor position and updates it after
validation.

## Composable utilities without breaking locality

Utility methods can be used if they accept **only primitives** (`byte[]`, `int`,
`long`) plus caller-owned reusable buffers, and return a primitive `int` (the
new position). They must never accept a `CursorBuffer` object or write to heap
state:

```java
public final class WriteOps {
    private WriteOps() {}

    public static int writeInt(byte[] buf, int pos, int v, byte[] intBuf) { ... }
    public static int writeLong(byte[] buf, int pos, long v, byte[] longBuf) { ... }
    public static int writeFloat(byte[] buf, int pos, float v, byte[] floatBuf) { ... }
    public static int writeDouble(byte[] buf, int pos, double v, byte[] doubleBuf) { ... }
    public static int writeEscapedJsonString(byte[] buf, int pos, String s) { ... }
    public static int writeLatin1(byte[] buf, int pos, String s) { ... }
    public static int writeRaw(byte[] buf, int pos, byte[] src, int off, int len) { ... }
}
```

Because these are thin wrappers around already-inlined backends, C2 inlines the
`WriteOps` calls too. The caller compiles to the same machine code as calling
the backends directly, while the source remains composable.

## Flushing strategy: lazy flush with cursor locality

Flushing to IO must not destroy cursor locality. The cold path handles it:

```java
if (pos + need > limit) {
    if (pos > 0) {
        cb.sink.write(buf, 0, pos); // rare: once per several KB
        pos = 0;
    }
    if (buf.length < need) {
        buf = new byte[Math.max(buf.length << 1, need)];
        limit = buf.length;
    }
    cb.buf = buf;
    cb.limit = limit;
}
```

`flush` is a single method call outside the per-field loop, so its overhead is
amortized. After flushing, the caller continues with the refreshed locals.

## Why it is faster

| Approach | Cursor location | Calls per field | Flush interaction |
|---|---|---|---|
| `DataOutputStream.writeInt()` | heap object field | 1 virtual call | immediate, no batching |
| `BufferedOutputStream` + `DataOutput` | heap object field | 2 virtual calls | flush only at boundary |
| **Cursor-locality writer** | **stack local** | **0 (inlined)** | flush only on demand, cursor not reloaded |

The counter-intuitive part: in `OutputStream`, the cursor is hidden inside the
stream object. Every `write` forces a load of that field (cache-miss potential)
and a store back. By pulling it into a local, the CPU sees a single contiguous
stream of writes — perfect for prefetching and store-buffer coalescing.

## What dia-log did before / does now

**Before:** `JsonLogWriter.writeJsonEvent` already applied writer-owns-buffer
assembly (T4) via `DirectJsonBuffer` — a reusable cursor spanning the whole
event. Fixed prefixes, numbers, and strings are assembled with `buf`/`pos` live
in registers. However, the codebase lacked a general-purpose, composable utility
layer for the underlying primitive stores; each writer duplicated the shift-and-
store pattern inline or called the JSON-specific `DirectJsonBuffer` methods
directly.

**Now:** The generalized pattern is documented and ready for extraction into a
shared `WriteOps` utility that wraps the existing optimized backends
(`JsonNumberWriter`, `DirectJsonBuffer`, `ReusableByteArrayOutputStream`,
`EscapedJsonStringWriter`, `StringByteExtractor`). New hot-path writers (e.g.,
stack-trace sanitizers, future binary protocol writers) can compose from the same
primitives while preserving the local-cursor discipline already proven in T4.

## Verification principles

- **Byte-identical output:** every new path must match the reference stream path
  for all input combinations (clean ASCII, control chars, non-ASCII, empty,
  max-length).
- **Zero allocation:** the hot path must not allocate per field or per message.
  Allocation should remain at the cold-path boundary only (grow/flush). The
  caller-owned digit buffers are reused across calls.
- **Benchmark isolation:** micro benchmarks for each primitive store, plus an
  end-to-end event benchmark, to confirm that adding composable utilities does
  not regress the inline shape.
