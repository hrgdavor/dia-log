# T7 — Cursor-Locality Buffer Writer (Generalized Pattern)

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871))
generalized via design discussion for a reusable, composable buffer writer.

## What the pattern is

Fory's headline win is not SWAR or digit tables in isolation — it is moving the
write cursor from a heap-allocated object field to a **stack-local variable** for
the duration of a hot serialization loop. C2 can then keep `buffer` and
`position` in CPU registers, eliminating virtual dispatch on every value write,
cache-line reloads of cursor state, and redundant bounds checks. The capacity is
always the buffer's own length — the buffer object keeps no separate `limit`
field, so a grow never forces an extra field reload.

This technique generalizes beyond JSON to any workload that serializes primitives,
UTF-8 strings, or raw bytes into a `byte[]` for periodic or batch IO.

## The hot-loop shape

Every serialization method follows this exact structure:

```java
public void writeMessage(Message m, ReusableByteArrayOutputStream rbo) throws IOException {
    // --- Pull locals once (cursor lives in registers for the whole method) ---
    byte[] buf = rbo.buf;
    int pos = 0;
    int maxSize = MAX_MESSAGE_SIZE;

    // --- Single cold-path check (rare: once per several KB) ---
    if (pos + maxSize > buf.length) {
        if (pos > 0) {
            rbo.publish();
            pos = 0;
        }
        if (buf.length < maxSize) {
            buf = rbo.grow(maxSize);   // grow returns the (reallocated) buffer
        }
    }

    // --- Hot path: pure straight-line stores, zero method calls ---
    // Numbers write straight into the cursor at the current offset — no
    // scratch buffer, no bulk-copy.
    pos = JsonNumberWriter.writeInt(buf, pos, m.id);
    pos = JsonNumberWriter.writeLong(buf, pos, m.timestamp);

    // Strings use the SWAR + length-band writers (EscapedJsonStringWriter /
    // StringByteExtractor) writing straight into the backing array.
    pos = WriteOps.writeEscapedJsonString(buf, pos, m.name);

    // --- Publish cursor ONCE at the end ---
    rbo.pos = pos;
}
```

The critical detail: `rbo.pos = pos` is written **once per message** (or per
batch), not per field. The main loop owns the cursor; the
`ReusableByteArrayOutputStream` object is a carrier, not a controller.

## The anti-pattern: returning arrays or objects from capacity checks

```java
// BAD — destroys cursor locality
int[] result = rbo.ensure(pos, 4);
buf = result[0];  // forces alias analysis, escape analysis failure
pos = result[1];  // register spill
```

Returning an array forces the JIT to treat `buf` and `pos` as potentially
aliased heap objects. C2 must reload them from memory after the call, defeating
the register residency that makes the pattern fast.

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

`JsonNumberWriter.writeInt` / `writeLong` pack digits using `DIGIT_QUADS`
(4 digits per store) and `DIGIT_TRIPLES` (1–3 digits with a leading-zero skip)
and write straight into the writer's backing array at the caller offset — the
digits are written most-significant-first with one little-endian `LE_INT`
VarHandle store per 4-digit group, so there is no scratch buffer, no
`System.arraycopy` and no shift:

```java
pos = JsonNumberWriter.writeInt(buf, pos, value);
pos = JsonNumberWriter.writeLong(buf, pos, value);
```

Float/double formatting delegates to `RyuFloat` / `RyuDouble`, which already
write at a caller offset (`RyuFloat.writeFloat(value, buf, pos)`).

### Packed little-endian stores — `WriteOps`

`WriteOps.LE_LONG` (the `byte[]` VarHandle view) and `WriteOps.writePackedLE`
perform the packed stores with an inlined capacity check + switch-based
fall-through stores. For absolute-offset stores (T2 overlapping-store trick),
`LE_LONG.set(buf, pos, value)` writes at a given offset without moving the
cursor.

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

## Backing-type choice: why `byte[]` is the right (and likely best) option for `CursorBuffer`

The generalized cursor has to pick a backing type for the hot-path write buffer.
`byte[]` is the correct choice here, and it is *the* type that enables the
multi-byte-primitive optimization the project already uses for reads. The
alternatives (`long[]`, `ByteBuffer`, `Unsafe`) each introduce a regression that
matters in this specific workload.

### The JDK primitive that makes `byte[]` special

`MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder)` creates a
`VarHandle` that treats a `byte[]` as a view of `long[]` (and likewise `int[]`,
`short[]`). Through it you can read or write 8, 4, or 2 bytes at any offset with
**no alignment constraint** and **no intermediate array**. The codebase already
uses this for SWAR word loads:

