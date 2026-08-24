# T11 — `writeValueDirect` return-value cursor locality

**Source technique:** Novel dia-log pattern (generalization of the T7
return-value cursor-locality discipline to the value-type dispatch in
`JsonLogWriter.writeValueDirect`).

## What dia-log did before

`writeValueDirect` operated on the `rbo.pos` **field** directly, and the only
call site (the KV-pair loop in `writeJsonEventDirect`) had to round-trip the
cursor through the field on every value:

```java
// JsonLogWriter.java (before)
buf[pos++] = ':';
rbo.pos = pos;                       // field round-trip: register -> heap
writeValueDirect(rbo, kvPair.value, mapper);
pos = rbo.pos;                       // field round-trip: heap -> register
buf = rbo.buf;                       // capacity had to be fetched separately
```

```java
private void writeValueDirect(ReusableByteArrayOutputStream rbo, Object value, ObjectMapper mapper) throws IOException {
    switch (value) {
        case String s -> WriteOps.writeEscapedJsonString(rbo, s);   // reads/writes rbo.pos
        case Long l    -> writeLongDirect(rbo, l);                  // reads/writes rbo.pos
        ...
        case Boolean b -> {
            rbo.ensure(JSON_TRUE_LEN_BUF);
            WriteOps.LE_LONG.set(rbo.buf, rbo.pos, JSON_TRUE_W0);    // field read
            rbo.pos += JSON_TRUE_LEN;                               // field write
        }
        ...
    }
}
```

Every value write forced a `register -> heap -> register` cursor sync and the
JIT could not keep `pos` resident across the helper chain, because the value
itself is heap-allocated and each branch re-read `rbo.pos` from the object.

## What dia-log does now

The cursor is a **primitive caller-owned local** that is passed in and returned,
so the call site is a single clean return-value update with no field round-trip
(`JsonLogWriter.java:298`):

```java
buf[pos++] = ':';
pos = writeValueDirect(rbo, pos, kvPair.value, mapper);   // pos stays in a register
buf = rbo.buf;                                            // capacity is buf.length
```

```java
private static int writeValueDirect(ReusableByteArrayOutputStream rbo, int pos, Object value, ObjectMapper mapper) throws IOException {
    switch (value) {
        case String s -> pos = writeEscapedJsonString(rbo, pos, s);
        case Long l    -> pos = writeLongDirect(rbo, pos, l);
        ...
        case Boolean b -> {
            rbo.pos = pos;                                  // one commit before grow/store
            if (b) {
                rbo.ensure(JSON_TRUE_LEN_BUF);
                WriteOps.LE_LONG.set(rbo.buf, pos, JSON_TRUE_W0);
                pos += JSON_TRUE_LEN;
            } else { ... }
        }
        ...
    }
    return pos;
}
```

The grow-capable backends (`WriteOps.writeEscapedJsonString(rbo, ...)`,
`JsonNumberWriter.writeInt(rbo.buf, pos, value)`, jackson `publish`/`resync`)
still live on `rbo.pos`, so each branch commits the local cursor once
(`rbo.pos = pos`) before a grow/publish and reads the advanced value back — a
single sync per value instead of a sync per byte. The number helpers pass the
local `pos` straight into `JsonNumberWriter.write*(buf, pos, value)` and return
the new `pos`, never touching `rbo.pos` on the steady-state store.

## Why it is faster

- **Register residency across the loop:** the only place the cursor leaves a
  register is the deliberate single `rbo.pos = pos` commit at each value's
  grow/publish boundary. The caller's `pos` stays in a CPU register through the
  `':'` store and the value dispatch, exactly the T7 return-value pattern.
- **No per-value field round-trip:** previously every KV value forced two heap
  stores/loads of `pos` (`rbo.pos = pos; ...; pos = rbo.pos`). Eliminated.
- **Lean static helpers:** `writeValueDirect` and its sub-helpers are now
  `static`, take a primitive `int pos` and return it — trivial for C2 to inline
  (no virtual dispatch, no `State` object).
- **Identical bytes:** the change is purely a cursor-carriage refactor; output is
  byte-for-byte unchanged (verified by `JsonLogWriterDirectBufferTest`).

## Verification

- `JsonLogWriterDirectBufferTest` asserts the direct-buffer path is
  byte-identical to the stream fallback across every value type (`String`,
  `CharSequence`, `Character`, `Enum`, `RawValue`, `Long`/`Integer`/`Short`/`Byte`,
  `Float`/`Double`, `BigDecimal`, `Boolean`, `RawJsonSelfWriter`,
  `RawJsonBytes`, and the `default` jackson path) — including grow-to-fit
  capacities (8-byte buffer) and throwable/stack/errHash fields.
- Full suite: `core` 624 tests + `logback` 111 tests pass.
