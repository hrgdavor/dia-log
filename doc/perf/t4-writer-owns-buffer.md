# T4 — Writer-Owns-Buffer Direct API

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871)).
Fory files: `java/fory-json/.../writer/Utf8JsonWriter.java` (`@Internal`
`getBuffer()`, `getPosition()`, `setPosition(int)`, `grow(int)`) and
`codegen/Utf8WriterCodegen.java` (generated codecs writing directly into the
buffer, including the inlined object-end store).

## What Fory does

Fory's generated codecs do **not** funnel output through an `OutputStream` or a
small-method API. The writer exposes its raw state — `getBuffer()`,
`getPosition()`, `setPosition(int)`, `grow(int)` — and generated code performs
local capacity checks (T3), packed stores (T6), and one `setPosition` per field
group. Even the object-end brace is written inline by generated code:

```java
byte[] objectEndBuffer = writer.getBuffer();
int objectEndPosition = writer.getPosition();
if (objectEndPosition == objectEndBuffer.length) {
    writer.grow(1);
    objectEndBuffer = writer.getBuffer();
}
objectEndBuffer[objectEndPosition++] = (byte) '}';
writer.setPosition(objectEndPosition);
writer.setDepth(writer.getDepth() - 1);
```

`writeRaw(byte[])` likewise becomes a direct `System.arraycopy` with an
inlined check (Fory diff 10707–10717) instead of `ensure` + helper call.

## Why it is faster

Every byte written through `OutputStream` pays a virtual call (or a virtual
call + `arraycopy` per chunk) and forces the JIT to reload buffer/cursor state
per call. Fory's shape collapses the entire event assembly into: local capacity
compare, direct stores, one cursor publish — C2 keeps `buffer` and `position`
live in registers across the whole event, and small fixed chunks (field
prefixes, separators) are stored with no call overhead at all.

## What dia-log did before

`JsonLogWriter.writeJsonEvent` assembled each event by calling
`OutputStream.write(...)` on the reusable event buffer
(`ReusableByteArrayOutputStream`) — every field prefix, string segment, number
and separator went through the virtual `OutputStream` interface, and the buffer
managed its own cursor internally.

Baseline fixture: `hr.hrg.dialog.core.perf.StreamMediatedWriter` (the
pre-change behavior: `out.write(byte[], off, len)` / `out.write(int)` per
chunk).

## What dia-log does now

`core/src/main/java/hr/hrg/dialog/core/ReusableByteArrayOutputStream.java`
gains the direct-buffer API (the dia-log analogue of Fory's `@Internal`
surface, all with T3 inlined checks):

- `position()` / `setPosition(int)` — direct cursor read/write.
- `writeLongPrefixLE(long, int)` / `writeIntPrefixLE(int, int)` — 1..8 / 1..4
  packed little-endian byte stores in one store group with one capacity check.
- `writeRaw(byte[], int, int)` — bulk copy with an inlined check (the
  direct equivalent of `write(byte[], off, len)` minus the virtual call).
- `grow(int)` is package-private so same-package writers can grow in place.

Consumers:

- `EscapedJsonStringWriter` and `StringByteExtractor` detect a
  `ReusableByteArrayOutputStream` target (`instanceof`) and use the
  direct-buffer path: clean SWAR words are stored straight into the backing
  array, escapes are stored inline, cursor published once (T1/T2).
- `JsonLogWriter` uses the packed-prefix path (T6) when the target is the
  reusable buffer.

The `OutputStream` API and the stream fallback are unchanged, so the writer
still works with any stream (and with jackson delegation).

## Scope note

Fory's full pattern also lets *generated* codecs hold buffer+cursor across a
whole object graph. dia-log's writer is handwritten, so this commit implements
the per-string and per-field direct paths; a future step could give
`JsonLogWriter` a direct assembly mode that keeps the buffer local across the
whole event (the analysis in
[`doc/fory-commit-585eb16f-perf-analysis.md`](../fory-commit-585eb16f-perf-analysis.md)
labels that "option 2").

## Verification

- `ReusableByteArrayOutputStreamDirectApiTest` — packed stores, cursor
  publish/growth, bounds checks.
- `JsonLogWriterDirectBufferTest` — byte-identical output through the direct
  path vs the stream fallback for plain, KV/MDC, throwable, long-value and
  null-field events.
- Benchmark: `ForyPerfComparisonBenchmark` (`*Direct` vs `*Stream` vs
  `*Classic`).
