# Allocation Benchmark Results

Measured 2026-08-18 with JMH `-prof gc` on JDK 25 (AMD Ryzen 9 7945HX, Windows,
x86-64, little-endian), single fork, single thread, `-wi 2 -i 3`. All numbers are
**`gc.alloc.rate.norm`** (bytes per operation).

> **Fast paths require `--add-opens java.base/java.lang=ALL-UNNAMED`.** Without it the
> String/escaping fallbacks allocate (`toCharArray()`, `getBytes()`). All numbers below are
> for the fast paths (the benchmark forks pass the flag).

## Allocation-avoidance measures — every step taken

This section is the complete inventory of the steps Dia-Log takes to avoid allocations on
the logging hot path. It is the counterpart to the benchmark tables below: each technique
here is either verified at 0 B/op in those tables or listed under *documented remaining
allocations* with the reason it is not (and should not be) allocation-free.

### Hashing without copying — `Wyhash64`

1. **Direct `String` hashing** — `hash(long, String)` reads `String.value`/`String.coder`
   via `VarHandle` (with `--add-opens`), so hashing a string never copies it into a
   `byte[]` or `char[]`. UTF-16 strings are hashed straight from their little-endian
   backing bytes on little-endian platforms.
2. **Strided Latin-1 slices** — an all-Latin-1 slice of a UTF-16LE string is hashed by
   strided low-byte reads (zero allocation, no `substring`, no copy), matching exactly
   what a compacted `substring` would hash to.
3. **Manual `char[]` packing** — `hash(char[])` reads multi-byte values straight from the
   array via manual byte packing: no boxing, no copy, no FFM/Unsafe dependency.
4. **Reusable streaming hasher** — `Wyhash64.Streaming` owns a persistent 48-byte buffer;
   `reset(seed)` reuses the same instance on the hot path instead of allocating a fresh
   hasher per event, and `finalHash()` reads its final 16-byte window directly from the
   buffer — the former scratch `byte[16]` allocation and per-call `long[3]` state copy
   were removed.

### String access and JSON escaping without `getBytes()`

5. **`StringByteExtractor`** — with `--add-opens`, streams a `String`'s backing bytes
   straight to the `OutputStream` via `VarHandle`, skipping the transient `byte[]` that
   `getBytes()` would allocate. Falls back to the classic path (allocating) only when
   `--add-opens` is unavailable; behavior is identical either way.
6. **`EscapedJsonStringWriter`** — JSON-escapes strings through the same direct-access
   strategy; no intermediate arrays on the fast path.

### Numbers without intermediate strings — `JsonNumberWriter`

7. **Ryu float/double** — `Float.toString()` / `Double.toString()` are replaced by the
   Ryu algorithms, which emit digits straight to the output (no `String`, no boxed
   `Float`/`Double`).
8. **Digit-pair/quad lookup for int/long** — digits are computed via the precomputed
   ASCII `DIGIT_QUADS`/`DIGIT_TRIPLES` tables and written directly.
9. **Bufferless number writes** — `JsonNumberWriter` writes digits straight into the
   event `byte[]` at a caller offset (Ryu at that offset for float/double); no scratch
   buffer and no per-number allocation.

### The `JsonLogWriter` hot path

10. **Pre-encoded key bytes** — field names (`"ts":`, `"level":`, …) and JSON literals
    (`true`/`false`/`null`) are stored once as `byte[]` constants; writing a field never
    allocates a byte array.
11. **Direct top-level serialization** — the well-known fields (`ts`, `level`, `logger`,
    `thread`, `msg`, `err*`, `stack`) are written straight to the `OutputStream`,
    bypassing Jackson's per-field machinery (generator state, intermediate buffers,
    internal objects). Jackson is invoked only for genuinely arbitrary KV/MDC values.
12. **Lazy KV/MDC dedup set** — the `allKeys` set is created **only** when MDC is
    non-empty and KV pairs exist. Previously it was a `new HashSet<>()` on every KV event
    (192 B/op even without MDC).
13. **Caller-owned reusable fingerprint hasher** — the fingerprint entry points
    (`fingerprint(...)`, `addFromTraceToOutputStream*AndFingerprint(...)`) take a
    caller-supplied `Wyhash64.Streaming` and reset it internally (seed 0). There are
    deliberately **no** no-stream convenience overloads and no hidden `ThreadLocal`
    state — project guideline: *prefer reusable objects as parameters over
    ThreadLocal* (see `AGENTS.md`). `JsonLogWriter` owns its hasher as a plain field
    like the number buffers (~136 B/op of per-event hasher allocation removed); the
    only per-call allocation left in `fingerprint(Throwable, …)` is the `Throwable`
    defensive copy (see the table below).
