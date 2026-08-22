# Contributing to Dia-Log

Thanks for considering a contribution! This document covers the build, the code
style rules that matter for this codebase, and the PR process.

## Requirements

- **JDK 25** (Temurin recommended) — the project compiles with `--release 25`
  and the Maven Enforcer fails the build on older JDKs.
- **Maven 3.9+** (also enforced by the Maven Enforcer).

## Build & Test

```bash
# Full build: enforcer + tests + JaCoCo coverage
mvn clean verify -Dgpg.skip=true

# Just the tests of one module
mvn test -pl core
mvn test -pl logback
```

GPG signing is bound to the `verify` phase for Maven Central releases, so local
`verify` runs and CI pass `-Dgpg.skip=true` (only the release workflow signs).

## Stack Trace Sanitizer Derivatives — READ THIS FIRST

The stack-trace sanitization family is **deliberately duplicated** as a
performance optimization, and the duplicates are **auto-generated**:

| File | Role |
|---|---|
| `core/.../JavaStackSanitizer.java` | **Canonical source** — modify this file ONLY |
| `core/.../JavaStackTraceWriter.java` | Core, no-filter derivative |
| `logback/.../JavaStackSanitizerLogback.java` | Filter-enabled, logback input |
| `logback/.../JavaStackWriterLogback.java` | No-filter, logback input |

**Workflow**: edit `JavaStackSanitizer.java`, then sync all derivatives:

```bash
mvn -pl project-automation compile exec:java \
    -Dexec.mainClass=hr.hrg.dialog.tools.StackSanitizerDerivativeGenerator
```

- **DO NOT edit derivative files directly** — they are regenerated and your
  changes will be overwritten.
- **DO NOT refactor into a common base class** — virtual dispatch in these hot
  paths would degrade logging performance.
- Filter logic lives at `filter.test(className)` checks; fallback blocks are
  marked with `// @sanitizer:begin` / `// @sanitizer:end`.

## Code Style & Performance Discipline

- **Intentional duplication**: hot-path code (Wyhash64, sanitizers, JSON number
  writing) is duplicated on purpose for JIT inlining. Do not "deduplicate" it.
- **Allocation hotspots to avoid** when adding features:
  - `Wyhash64.Streaming.finalHash()` — must not allocate a scratch `byte[16]`
  - `JsonLogWriter.writeJsonEventDirect()` — `allKeys` is lazily allocated, keep it so
  - `StringByteExtractor.writeClassic()` — allocates per string (fallback only)
  - `Float.toString()` / `Double.toString()` in `JsonNumberWriter` (Ryu used)
- **Thread safety model** (documented in `AGENTS.md`):
  - `DiaLoggerBase.prefix` must stay `volatile`
  - `JsonAppender.activeStream` / `JsonAppenderRolling.activeStream` are
    intentionally non-volatile; `writeOut()` snapshots to a local variable
  - `JsonLogWriter.stackTraceFilter` is configured once at startup
- **JSON escaping**: all keys/values written by `JsonLogWriter` must be JSON-
  escaped (quotes, backslash, control chars). Raw bytes are only for
  `RawValue` / `RawJsonBytes` passthrough.

## Testing

- Every change should come with tests. Key suites:
  - `WyhashZeroAllocTest` — Unicode/UTF-16 parity across String/char[]/byte[]
  - `JsonLogWriterTest` — JSON field layout, escaping, dedup, exception events
  - `ExampleIntegrationTest` — end-to-end run of the example `Main`
- Benchmarks live next to tests (`*Benchmark.java`); update them when you change
  hot paths and note results in `doc/benchmark-optimization-history.md`.

## PR Process

1. Branch from `main`, open a PR back to `main`.
2. Run `mvn clean verify -Dgpg.skip=true` locally before pushing.
3. Add a `CHANGELOG.md` entry under `[Unreleased]`.
4. Keep the plans folder honest: if you close a gap listed in
   `plans/analysis-report.md` or `plans/critical-analysis.md`, update the item's
   status in the same PR.
