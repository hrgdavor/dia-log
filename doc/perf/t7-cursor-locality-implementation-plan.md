# T7 — Cursor-Locality Buffer Writer: Implementation Plan

**Goal:** Extract a generalized, composable cursor-locality buffer writer pattern
into the dia-log codebase, following the anti-pattern discipline and JIT-friendly
design documented in [t7-cursor-locality-buffer-writer.md](t7-cursor-locality-buffer-writer.md).

## Phase 1 — Create `WriteOps` utility class

**File:** `core/src/main/java/hr/hrg/dialog/core/WriteOps.java`

Create a `final` utility class with tiny static methods that accept only
primitives and return a primitive `int` (the new cursor position). These become
the composable building blocks for any hot-path writer:

```java
public final class WriteOps {
    private WriteOps() {}

    public static int writeInt(byte[] buf, int pos, int v) { ... }
    public static int writeLong(byte[] buf, int pos, long v) { ... }
    public static int writeFloat(byte[] buf, int pos, float v) { ... }
    public static int writeDouble(byte[] buf, int pos, double v) { ... }
    public static int writeVarint(byte[] buf, int pos, int v) { ... }
    public static int writeUTF8(byte[] buf, int pos, byte[] utf8) { ... }
}
```

**Acceptance criteria:**
- Every method is `< 35 bytecodes` so C2 inlines unconditionally.
- No method accepts a `CursorBuffer` or any heap-allocated state object.
- No method allocates.

## Phase 2 — Refactor `JsonLogWriter` direct path to use `WriteOps`

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`

Replace the inline shift-and-store sequences in `writeJsonEventDirect` with
`WriteOps` calls:

- `writeInt(DirectJsonBuffer, long)` → `WriteOps.writeLong(buf, pos, v)`
- `writeLongPrefixLE` / `writeIntPrefixLE` → `WriteOps.writeLong(buf, pos, v)`
  / `WriteOps.writeInt(buf, pos, v)`
- String bulk copies → `WriteOps.writeUTF8(buf, pos, utf8)`

**Verification:**
- `JsonLogWriterDirectBufferTest` must continue to pass with byte-identical
  output.
- JMH micro benchmark (`ForyPerfComparisonBenchmark` prefixes/int/long legs)
  must show no regression. If C2 inlining behaves correctly, the numbers should
  be identical to the inline version.

## Phase 3 — Add `CursorBuffer` for non-JSON writers

**File:** `core/src/main/java/hr/hrg/dialog/core/CursorBuffer.java`

A lightweight state carrier analogous to `DirectJsonBuffer` but general-purpose:

```java
public final class CursorBuffer {
    public byte[] buf;
    public int pos;
    public int limit;
    public final OutputStream sink;

    public void publish() { /* no-op, cursor is already local */ }
    public void resync() { pos = 0; /* post-flush resync */ }
}
```

**Usage contract:** Writers pull `buf`/`pos`/`limit` into locals at method entry,
perform all writes through `WriteOps` or inline stores, and publish `cb.pos = pos`
once at the end. The cold path (flush/grow) is inlined inside the `if` block,
exactly as in [t7-cursor-locality-buffer-writer.md](t7-cursor-locality-buffer-writer.md).

## Phase 4 — Apply pattern to a new hot-path writer

Pick a writer that currently lacks the cursor-locality discipline. Candidate:

- A new binary TLV (type-length-value) log writer, or
- The existing `DirectJsonStringWriter` / `DirectJsonBuffer` as a reference
  implementation that new writers must match.

**Goal:** Any new writer in `core/` or `logback/` that serializes into a
`byte[]` should either reuse `CursorBuffer` + `WriteOps` or be demonstrably
equivalent in JIT shape.

**Do not modify:**
- Auto-generated sanitizer derivatives (`JavaStackSanitizerLogback`,
  `JavaStackWriterLogback`). They are generated from
  `JavaStackSanitizer.java` per `AGENTS.md`.
- Dev/diagnostic variants (`JsonLogWriterDev`, `JsonLogWriterClassic`,
  benchmark fixtures). Keep them simple; do not add guard scans or cursor
  machinery to them.

## Phase 5 — Benchmark and document

1. Add a `CursorBufferWriterBenchmark` in `core/src/test/.../perf/` that
   measures `writeInt`, `writeLong`, `writeUTF8` through `CursorBuffer` +
   `WriteOps` vs `ByteArrayOutputStream` + `DataOutputStream`.
2. Add the results to `doc/perf/fory-perf-benchmark-results.md`.
3. Create `doc/perf/t7-cursor-locality-buffer-writer.md` as the canonical
   learning material (see the existing `t{N}-{slug}.md` pattern).

**Success metric:** The cursor-locality path should be at least 2× faster than
`ByteArrayOutputStream`-mediated writes for mixed primitive/string workloads,
with **0 B/op** allocation on the hot path.
