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
