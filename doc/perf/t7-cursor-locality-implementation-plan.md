# T7 — Cursor-Locality Buffer Writer: Implementation Plan

**Goal:** Extract a generalized, composable cursor-locality buffer writer pattern
into the dia-log codebase, reusing the existing optimized primitives
(`JsonNumberWriter`, `DirectJsonBuffer`, `ReusableByteArrayOutputStream`) rather
than introducing naive shift-and-store fallbacks.

## Phase 1 — Create `WriteOps` facade over existing optimized implementations

**File:** `core/src/main/java/hr/hrg/dialog/core/WriteOps.java`

Do **not** write naive `writeInt`/`writeLong` byte-shift stores. Instead, create
a `final` utility class that delegates to the project's already-optimized number
and string writers, exposing a uniform `byte[]` + `int pos` API:

```java
public final class WriteOps {
    private WriteOps() {}

    // Numbers: delegate to JsonNumberWriter.buildInt/buildLong, then bulk-copy
    public static int writeInt(byte[] buf, int pos, int v, byte[] intBuf) { ... }
    public static int writeLong(byte[] buf, int pos, long v, byte[] longBuf) { ... }
    public static int writeFloat(byte[] buf, int pos, float v, byte[] floatBuf) { ... }
    public static int writeDouble(byte[] buf, int pos, double v, byte[] doubleBuf) { ... }

    // Strings: delegate to EscapedJsonStringWriter / StringByteExtractor
    public static int writeEscapedJsonString(byte[] buf, int pos, String s) { ... }
    public static int writeLatin1(byte[] buf, int pos, String s) { ... }

    // Raw bulk copy (already optimized in ReusableByteArrayOutputStream.writeRaw)
    public static int writeRaw(byte[] buf, int pos, byte[] src, int off, int len) { ... }
}
```

**Key design decisions:**
- Number formatting reuses `JsonNumberWriter.buildInt`/`buildLong` (packed
  `DIGIT_QUADS`/`DIGIT_TRIPLES` tables, Ryu float/double) — the digit-building
  logic stays in the small, stable class where C2 already inlines it optimally.
- The reusable digit buffers (`intBuf`, `longBuf`, `floatBuf`, `doubleBuf`) are
  **caller-owned** and passed in, matching the project's "reusable objects as
  parameters over ThreadLocal" rule.
- String writing reuses `EscapedJsonStringWriter` and `StringByteExtractor` in
  their direct-buffer forms (SWAR scan + length-band dispatch).
- `writeRaw` is a thin `System.arraycopy` with an inlined capacity check,
  mirroring `ReusableByteArrayOutputStream.writeRaw`.

**Acceptance criteria:**
- `WriteOps` methods are `< 35 bytecodes` each so C2 inlines them unconditionally.
- No method accepts a heap-allocated state object beyond the `byte[]` buffer
  itself and the caller-owned digit buffers.
- No method allocates on the hot path.

## Phase 2 — Refactor `JsonLogWriter` direct path to use `WriteOps`

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`

Replace the inline digit-building and string-escaping sequences in
`writeJsonEventDirect` with `WriteOps` calls that operate on actual buffer
indexes:

- Number fields (`ts`, `level` ordinal, etc.):
  ```java
  // before (inline buildInt + writeRaw)
  int len = JsonNumberWriter.buildInt(intBuf, value);
  c.writeRaw(intBuf, MAX_INT_BYTES - len, len);

  // after (WriteOps delegates to the same buildInt, then bulk-copies at pos)
  pos = WriteOps.writeInt(buf, pos, value, intBuf);
  ```
- String fields (logger, thread, message):
  ```java
  pos = WriteOps.writeEscapedJsonString(buf, pos, logger);
  ```
- Packed field prefixes (`{`, `"ts":`, etc.):
  ```java
  pos = WriteOps.writeRaw(buf, pos, prefixBytes, 0, prefixBytes.length);
  ```

**Verification:**
- `JsonLogWriterDirectBufferTest` must continue to pass with byte-identical
  output.
- JMH micro benchmark (`ForyPerfComparisonBenchmark` prefixes/int/long/string
  legs) must show no regression vs the current inline implementation. Because
  `WriteOps` methods are tiny and delegate to the same optimized backends, C2
  should produce identical machine code.

## Phase 3 — Generalize `DirectJsonBuffer` into a `CursorBuffer`

**File:** `core/src/main/java/hr/hrg/dialog/core/CursorBuffer.java`

`DirectJsonBuffer` is JSON-specific (it owns a `ReusableByteArrayOutputStream`).
Extract a general-purpose `CursorBuffer` for non-JSON writers, parameterized by
the backing buffer type:

```java
public final class CursorBuffer {
    public byte[] buf;
    public int pos;
    public int limit;

    /** Publishes the cursor to the underlying sink. */
    public void publish() { }

    /** Re-syncs after a stream-bound delegation wrote directly. */
    public void resync() { }
}
```

For JSON writers, a `JsonCursorBuffer` subclass adds the `ReusableByteArrayOutputStream`
target and `publish`/`resync` implementations. For other writers (binary TLV,
custom protocols), the base `CursorBuffer` is sufficient.

**Usage contract:** Writers pull `buf`/`pos`/`limit` into locals at method entry,
perform all writes through `WriteOps` or inline stores, and publish the cursor
once at the end. The cold path (flush/grow) is inlined inside the `if` block,
exactly as in [t7-cursor-locality-buffer-writer.md](t7-cursor-locality-buffer-writer.md).

## Phase 4 — Apply pattern to a new hot-path writer

Pick a writer that currently lacks the cursor-locality discipline. Candidate:

- A new binary TLV (type-length-value) log writer, or
- The existing `DirectJsonStringWriter` / `DirectJsonBuffer` as a reference
  implementation that new writers must match.

**Goal:** Any new writer in `core/` or `logback/` that serializes into a
`byte[]` should reuse `CursorBuffer` + `WriteOps`, proving the generalized
pattern works beyond JSON.

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
3. Update `doc/perf/t7-cursor-locality-buffer-writer.md` to reflect the actual
   project implementations (JsonNumberWriter, DirectJsonBuffer, ReusableByteArrayOutputStream).

**Success metric:** The cursor-locality path should be at least 2× faster than
`ByteArrayOutputStream`-mediated writes for mixed primitive/string workloads,
with **0 B/op** allocation on the hot path.
