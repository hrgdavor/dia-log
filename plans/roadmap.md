# Dia-Log — Original Roadmap (archived)

> **ARCHIVED — SUPERSEDED (2026-08-17).** This was the original design/implementation
> roadmap at the root of the repo (`plan.md`). It is kept for **historical reference only**:
> the project has since shipped v1.0.0 (2026-08-11) and the design evolved away from several
> items described here. For the actual current state, see the
> [Implementation Status Audit](analysis-report.md) in `plans/analysis-report.md`.
> Stale items are annotated inline with `HISTORICAL` / `OUTDATED`.

---

## Current State (as written — OUTDATED)

> `HISTORICAL`: written when the project targeted Java 21 / SLF4J 2.0.17. Actual: Java 25,
> SLF4J 2.0.18, version 1.0.0.

Multi-module Maven library (`hr.hrg.dialog:dia-log-root`) targeting Java 21, built on SLF4J 2.0.17.

| Module    | Artifact          | Contents (as written) — OUTDATED, see current README                                            |
| --------- | ----------------- | ----------------------------------------------------------------------------------------------- |
| `core`    | `dia-log-core`    | `DiaLogger.java`, `LoggingEventBuilderWrapper.java`, `JavaStackSanitizer.java`, `Wyhash64.java` |
| `logback` | `dia-log-logback` | `CustomJsonEncoder.java`, `JsonLogWriter.java`  — `CustomJsonEncoder` was later removed         |
| `example` | `dia-log-example` | `Main.java` with `logback.xml` demonstrating all appenders                                      |

**What exists today:** Full SLF4J `Logger` delegation via `DiaLogger` (abstract, generic), `LoggingEventBuilderWrapper` (abstract, generic with `self()` for fluent chaining), JSON console/file/dev appenders with sanitized stack traces and hash-based dedup, cookbook docs.

> `HISTORICAL`: the "abstract, generic with `self()`" wrapper design was **dropped** — see
> Generic Type Hierarchy below. The "JSON console/file/dev appenders" were later replaced by
> `JsonAppender` / `JsonAppenderRolling` (dev appender removed per ADR-009).

---

## Phase 1 — Core API Completion

> Status: completed in essence; several described APIs changed later (see annotations).

- [x] **DiaLogger** — Abstract generic class `DiaLogger<L extends LoggingEventBuilderWrapper>` implementing full `Logger` interface. All methods delegate through the wrapper. Supports `contextStart(L)`/`contextEnd()` lifecycle, `prependPrefix()`, `addKeyValues()`. Subclasses implement `initBuilder()` (creates wrapped builder) and `noOpWrapper()` (returns no-op for disabled levels).
  - `HISTORICAL`: `contextStart(L)`/`contextEnd()` were never shipped; the actual API is `DiaLoggerBase<L extends LoggingEventBuilderWrapperBase>` with `initBuilder()`/`noOpWrapper()`/`_contextStart()`.
- [x] **LoggingEventBuilderWrapper** — Abstract generic class `LoggingEventBuilderWrapper<L extends LoggingEventBuilderWrapper<L>>`. Wraps SLF4J `LoggingEventBuilder` with delegation. Features: `stackWhenTrace()`, `kv()` shorthand, `Logger` reference. All fluent methods return `self()` for correct subclass chaining.
  - `HISTORICAL`: the CRTP/`self()` design was dropped. Actual: non-generic `LoggingEventBuilderWrapperBase`; chaining preserved via covariant overrides (see `plans/analysis-report.md` §20).
- [x] **JavaStackSanitizer** — Sanitizes stack frames for deterministic output: drops `jdk.internal.*`/`sun.reflect.*`, normalizes `$$Lambda` identifiers, strips line numbers, standardizes native calls. Provides `getSanitizedFrames()` (individual frames) and `getFingerprint()` (pipe-delimited).
  - `HISTORICAL` API names: actual API is `addFromTrace*()` / `fingerprint()`; a derivative family (`JavaStackTraceWriter`, `JavaStackSanitizerLogback`, `JavaStackWriterLogback`) is now `@generated` from this canonical class.
- [x] **Wyhash64** — Fast 64-bit hash for stack trace deduplication. Includes `Streaming` inner class for incremental hashing.
- [ ] **1.1 Concrete DiaLogger subclass** — e.g. `DefaultDiaLogger<L>` implementing `initBuilder()`/`noOpWrapper()` for common use cases (prefix scoping).
  - ✅ Done later: `DiaLogger` is the concrete subclass (`DiaLoggerBase<LoggingEventBuilderWrapperBase>`); `LoggingEventBuilderWrapperNoop` is the no-op wrapper.