14. **Single-pass stack write + fingerprint** — `addFromTraceToOutputStreamJsonAndFingerprint`
    and friends write the `stack` JSON *and* compute `errHash` in one traversal of the
    trace instead of two, cutting both CPU work and allocation.
15. **`JavaStackSanitizer` streams frames** — stack frames are written directly to the
    output stream instead of building intermediate strings for each frame.

### Reusable key-dedup set — `StringHashSet` (core)

16. **Resettable open-addressing set** — `clear()` nulls the occupied slots and reuses
    the table (no reallocation, no shrink); capacity only doubles when the 2/3 load
    factor is exceeded, so the initial table and each growth table are the set's *only*
    allocations. Steady-state `add`/`contains`/`clear` are verified at 0 B/op by a
    `ThreadMXBean` unit test. It uses the cached `String.hashCode()` (identity fast path
    before `equals`), which allocates nothing on any JVM — unlike the `Wyhash64` String
    path, whose zero-allocation mode requires `--add-opens`.

### No-op wrapper pattern

17. **Singleton no-op builder** — when a log level is disabled, `LoggingEventBuilderWrapperNoop`
    keeps the fluent chain working with zero allocation (no per-call objects, no
    evaluation of arguments).

### Deliberately excluded

Per `AGENTS.md`, **dev/diagnostic variants** (`JsonLogWriterDev` missing-key reporting,
`JsonLogWriterClassic`) and benchmark fixtures are *not* subject to zero-allocation
efforts: adding guard scans or micro-optimizations to them can cost more than the
allocation they avoid. The production writer is the only zero-allocation target.

## How to run

```bash
# compile benchmarks (the jmh annotation processor runs during test-compile)
mvn -q test-compile -pl core,logback

# build the test classpath, then run with the gc profiler
mvn -q dependency:build-classpath -pl logback -am -Dmdep.includeScope=test -Dmdep.outputFile=cp.txt
java --add-opens java.base/java.lang=ALL-UNNAMED \
     -cp "core/target/test-classes;core/target/classes;logback/target/test-classes;logback/target/classes;$(cat cp.txt)" \
     org.openjdk.jmh.Main AllocationBenchmark JsonLogWriterDevBenchmark \
     -prof gc -wi 2 -i 3 -f 1 -t 1
```

(`-jvmArgsAppend "--add-opens java.base/java.lang=ALL-UNNAMED"` is needed if the launcher
JVM is not already started with the flag, since JMH forks do not inherit it.)

## Core zero-allocation paths — verified 0 B/op

| Benchmark | B/op |
|---|---|
| `hashStringLatin1` — `Wyhash64.hash(0, String)` (Latin-1) | 0.0 |
| `hashStringUtf16` — `Wyhash64.hash(0, String)` (UTF-16) | 0.0 |
| `hashCharArray` — `hash(char[])` (manual packing) | 0.0 |
| `hashByteArray` — `hash(byte[])` | 0.0 |
| `streamingReused` — `Streaming` update + `finalHash()` on a reused hasher | 0.0 |
| `escapedJsonString` — `EscapedJsonStringWriter` (VarHandle path) | 0.0 |
| `stringBytes` — `StringByteExtractor` strategy write | 0.0 |
| `fingerprintFromTraceReused` — prepared trace + caller-owned hasher | 0.0 |

> `floatWrite` / `doubleWrite` moved out of this table on 2026-08-25: the
> `JsonNumberWriter.writeFloat(OutputStream, float)` / `writeDouble(OutputStream, double)`
> convenience overloads (used by the benchmark) allocate a per-call scratch
> `byte[]` (`MAX_FLOAT_BYTES` = 32 B, `MAX_DOUBLE_BYTES` = 48 B). The 08-18
> baseline measured the caller-supplied-buffer overloads, which commit 50e5586
> (2026-08-22, "few rounds of performance improvements") replaced. The
> **production direct path** uses the bufferless
> `writeFloat(byte[], int, float)` / `writeDouble(byte[], int, double)`
> overloads and stays allocation-free; the stream overloads are fallbacks
> (jackson delegation, classic fixture) in the same accepted category as
> `StringByteExtractor.writeClassic()`.

## Production writer — now 0 B/op in every scenario

| Benchmark | before | after |
|---|---|---|
| `plainWriter_noKv` — event without key/values | 0.0 | 0.0 |
| `plainWriter_withKv` — event with key/value pairs, no MDC | 192 | **0.0** |
| `plainWriter_exception` — throwable event | 296 | **0.0** |

### What was removed

