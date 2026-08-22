# T9 — Bufferless VarHandle Number Writing

**Source technique:** **Novel dia-log pattern**, built on the T5 packed ASCII
digit tables ported from Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
(*"feat(java): optimize json perf"*, PR #3871) and the T7 cursor-locality
writer shape. The refinement: numbers are written **directly into the
destination `byte[]` at the caller offset** — no reusable scratch buffer, no
`System.arraycopy`, one little-endian `LE_INT` VarHandle store per 4-digit
group.

## What Fory does

Fory's `Utf8JsonWriter` writes fixed-schema numbers with packed ASCII digit
tables (`DIGIT_QUADS`, `DIGIT_TRIPLES`) and `LittleEndian` word stores. The
digit tables are the upstream origin of dia-log's T5 packing; this iteration
is about the *writing shape* around them.

## What dia-log did before

- `core/.../JsonNumberWriter.java` — `buildInt`/`buildLong` packed the digits
  **right-aligned into a caller-owned scratch buffer** (`buf[MAX_BYTES-len ..
  MAX_BYTES)`), and `writeInt`/`writeLong` (OutputStream and
  `ReusableByteArrayOutputStream` overloads) took that buffer, then
  `System.arraycopy`'d the significant tail into the destination:
  ```java
  int len = JsonNumberWriter.buildLong(longBuf, v);
  System.arraycopy(longBuf, MAX_LONG_BYTES - len, buf, pos, len);
  ```
  `writeFloat`/`writeDouble` also took a caller `floatBuf`/`doubleBuf` scratch.
- `core/.../WriteOps.java` — `writeInt`/`writeLong`/`writeFloat`/`writeDouble`
  aliases (pure `byte[]` and grow-capable `ReusableByteArrayOutputStream`
  overloads) that each did the build-then-copy above.
- `logback/.../JsonLogWriter.java` — owned four reusable number buffers as
  instance fields (`intNumberBuffer`/`longNumberBuffer`/`floatNumberBuffer`/
  `doubleNumberBuffer`) and threaded them through every number call on both the
  direct and the stream path.

The hot path therefore paid: build into a scratch buffer → `arraycopy` into the
destination → one indirection layer through `WriteOps`.

## What dia-log does now

- `core/.../JsonNumberWriter.java` — the primary API is
  `writeInt`/`writeLong`/`writeFloat`/`writeDouble(byte[] buf, int pos, value)`
  returning the advanced position, with **no scratch buffer and no
  `System.arraycopy`**:
  - `writeInt`/`writeLong` write **most-significant-first**: a
    `decimalDigits` comparison ladder + `POW10` table slice the leading 1..4
    digits, then each subsequent 4-digit group is emitted with one
    `LE_INT.set(buf, pos, DIGIT_QUADS[chunk])` VarHandle store (a 1..3-digit
    leading group via the `DIGIT_TRIPLES` byte-store decode). Digits land
    directly in place — no right-to-left build, no shift.
  - `writeFloat`/`writeDouble` pass `pos` straight to Ryu
    (`RyuFloat.writeFloat(value, buf, pos)`), which already writes at an
    offset.
  - `Integer.MIN_VALUE` delegates to `writeLong` (its magnitude fits a long);
    `Long.MIN_VALUE` is stored with precomputed `LE_LONG`/`LE_INT` word stores;
    non-finite float/double writes `"null"` with one `LE_INT` store.
  - The `OutputStream` fallback overloads (`writeX(out, value)`) remain for API
    compatibility; they build into a small per-call local `byte[]` (a
    fallback-only allocation — see the benchmark notes).
- `core/.../WriteOps.java` — the four number aliases were removed; numbers are
  called on `JsonNumberWriter` directly.
- `logback/.../JsonLogWriter.java` — the four buffer fields are gone. The
  direct path calls `JsonNumberWriter.writeLong(buf, pos, …)` for `ts`/`errHash`
  (capacity pre-reserved by the combined checks) and
  `rbo.ensure(MAX_X_BYTES); rbo.pos = JsonNumberWriter.writeX(rbo.buf, rbo.pos, …)`
  for KV values; the stream path uses the bufferless `OutputStream` overloads.

## Why it is faster

- **No scratch-buffer round-trip.** Before: pack right-aligned into a separate
  `byte[]`, then `System.arraycopy` into the destination. After: write straight
  into the destination at `pos` — one memory region, no copy, no second pass.
- **One `LE_INT` VarHandle store per 4 digits** replaces four byte stores; the
  store is a single inlinable wide write that keeps `buf`/`pos` in registers.
- **One indirection layer removed** (no `WriteOps` number facade); the JIT
  inlines `JsonNumberWriter.writeLong` directly into the event assembly.
- **Zero allocation preserved on the direct path** (no scratch anywhere in the
  production `writeJsonEventDirect`), while the packed-key `LE_LONG` stores and
  the accumulated cursor work make the whole event cheaper.

## Verification

- **Correctness:** `JsonNumberWriterTest` (bufferless OutputStream API),
  `ForyPerfComparisonTest` (byte-identical int/long output vs the digit-by-digit
  `ClassicJsonNumberWriter` across a 300-400-value battery plus all digit-count
  boundaries), `JsonLogWriterDirectBufferTest` (byte-identical direct vs stream
  for every event shape, including numbers at non-zero offsets), `StrPackerTest`.
- **Benchmarks** (JDK 25.0.3, JMH 1.37, `-f 1 -t 1`, `--add-opens
  java.base/java.lang=ALL-UNNAMED`; deltas vs the recorded option-2 baselines of
  2026-08-18, so they are cumulative over the T7/StrPacker/T8/T9 work):

  | Benchmark | old | current | delta |
  | --- | --- | --- | --- |
  | `ForyPerfEventBenchmark.eventNewDirect` (avgt) | 0.095 µs/op | **0.064 µs/op** | **−32.6%** |
  | `eventNewDirect` (thrpt) | 10.73 ops/µs | **16.38 ops/µs** | **+52.7%** |
  | `CursorBufferWriterBenchmark.cursorWriteOps` | 3012 ns/op | **2628 ns/op** | **−12.7%** |
  | `prefixesPackedDirect` | 4.39 ns/op | **3.77 ns/op** | **−14.2%** |

  `eventNewDirect` remains **≈ 0 B/op**. The `OutputStream` stream-fallback
  legs (`intNew`/`longNew`/`eventNewStream`/`streamDataOutput`) regressed
  +3..12% in time with a new **32–40 B/op** per-call scratch allocation — a
  documented trade-off of the bufferless fallback, confined to the
  API-compatibility path (production targets `ReusableByteArrayOutputStream`).

- **Artifacts:** `bench-fory-event-current.txt`, `bench-fory-perf-current.txt`,
  `bench-cursor-writer-current.txt`, `bench-writers-current.txt`,
  `bench-misc-current.txt`; results summary in
  `fory-perf-benchmark-results.md` ("Current state (2026-08-22)").

## Historical note

This iteration (2026-08-21/22) removed, in order: the `WriteOps` number aliases
and the `JsonLogWriter` number-buffer fields, added the offset-based bufferless
`JsonNumberWriter` API, then replaced the right-aligned-build + `arraycopy`
shift with left-to-right `LE_INT` VarHandle stores. The stream fallback
allocation (32–40 B/op) was accepted as the price of removing the reusable
buffers; if it must be allocation-free, the fallback can write digit-by-digit
via `out.write(int)` — a decision left open.