- [x] **1.2 Unit tests for core** — `LoggingEventBuilderWrapperTest` (stackWhenTrace, kv shorthand, delegation, thread safety), `JavaStackSanitizerTest` (frame filtering, lambda normalization, native methods, maxFrames), `Wyhash64Test` (determinism, seed sensitivity, offset/length, ByteBuffer, streaming, edge cases), plus WyhashTestVectors, WyhashStandaloneTest, Wyhash64StreamingTest.
  - ✅ Plus later additions: `WyhashZeroAllocTest` (58 tests incl. Unicode), `Wyhash64ZigCompatibilityTest`, `Wyhash64StreamingSingleShotTest`, `JsonNumberWriterTest`, `RyuFloatDoubleTest`. Still missing per plans: `TraceIdTest`, `DiaLoggerTest`, `Wyhash64EdgeCaseTest`, `LoggingEventBuilderWrapperBaseTest`.

## Phase 2 — Logback Integration

- [x] **CustomJsonEncoder** — Logback encoder that outputs JSON via `JsonLogWriter`. Config: `includeMDC`, `includeKeys`, `includeSource`, `prettyPrint`, `customFields`, `maxStackFrames`. Use with standard `ConsoleAppender` or `RollingFileAppender`.
  - `HISTORICAL`: `CustomJsonEncoder` was later **removed**; replaced by `JsonAppender` / `JsonAppenderRolling` (direct `OutputStream` writers).
- [x] **JsonLogWriter** — Shared JSON serializer. Output schema: `ts` (epoch millis), `level`, `logger`, `thread`, `msg`, `kv`, `ctx`, `source`, `err` (with sanitized `stack`, `hash`, `cause`), `msgTpl`, custom fields. Config: `includeMDC`, `includeKeys`, `includeSource`, `prettyPrint`, `customFields`, `maxStackFrames`.
  - `HISTORICAL` schema: the nested `kv`/`ctx`/`err`/`msgTpl` schema was **replaced** by flat top-level fields — see the JSON schema section below.
- [ ] **2.1 Unit tests** — Cover: JSON structure for all log levels, key-value inclusion/exclusion, MDC inclusion/exclusion, exception serialization, special character escaping, encoder lifecycle.
  - 🔶 Still open: `JsonLogWriterTest`, `ConsoleAppenderJsonTest`, `RollingFileAppenderJsonTest` do not exist (the appender classes were renamed). `JavaStackSanitizerLogbackTest` exists.

## Phase 3 — Documentation

- [x] **cookbook/additional.error-only.log.md** — Error-only log file pattern with standard and JSON appenders, zgrep examples, `.gz` clarification.
- [x] **cookbook/stackWhenTrace.md** — Conditional call-stack visibility: how it works, output examples (plain text, JSON, dev console), ThrowableProxy explanation, configuration.
- [x] **cookbook/missing-keys-warn.md** — Detecting missing log keys at runtime: warnOnMissingKeys feature, output examples, null handling, configuration.
  - ⚠️ `cookbook/missing-keys-warn.md` documents the removed `ConsoleAppenderDev` feature — flagged in `plans/inconsistency-report.md` §29.
- [x] **3.1 README.md** — Project overview, maven coordinates, quick-start snippet, module descriptions. — ✅ Done; README now reflects the flat JSON schema and current module contents.
- [ ] **3.2 Javadoc** — Public API fully documented with `{@code ...}`, `@param`, `@return`, usage examples. — 🔶 Partial (key classes documented; not exhaustive).
- [ ] **3.3 Usage guide (`docs/usage.md`)** — Practical examples: basic setup, JSON output config, structured logging with `kv()`, contextual prefix, stackWhenTrace, missing key detection, error-only logs. — ❌ Not created.
- [ ] **3.4 Migration guide (`docs/migration.md`)** — Step-by-step from plain SLF4J to Dia-Log. — ❌ Not created.

## Phase 4 — Release Readiness