1. **`allKeys` `HashSet` (192 B/op on every KV event without MDC).** The set existed only
   to dedup MDC keys against statement keys, but was allocated whenever KV pairs were
   present even when there was no MDC. `JsonLogWriter` now fetches the MDC map first and
   builds the set only when MDC is actually non-empty — KV events without MDC allocate
   nothing.
2. **A fresh `Wyhash64.Streaming` per exception event (~136 B/op).** The single-pass
   fingerprint (`addFromTraceToOutputStreamJsonAndFingerprint`) created a new hasher
   internally. The fingerprint entry points now take a **caller-owned reusable hasher**
   as a parameter and reset it internally; `JsonLogWriter` holds its hasher as a plain
   field like the number buffers (no hidden `ThreadLocal` state — the writer is
   `@NotThreadSafe`). Exception events now allocate ~0 B.

Both were introduced in 2026-08-18; `mvn clean verify` (JaCoCo line ≥80 % / branch ≥70 %)
passes with the changes.

## Documented remaining allocations (intentionally not removed)

| Benchmark | B/op | reason |
|---|---|---|
| `floatWrite` / `doubleWrite` | 32 / 48 | `JsonNumberWriter.writeFloat(OutputStream, float)` / `writeDouble(OutputStream, double)` allocate a per-call scratch `byte[]` (`MAX_FLOAT_BYTES` / `MAX_DOUBLE_BYTES`). The production direct path uses the bufferless `byte[], int` overloads (0 B/op); these stream overloads are fallbacks (since commit 50e5586, 2026-08-22) |
| `streamingNewPerCall` | 136 | `new Wyhash64.Streaming(0)` per call — the caller should reuse (`reset`); reusable overloads exist |
| `fingerprint(Throwable, …)` (reusable hasher) | 88 | `Throwable.getStackTrace()` returns a **defensive copy** each call (JDK behavior, not ours); pass `StackTraceElement[]` directly via `fingerprintFromTrace` where possible |
| `devWriter_allPresent` / `devWriter_oneMissing` | 256 / 464 | `JsonLogWriterDev` missing-key reporting is a **dev/diagnostic tool** — by design it is excluded from zero-allocation efforts (see `AGENTS.md`); it uses a regex `Matcher` + small set/list per event |

## Dev variant policy

Per `AGENTS.md`, dev/diagnostic variants (`JsonLogWriterDev`, `JsonLogWriterClassic`,
benchmark fixtures) are **not** subject to zero-allocation efforts: adding guard scans or
micro-optimizations to them can cost more than the allocation they avoid. The production
writer is the only zero-allocation target.

## Notes

- **Where the 88 B/op on `fingerprint(Throwable, …)` comes from (verified):** it is one
  single allocation — the defensive clone inside `Throwable.getStackTrace()`. The JDK 25
  implementation is `return getOurStackTrace().clone();` on every call. The benchmark's
  throwable has a ~17–18 frame trace, and one cloned `StackTraceElement[18]` is
  16 bytes (array header) + 4 bytes per reference, aligned to 8 → **88 bytes**. The
  isolation rows prove the whole fingerprint pipeline adds nothing on top:
  `getStackTrace()` alone = 88.000 B/op, `fingerprint(Throwable, …, stream)` =
  88.004 B/op, `fingerprintFromTrace(trace, …)` = 0.004 B/op.
- **Is the 88 avoidable?** Per call, *from a bare `Throwable`: no* — the JDK mandates the
  defensive copy (internal array is private; subclasses may override `getStackTrace()`).
  A `VarHandle` read of `Throwable.stackTrace` (with `--add-opens`) is possible but
  rejected: the field is null until first materialization, the read bypasses overrides,
  and it breaks the documented defensive-copy contract. **Structurally: yes** — materialize
  the trace once and fingerprint the prepared array: `fingerprintFromTrace(trace, …)`
  (0.004 B/op), or the logback path, where `ThrowableProxy` caches
  `StackTraceElementProxy[]` once at construction and `JsonLogWriter` (0 B/op on
  throwable events) never calls `getStackTrace()` per event.
- There are no convenience fingerprint overloads and no hidden `ThreadLocal` state: the
  fingerprint entry points require a caller-owned reusable `Wyhash64.Streaming` (a field
  of the logger/appender, like the number buffers). See `AGENTS.md` — *prefer reusable
  objects as parameters over ThreadLocal*.
- All hash paths are allocation-free on little-endian platforms; big-endian CPUs (outside
  the JDK-25 requirement) fall back to a `char[]` repack (allocates).
- See [`doc/wyhash64-zero-allocation.md`](wyhash64-zero-allocation.md) for the design and
  [`doc/benchmark-optimization-history.md`](benchmark-optimization-history.md) for the
  optimization history.
