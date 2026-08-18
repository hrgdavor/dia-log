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
- `StringHashSet` — resettable, allocation-minimizing open-addressing set for
  `String` key deduplication (`add` returns whether the key was new; `clear()`
  reuses the table; capacity only grows, never shrinks). Implements no
  `java.util` interfaces and allocates nothing per `add`/`contains`/`clear`
  in steady state — a drop-in replacement for the per-event
  `HashSet<String>` used for KV/MDC key dedup.

### Changed

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