```java
// EscapedJsonStringWriter / StringByteExtractor — loads only today
private static final VarHandle LE_WORD =
    MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
// ...
long w0 = (long) LE_WORD.get(bytes, i);
```

The same handle can be used for stores. The missing piece in the current code
is the *set* direction — `writePackedLE` and `putWordLE` still split a
prefilled `long` into eight manual byte stores. Adding a VarHandle-backed store
closes that gap:

```java
// WriteOps exposes the byte[]-view VarHandle directly (public field)
public static final VarHandle LE_LONG =
    MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

// hot writer: one full 8-byte store, cursor advanced by the bytes consumed
LE_LONG.set(buf, pos, v);
pos += n;
```

On little-endian (all modern x86/ARM), the JVM intrinsifies this to a single
8-byte store — exactly the "write a prefilled long instead of an array of
bytes" optimization, with zero copy and zero allocation.

### Why `byte[]` wins for dia-log's use case

| Criterion | `byte[]` | `long[]` / `LongBuffer` | `ByteBuffer` | `Unsafe` |
|---|---|---|---|---|
| Alignment constraint | **None** — byte-array view VarHandles are unaligned | 8-byte alignment required | 4-byte (heap) or none (direct), but indirect | None |
| Granularity | **Byte** — JSON is variable-length, byte-aligned | 8-byte words — forces padding/waste | Byte, but through a `put(int)` virtual call | Byte, but non-portable |
| Interop with `ReusableByteArrayOutputStream` | **Direct** — it already exposes `byte[] buffer()` | Requires copy/reinterpret | Requires `ByteBuffer.wrap` or allocate | Requires address arithmetic |
| Allocation on hot path | **Zero** — array is caller-owned, reused | Zero for array, but indirect view costs | `ByteBuffer` object + position/limit state | Zero, but unsafe |
| JIT friendliness | **Excellent** — `byte[]` is the canonical array type; C2 knows how to scalar-replace/escape-analyze it | OK, but alignment constraints hurt vectorization | Poor — `put()` is virtual; C2 deoptimizes hot buffers through it | Non-portable; JIT intrinsics vary |
| Already used for this | **Yes** — SWAR loads in `StringByteExtractor`/`EscapedJsonStringWriter` | No | No (only stream fallbacks) | No |

#### Detail on the alternatives

- **`long[]` backing.** A `long[]` would let you write a prefilled prefix in one
  store, but JSON output is byte-granular. A `long[]` forces every write into
  word-sized slots; you would pad every field to 8 bytes or maintain a separate
  byte cursor for the tails — both add branches and waste. More importantly,
  `ReusableByteArrayOutputStream` owns a `byte[]`; switching to `long[]` would
  require a full copy at the boundary (flush) and break every existing
  `writeRaw`/`writeByte` caller.

- **`ByteBuffer`** (heap or direct). Heap `ByteBuffer` wraps a `byte[]` but
  exposes a `put(byte)` / `putLong(long)` virtual API. Every value write becomes
  a virtual dispatch into the buffer's `put()` method, defeating the cursor-locality
  premise entirely (the cursor is now back inside an object, reloaded each call).
  Direct `ByteBuffer` avoids the virtual dispatch but introduces off-heap
  allocation and `Cleaner` pressure, plus a copy at the I/O boundary — exactly
  the batch-vs-stream trade this pattern exists to avoid.

- **`sun.misc.Unsafe`.** `Unsafe.putLong` *can* write unaligned longs and is
  intrinsified, but it is non-portable, blocked by the module system, and
  unnecessary. `MethodHandles.byteArrayViewVarHandle` is the supported,
  JIT-intrinsified, bounds-checked equivalent and is already on the project's
  classpath (used in `StringByteExtractor`/`EscapedJsonStringWriter`).

- **`MemorySegment` (Project Panama).** Too new for a baseline-JDK 25 project;
  also introduces indirection through the `SegmentScope` / `SegmentAllocator`
  abstractions that would fight the zero-allocation, single-threaded hot path.

### Practical consequence for this codebase

The hot path packs prefixes into `@CB.StrPacker` `long` constants and stores
them via `WriteOps.LE_LONG` (the `byte[]` VarHandle view), so the packed word
flows straight through with one `LE_LONG.set` store per 8-byte window:

```
KEY_X_W0/W1  ──▶  WriteOps.LE_LONG.set(buf, pos, word)
```

Same for SWAR clean-word bulk stores in `EscapedJsonStringWriter.writeEscapedLatin1Direct`:
instead of `putWordLE(buf, pos, w0)` (8 manual byte stores), `LE_LONG.set(buf, pos, w0)`.

The partial-width tails (`n < 8`) are refined in
`t8-packed-word-varhandle-stores.md`: the benchmark showed the full 8-byte store
+ partial cursor advance is flat across tail lengths, so the tails also use a
single VarHandle store instead of per-byte stores.

