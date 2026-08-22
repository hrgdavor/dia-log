# T3 — Inlined Capacity Checks (`ensure` → local check + `grow`)

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871)).
Fory files: `java/fory-json/.../writer/Utf8JsonWriter.java` and
`writer/StringJsonWriter.java` — every `ensure(n)` call replaced by an inlined
local check, and `grow` redefined to take the incremental byte count.

## What Fory does

Fory's writers previously guarded every write with `ensure(additional)`, a
shared helper that computed `minCapacity = position + additional` and grew when
needed. The commit removes the helper and inlines the check at every call site:

```java
// before
ensure(prefix.length + 5);
writeRawLatin1NoEnsure(prefix);

// after
int additional = prefix.length + 5;
if (position + additional > buffer.length) {
    grow(additional);
}
writeRawLatin1NoEnsure(prefix);

// grow computes the absolute capacity only on the cold path
private void grow(int additional) {
    int minCapacity = position + additional;
    buffer = Arrays.copyOf(buffer, growCapacity(buffer.length, minCapacity));
}
```

Single-byte writes specialize even further (Fory diff, `writeComma` /
`writeByteRaw`):

```java
int pos = position;
if (pos == buffer.length) {
    grow(1);
}
buffer[pos] = (byte) ',';
position = pos + 1;
```

Fory's own comment (diff lines 9431–9434 / 10834–10837) states the rationale:
*"The local check lets hot methods reuse their position cursor; only the cold
path computes the absolute capacity from the incremental byte count."*

## Why it is faster

- The common no-grow case is a single compare + branch **inside** the hot
  method, with `position` and `buffer` already live in registers — no call,
  no re-load of the cursor from the heap.
- C2 can keep the writer state visible across a whole field/value sequence
  instead of reloading it after each helper call.
- The grow path (never taken in steady state with a 1 MiB event buffer)
  computes the absolute capacity only when it actually runs.

## What dia-log did before / does now

`ReusableByteArrayOutputStream` (the production event buffer) already had the
spirit of this: `write(int)` checks `count == buf.length` inline and
`write(byte[], off, len)` checks `count + len > buf.length` inline. What was
missing was the same discipline in the *new* direct-buffer API added for T4,
and in the writers that use it:

- `ReusableByteArrayOutputStream.writeLongPrefixLE` / `writeIntPrefixLE` /
  `writeRaw` (T4) each perform one inlined
  `if (pos + n > buf.length) grow(pos + n);` — the grow takes the absolute
  capacity (this class's existing `grow(int need)` contract), which is the
  dia-log adaptation of Fory's "grow by additional".
- `grow(int)` is now package-private so the same-package direct-buffer writers
  (`EscapedJsonStringWriter`, `StringByteExtractor`) can perform their own
  local checks + grow with the buffer in hand.
- The direct-mode writers do exactly Fory's local-check pattern: one check per
  clean band/block, `pos + 6` per escaped byte, `pos + 2` per UTF-8 expansion,
  publishing the cursor once with `setPosition`.

## Verification

- `ReusableByteArrayOutputStreamDirectApiTest` covers the packed-store helpers
  and their growth behavior (including growth past the initial capacity).
- The old "ensure-style" alternative is represented by
  `hr.hrg.dialog.core.perf.StreamMediatedWriter`, which routes every chunk
  through the virtual `OutputStream` API (the pre-T4 behavior) and is used as
  the benchmark baseline (`prefixesStreamMediated` vs `prefixesPackedDirect`).
