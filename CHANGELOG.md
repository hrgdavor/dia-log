# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `JsonAppender` and `JsonAppenderRolling` accept a logback-configurable
  `<stackTraceFilter>` predicate (a fully-qualified class name implementing
  `Predicate<String>`) to exclude stack-trace frames from the written `stack`
  field and the `errHash` fingerprint.
- XZ compression support for rotated logs documented. The example module now bundles the
  `org.tukaani:xz` dependency so Logback's native XZ compression
  (`XZCompressionStrategy`) is available. Using a `fileNamePattern` ending in
  `.xz` on a `TimeBasedRollingPolicy` or `SizeAndTimeBasedRollingPolicy` to
  compress rotated files with XZ.

### Removed

- **Key dedup between KV pairs and MDC removed** from `JsonLogWriter` (and the
  test-side `JsonLogWriterStream` / `JsonLogWriterClassic` baselines). Duplicate
  keys are now emitted as-is: when a statement KV key collides with an MDC key,
  both appear in the JSON output. Downstream log ingestion handles rare
  duplicate-key cases (last-wins or array semantics depending on the parser).
  This eliminates the per-event `StringHashSet` key-tracking set from the hot
  path. `StringHashSet` (`core`) and its test deleted.
- **Reserved-name skipping of MDC keys removed** from `JsonLogWriter` (and the
  test-side `JsonLogWriterStream` / `JsonLogWriterClassic` baselines). MDC keys
  are now written as-is, even when they collide with a writer field name
  (`ts`, `level`, `msg`, `errClass`, `errHash`, `stack`, `prefix`, …): duplicate
  top-level keys are allowed and resolved at the consumer. See ADR 013.

### Changed

- `LoggingEventBuilderWrapperBase.beforeLog()` now attaches the synthetic
  `stackWhenTraceEnabled` cause only when no cause was already set: the wrapper
  tracks the user-set cause in its `setCause` override and guards on it, so an
  explicitly set cause (e.g. `log.atError().setCause(e).stackWhenTraceEnabled()`)
  is no longer replaced by the synthetic trace `Throwable`. The code now matches
  its Javadoc.
- Fingerprint entry points in `JavaStackSanitizer`, `JavaStackTraceWriter`,
  `JavaStackSanitizerLogback`, and `JavaStackWriterLogback`
  (`fingerprint(...)`, `fingerprintFromTrace(...)`,
  `addFromTraceToOutputStream*AndFingerprint(...)`) now take a
  **caller-supplied reusable `Wyhash64.Streaming`** and reset it internally
  (seed 0). The no-stream convenience overloads were removed: there is no
  hidden `ThreadLocal` state, and the hasher is reused by the caller exactly
  like the number buffers. `JsonLogWriter`/`JsonLogWriterClassic` own their
  hasher as a plain field. This removes the per-call `Wyhash64.Streaming`
  allocation (~136 B/op) from every fingerprint path; the only per-call
  allocation left in `fingerprint(Throwable, …)` is the JDK-mandated
  `Throwable.getStackTrace()` defensive copy.
- Documented the project guideline *prefer reusable objects as parameters over
  ThreadLocal* in `AGENTS.md` (applies to all future hot-path code).
- Added `LogbackWriterComparisonBenchmark` and its results doc
  (`doc/logback-writer-comparison-benchmark-results.md`): default logback pattern
  encoder vs optimized JSON vs Jackson-based encoder, with and without traces.
  Findings: the optimized writer allocates a constant 208 B/op regardless of
  throwable presence; the default encoder's `%ex` trace rendering allocates
  ~12–15 KB/op.
- `ReusableByteArrayOutputStream` (core) — reusable, grow-only in-memory buffer
  (default 1 MiB, grows only to the longest event). `JsonAppender` and
  `JsonAppenderRolling` now assemble each event in it and flush the whole event to
  the real stream with one bulk write instead of hundreds of tiny writes.
- `StringByteExtractor.writeLatin1` now batches contiguous ASCII runs into bulk
  `write(byte[], off, len)` calls instead of per-byte `write(int)` (per-byte writes
  measured ≈51× slower). Traced events: optimized JSON 5.937 → 1.942 us/op (≈3.1×),
  Jackson encoder 6.430 → 2.264 us/op (≈2.8×).
- `EventSnapshotHandler` — hook on `JsonAppender` / `JsonAppenderRolling` that
  receives an owned, exact-size `byte[]` copy of each serialized JSON event, for
  forwarding to an HTTP endpoint or a log-tracking UI. The snapshot is always a
  fresh allocation (it may be retained, fanned out to multiple outputs, or handed
  to another thread). Configurable programmatically or via
  `<eventSnapshotHandlerClass>` in logback.xml (no-arg class name, like
  `<stackTraceFilter>`).

### Fixed

- `JsonAppender` / `JsonAppenderRolling` now flush the output stream after the
  per-event bulk write, honoring `immediateFlush` (default `true`) like logback's
  own `OutputStreamAppender`. The `writeOut()` overrides bypass logback's write
  path, so previously events could sit in the file stream's 8 KiB buffer until
  rollover or JVM shutdown — a short run left the log file empty.
- Renamed the logback.xml class-name property from `<eventSnapshotHandler>` to
  `<eventSnapshotHandlerClass>` (programmatic
  `setEventSnapshotHandler(handler)` is unchanged). Two `setEventSnapshotHandler`
  overloads made logback's `BeanDescriptionFactory` emit a WARN status, which
  triggers logback 1.5.x's LOGBACK-292 fallback: the entire configuration status
  list dumped to stdout, corrupting the JSON Lines stream.
- `example` module: section banners go to stderr (stdout is pure JSON Lines);
  `logback.xml` / `logback-test.xml` register an stderr status listener (the
  `status="OFF"` configuration attribute is a no-op in logback 1.5.38); the run
  story now uses [`jlx`](https://github.com/hrgdavor/zig-jlx) instead of `jq`;
  `jlx.conf` omits `paths` so its `[folders]` section is the fallback matched in
  file mode (relative `paths` never match).

### Changed

- `JsonLogWriter.writeValueDirect()` fallback path now uses direct `JsonGenerator`
  via `gen.writePOJO()` instead of `mapper.writeValue()`. The generator is created
  via `mapper.writer().createGenerator(rbo)` which inherits the writer's configuration
  at instantiation time (Jackson 3.x immutability model). This avoids re-entering
  the mapper's configuration resolution on every fallback call, reducing overhead
  in the object serialization fallback path. See `doc/perf-exploration/t15-direct-json-generator-fallback.md`
  for the full explanation.

## [1.0.0] - 2026-08-11

### Added

- Initial release of `dia-log-core` and `dia-log-logback`.
- Structured JSON logging via `DiaLogger` and `kv()` key/value pairs.
- `JsonAppender` and `JsonAppenderRolling` logback appenders backed by `JsonLogWriter`.
- Conditional stack traces with `stackWhenTraceEnabled()`.
- Deterministic stack-trace sanitization and fingerprinting (`JavaStackSanitizer`, `Wyhash64`).
- Low-allocation hot path: pre-encoded key bytes, direct string data access,
  single-pass hash + write, reusable number buffers.
- Maven Central publishing configuration (Central Portal).