### Performance notes

- **Unaligned is free.** `byteArrayViewVarHandle` on a `byte[]` is not subject
  to alignment constraints (JDK spec). The JIT emits an unaligned 8-byte move
  on LE targets; on BE targets it swaps bytes — correct either way because the
  source word was packed little-endian.
- **No aliasing / no synchronization.** The cursor is `@NotThreadSafe` and
  single-threaded, so `LE_LONG.set` writes the same `buf` the local `pos`
  points at, with no data-race concerns.
- **Bounds-check behavior.** The VarHandle checks `pos + viewSize <= array.length`
  (the array length). The surrounding `ensure(8)` call guarantees there is room,
  so the VarHandle's check never triggers on the hot path — it is an
  uncommon-trip safety net only.
- **C2 inlining.** Static-final VarHandles are constant-folded; the JIT sees
  `LE_LONG.set` as a direct intrinsic, not a virtual call. This matches the
  existing `LE_WORD.get` inlining already proven in `StringByteExtractor` and
  `EscapedJsonStringWriter`.

### Summary

`byte[]` is the only backing type that simultaneously satisfies:

1. **Byte-granular output** (JSON is variable-length).
2. **Unaligned multi-byte primitive access** (via `byteArrayViewVarHandle`,
   already in the codebase for loads).
3. **Zero-copy, zero-allocation** stores for prefilled `long`/`int` values
   (packed field prefixes, SWAR clean words).
4. **Direct interop** with `ReusableByteArrayOutputStream.buffer()`.
5. **JIT-friendly** layout that keeps `buf`/`pos` in registers.

The store direction now uses the VarHandle too: `WriteOps.LE_LONG` (the
`byte[]` VarHandle view) is the public store handle, and the hot writers call
`LE_LONG.set(buf, pos, v)` directly — the last manual-split path for prefilled
words is gone (see `t8-packed-word-varhandle-stores.md`).


## Flushing strategy: lazy flush with cursor locality

Flushing to IO must not destroy cursor locality. The cold path handles it:

```java
if (pos + need > buf.length) {
    if (pos > 0) {
        rbo.publish();  // rare: once per several KB
        pos = 0;
    }
    if (buf.length < need) {
        buf = rbo.grow(need);   // grow returns the (reallocated) buffer
    }
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
assembly (T4) via `DirectJsonBuffer` — a reusable JSON-specific cursor spanning
the whole event. Fixed prefixes, numbers, and strings are assembled with
`buf`/`pos` live in registers. However, the codebase lacked a general-purpose,
composable utility layer for the underlying primitive stores; the JSON-specific
`DirectJsonBuffer` methods were the only reusable primitive path, so non-JSON
writers could not reuse the same local-cursor discipline.

**Now:** the pattern is extracted into two reusable classes:

- `core/.../ReusableByteArrayOutputStream` — the grow-capable carrier that owns
  the backing `byte[]` and cursor (`buf`/`pos` fields, write helpers
  `writeByte`/`writeRaw`/`writePackedLE`/`putPackedLE`, `ensure`/`publish`/`resync`,
  and `grow`, which returns the reallocated buffer). The capacity is always
  `buf.length`, so there is no separate `limit` field to maintain or fetch.
- `core/.../WriteOps` — a `final` facade over the existing optimized backends
  (`DirectJsonStringWriter`, `StringByteExtractor`, and the packed VarHandle
  stores). Number writing lives in `JsonNumberWriter` directly (`writeInt`/
  `writeLong`/`writeFloat`/`writeDouble` take a `byte[]` and offset, no scratch
  buffer). Every method returns the new `int pos` and takes only primitives; no
  heap state, no allocation on the hot path. Two shapes: pure `byte[] buf,
  int pos` overloads (capacity assumed) and grow-capable
  `ReusableByteArrayOutputStream` overloads (used by `JsonLogWriter`'s direct
  path).

`JsonLogWriter.writeJsonEventDirect` now assembles numbers via
`JsonNumberWriter` (`byte[]` + offset, no buffers) and strings via `WriteOps`,
preserving byte-identical output to the stream path.

## Verification principles

- **Byte-identical output:** every new path must match the reference stream path
  for all input combinations (clean ASCII, control chars, non-ASCII, empty,
  max-length).
- **Zero allocation:** the hot path must not allocate per field or per message.
  Allocation should remain at the cold-path boundary only (grow/flush). Numbers
  write straight into the cursor — no digit buffers.
- **Benchmark isolation:** micro benchmarks for each primitive store, plus an
  end-to-end event benchmark, to confirm that adding composable utilities does
  not regress the inline shape.