- [x] **4.0 .gitignore** — Standard Java/Maven/IDE gitignore covering `target/`, `.idea/`, `*.iml`, `.classpath`, `.project`, `.settings/`, `.vscode/`, OS files, logs.
- [ ] **4.1 Maven Central publishing** — GPG signing, nexus-staging-maven-plugin, POM metadata (SCM, license, developers). — ✅ Done: source/javadoc/gpg/central-publishing plugins + POM metadata + `.github/workflows/publish.yml` + `PUBLISHING.md`.
- [ ] **4.2 CI pipeline** — GitHub Actions: `mvn verify`, JaCoCo coverage, static analysis. — 🔶 Partial: `publish.yml` exists; no `ci.yml` (push/PR verify), no JaCoCo, no enforcer.
- [ ] **4.3 Versioning policy** — Semantic versioning strategy. — ✅ Changelog follows Keep a Changelog + SemVer.

---

## JSON Output Schema (as written — HISTORICAL)

> `HISTORICAL`: the schema below was never shipped as-is. The actual `JsonLogWriter` emits
> **flat** top-level fields: `ts`, `level`, `logger`, `thread`, `msg` (formatted), key/value
> pairs at top level, MDC keys at top level (reserved keys skipped), and for exceptions
> `errClass`, `errMessage`, `stack` (JSON string of sanitized frames), `errHash` (fingerprint).
> See `plans/inconsistency-report.md` §1 for the full mismatch analysis.

```json
{
  "ts": 1748765696789,
  "level": "DEBUG",
  "logger": "com.example.OrderService",
  "thread": "main",
  "msg": "Change state to {state}",
  "kv": {"state": "PAID"},
  "ctx": {"requestId": "abc-123"},
  "err": {
    "class": "java.lang.RuntimeException",
    "msg": "something broke",
    "stack": ["com.example.MyClass.method", "com.example.Main.main"],
    "hash": 1234567890,
    "cause": {"class": "java.io.IOException", "msg": "connection refused"}
  },
  "msgTpl": "Change state to {state}"
}
```

Key design decisions (as written — HISTORICAL):
- `ts` is epoch millis (not ISO-8601) for parsing efficiency — ✅ still true
- `err.stack` contains sanitized frames (no line numbers, no lambda IDs) for deterministic dedup — ✅ concept kept; emitted as a JSON string
- `err.hash` is Wyhash64 of pipe-joined sanitized frames for fast grouping — ✅ concept kept; field renamed `errHash`
- `msgTpl` preserves the original message template for structured analysis — ❌ dropped; not emitted
- Named placeholders (`{name}`) in `msg` are kept literal (not expanded) for downstream tools — ❌ dropped; `msg` is the formatted message

## Generic Type Hierarchy (as written — HISTORICAL)

> `HISTORICAL`: this CRTP design was **dropped**. Actual hierarchy:
> `LoggingEventBuilderWrapperBase` (non-generic) ← `LoggingEventBuilderWrapper`,
> `LoggingEventBuilderWrapperNoop` (covariant overrides); `DiaLoggerBase<L extends LoggingEventBuilderWrapperBase>`
> ← `DiaLogger`. See `plans/analysis-report.md` §20.

```
LoggingEventBuilderWrapper<L extends LoggingEventBuilderWrapper<L>>
  └─ abstract self() → subclasses return (L) this

DiaLogger<L extends LoggingEventBuilderWrapper<L>>
  ├─ abstract initBuilder(LoggingEventBuilder) → L
  ├─ abstract noOpWrapper() → L
  ├─ abstract contextStart(L) → void
  └─ abstract contextEnd() → void
```

This allows subclasses to extend both DiaLogger and LoggingEventBuilderWrapper with additional fluent methods while maintaining correct return types in the chain.

## Module Structure (as written — HISTORICAL)

> `HISTORICAL`: `CustomJsonEncoder` removed; `project-automation` module added (derivative
> generator); logback now has `JsonAppender`, `JsonAppenderRolling`, `JsonLogWriter`,
> `JsonLogWriterClassic` and the generated sanitizer derivatives.

```
dia-log-root (pom.xml)
├── core/         (dia-log-core)       — DiaLogger, LoggingEventBuilderWrapper, JavaStackSanitizer, Wyhash64
├── logback/      (dia-log-logback)    — CustomJsonEncoder, JsonLogWriter
├── example/      (dia-log-example)    — Main.java with logback.xml
├── cookbook/      (docs)               — additional.error-only.log.md, stackWhenTrace.md, missing-keys-warn.md
└── .gitignore
```
