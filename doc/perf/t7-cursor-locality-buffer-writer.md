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
    buf[pos++] = (byte) (m.id >> 24);
    buf[pos++] = (byte) (m.id >> 16);
    buf[pos++] = (byte) (m.id >> 8);
    buf[pos++] = (byte) m.id;

    byte[] utf8 = m.nameBytes;
    System.arraycopy(utf8, 0, buf, pos, utf8.length);
    pos += utf8.length;

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

## Composable utilities without breaking locality

Utility methods can be used if they accept **only primitives** (`byte[]`, `int`,
`long`) and return a primitive `int` (the new position). They must never accept a
`CursorBuffer` object or write to heap state:

```java
public final class WriteOps {
    private WriteOps() {}

    public static int writeInt(byte[] buf, int pos, int v) {
        buf[pos]   = (byte)(v >> 24);
        buf[pos+1] = (byte)(v >> 16);
        buf[pos+2] = (byte)(v >> 8);
        buf[pos+3] = (byte)v;
        return pos + 4;
    }

    public static int writeLong(byte[] buf, int pos, long v) {
        buf[pos]   = (byte)(v >> 56);
        buf[pos+1] = (byte)(v >> 48);
        buf[pos+2] = (byte)(v >> 40);
        buf[pos+3] = (byte)(v >> 32);
        buf[pos+4] = (byte)(v >> 24);
        buf[pos+5] = (byte)(v >> 16);
        buf[pos+6] = (byte)(v >> 8);
        buf[pos+7] = (byte)v;
        return pos + 8;
    }

    public static int writeUTF8(byte[] buf, int pos, byte[] utf8) {
        System.arraycopy(utf8, 0, buf, pos, utf8.length);
        return pos + utf8.length;
    }
}
```

Because these are tiny (`< 35 bytecodes`), C2 inlines them unconditionally. The
caller compiles to the same machine code as hand-written stores, while the source
remains composable.

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

| Approach                              | Cursor location   | Calls per field | Flush interaction                         |
| ------------------------------------- | ----------------- | --------------- | ----------------------------------------- |
| `DataOutputStream.writeInt()`         | heap object field | 1 virtual call  | immediate, no batching                    |
| `BufferedOutputStream` + `DataOutput` | heap object field | 2 virtual calls | flush only at boundary                    |
| **Cursor-locality writer**            | **stack local**   | **0 (inlined)** | flush only on demand, cursor not reloaded |

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
store pattern inline.

**Now:** The generalized pattern is documented and ready for extraction into a
shared `WriteOps` utility. New hot-path writers (e.g., stack-trace sanitizers,
future binary protocol writers) can compose from the same primitives while
preserving the local-cursor discipline already proven in T4.

## Verification principles

- **Byte-identical output:** every new path must match the reference stream path
  for all input combinations (clean ASCII, control chars, non-ASCII, empty,
  max-length).
- **Zero allocation:** the hot path must not allocate per field or per message.
  Allocation should remain at the cold-path boundary only (grow/flush).
- **Benchmark isolation:** micro benchmarks for each primitive store, plus an
  end-to-end event benchmark, to confirm that adding composable utilities does
  not regress the inline shape.
