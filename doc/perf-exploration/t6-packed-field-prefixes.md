# T6 — Packed Field-Name Prefixes

**Source technique:** Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
— *"feat(java): optimize json perf"* (PR [#3871](https://github.com/apache/fory/pull/3871)).
Fory files: `java/fory-json/.../codegen/Utf8WriterCodegen.java`
(`directPackedPrefix`, `packedPrefixArgs`, `packedDynamicPrefixArgs`) and
`writer/Utf8JsonWriter.java` / `StringJsonWriter.java` (`writeRawValue(long,
long, int)` and the packed-prefix write methods).

## What Fory does

Fory's codegen precomputes each schema field prefix as up to **two little-
endian packed `long` constants** at generation time. Generated code writes the
prefix with `LittleEndian.putInt64` stores — one store per 8 bytes — instead
of per-byte loops or `arraycopy` calls. The comma/no-comma variants are
precomputed as separate packed pairs, and the object-start `{` is fused into
the first field prefix by shifting:

```java
// Utf8WriterCodegen.packedObjectStartPrefixArgs: fuse '{' into the prefix
Expression.Literal.ofLong((prefix0 << Byte.SIZE) | '{'),
Expression.Literal.ofLong((prefix1 << Byte.SIZE) | (prefix0 >>> (Long.SIZE - Byte.SIZE))),
```

so `{"name":` costs one capacity check + two 8-byte stores.

## Why it is faster

Every fixed field prefix written through `OutputStream.write(byte[])` pays a
virtual call + `arraycopy` (or worse, per-byte writes). A packed store group is
1–2 direct 8-byte stores with one inlined capacity check (T3), and keeps
`buffer`/`position` visible to C2 across the field sequence.

## What dia-log did before

`JsonLogWriter` precomputed each fixed prefix as a `byte[]`
(`KEY_TS = "\"ts\":".getBytes(...)`) and wrote it with
`out.write(',')` + `out.write(keyBytes)` (and `out.write('{')` +
`out.write(KEY_TS)` for the first field) — two virtual `OutputStream` calls
per field.

Baseline fixture: `hr.hrg.dialog.core.perf.ClassicFieldPrefixes` (the old
`{`/`,` + `write(byte[])` behavior).

## What dia-log does now

`logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`:

- Each fixed prefix is declared by a `// @CB.StrPacker ...` marker (see
  `doc/codebuddy-strpacker.md`); the project-automation `CodeBuddy` generator
  emits the UTF-8 `byte[]` plus the packed little-endian `long` constants
  (`KEY_X_W0..W3`, `KEY_X_LEN`) as compile-time literals.
- The direct-buffer path (target is a `ReusableByteArrayOutputStream`, the
  production event buffer in `JsonAppender`) stores each prefix as one
  little-endian VarHandle long store per 8-byte window (`WriteOps.LE_LONG.set`)
  with inlined capacity checks — no `arraycopy`, no
  per-call virtual dispatch. The leading `{` is fused into the first prefix
  (`KEY_TS` includes the object-start `{`).
- Stream fallback keeps the original `out.write(',')` + `out.write(bytes)`
  behavior, so output is byte-identical for every sink.

The sub-8-byte tail stores are refined in
[t8-packed-word-varhandle-stores.md](t8-packed-word-varhandle-stores.md).

User-supplied KV/MDC keys are unaffected: they still go through
`EscapedJsonStringWriter` (full JSON escaping per AGENTS.md's escape
discipline). Only the fixed schema prefixes are packed.

## Verification

- `JsonLogWriterDirectBufferTest` asserts byte-identical output between the
  direct-buffer path (packed prefixes active) and the plain-stream fallback
  (old `write(byte[])` behavior) for plain events, all-field-type events,
  throwable events, long-value events and null-field events.
- `core/.../WriteOpsPackedTest` pins the `WriteOps.LE_LONG` packed store at
  every byte offset (alignment coverage).
- Existing `JsonLogWriterTest` (field layout, escaping, KV/MDC dedup) passes
  unchanged.
- Benchmark: `ForyPerfComparisonBenchmark` (`prefixesStreamMediated`,
  `prefixesClassicFixture` vs `prefixesPackedDirect`).
