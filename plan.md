# Dia-Log — Diagnostic & Contextual Logging Plan

## Current State

Multi-module Maven library (`hr.hrg.dialog:dia-log-root`) targeting Java 21, built on SLF4J 2.0.17.

| Module    | Artifact          | Contents                                                                                                    |
| --------- | ----------------- | ----------------------------------------------------------------------------------------------------------- |
| `core`    | `dia-log-core`    | `DiaLogger.java`, `LoggingEventBuilderWrapper.java`, `JavaStackSanitizer.java`, `Wyhash64.java`             |
| `logback` | `dia-log-logback` | `ConsoleAppenderJson.java`, `RollingFileAppenderJson.java`, `ConsoleAppenderDev.java`, `JsonLogWriter.java` |
| `example` | `dia-log-example` | `Main.java` with `logback.xml` demonstrating all appenders                                                  |

**What exists today:** Full SLF4J `Logger` delegation via `DiaLogger` (abstract, generic), `LoggingEventBuilderWrapper` (abstract, generic with `self()` for fluent chaining), JSON console/file/dev appenders with sanitized stack traces and hash-based dedup, cookbook docs.

---

## Phase 1 — Core API Completion

- [x] **DiaLogger** — Abstract generic class `DiaLogger<L extends LoggingEventBuilderWrapper>` implementing full `Logger` interface. All methods delegate through the wrapper. Supports `contextStart(L)`/`contextEnd()` lifecycle, `prependPrefix()`, `addKeyValues()`. Subclasses implement `initBuilder()` (creates wrapped builder) and `noOpWrapper()` (returns no-op for disabled levels).
- [x] **LoggingEventBuilderWrapper** — Abstract generic class `LoggingEventBuilderWrapper<L extends LoggingEventBuilderWrapper<L>>`. Wraps SLF4J `LoggingEventBuilder`, auto-cleans MDC keys after `log()`, supports `AutoCloseable`. Features: `stackWhenTrace()`, `kv()` shorthand, `Logger` reference. All fluent methods return `self()` for correct subclass chaining.
- [x] **JavaStackSanitizer** — Sanitizes stack frames for deterministic output: drops `jdk.internal.*`/`sun.reflect.*`, normalizes `$$Lambda` identifiers, strips line numbers, standardizes native calls. Provides `getSanitizedFrames()` (individual frames) and `getFingerprint()` (pipe-delimited).
- [x] **Wyhash64** — Fast 64-bit hash for stack trace deduplication. Includes `Streaming` inner class for incremental hashing.
- [ ] **1.1 Concrete DiaLogger subclass** — e.g. `DefaultDiaLogger<L>` implementing `contextStart()`/`contextEnd()`/`initBuilder()`/`noOpWrapper()` for common use cases (MDC propagation, prefix scoping).
- [x] **1.2 Unit tests for core** — `LoggingEventBuilderWrapperTest` (MDC cleanup, stackWhenTrace, kv shorthand, delegation, thread safety), `JavaStackSanitizerTest` (frame filtering, lambda normalization, native methods, maxFrames), `Wyhash64Test` (determinism, seed sensitivity, offset/length, ByteBuffer, streaming, edge cases), plus WyhashTestVectors, WyhashStandaloneTest, Wyhash64StreamingTest.

## Phase 2 — Logback Appenders

- [x] **ConsoleAppenderJson** — JSON console appender delegating to `JsonLogWriter`. Config: `includeMDC`, `includeKeys`, `includeSource`, `prettyPrint`, `customFields`.
- [x] **RollingFileAppenderJson** — Rolling file JSON appender delegating to `JsonLogWriter`. Same config as console.
- [x] **ConsoleAppenderDev** — Dev console appender with `{name}` placeholder expansion from `kv` pairs. Features: `expandPlaceholders` (default true), `warnOnMissingKeys` (opt-in, appends error + stack trace for missing keys).
- [x] **JsonLogWriter** — Shared JSON serializer. Output schema: `ts` (epoch millis), `level`, `logger`, `thread`, `msg`, `kv`, `ctx`, `source`, `err` (with sanitized `stack`, `hash`, `cause`), `msgTpl`, custom fields. Config: `includeMDC`, `includeKeys`, `includeSource`, `prettyPrint`, `customFields`, `maxStackFrames`.
- [ ] **2.1 maxStackFrames delegation** — Expose `maxStackFrames` on `ConsoleAppenderJson` and `RollingFileAppenderJson` (currently only on `JsonLogWriter`).
- [ ] **2.2 Unit tests for appenders** — Cover: JSON structure for all log levels, key-value inclusion/exclusion, MDC inclusion/exclusion, exception serialization (sanitized frames + hash), special character escaping, placeholder expansion, missing-key detection, configuration via logback.xml.

## Phase 3 — Documentation

- [x] **cookbook/additional.error-only.log.md** — Error-only log file pattern with standard and JSON appenders, zgrep examples, `.gz` clarification.
- [x] **cookbook/stackWhenTrace.md** — Conditional call-stack visibility: how it works, output examples (plain text, JSON, dev console), ThrowableProxy explanation, configuration.
- [x] **cookbook/missing-keys-warn.md** — Detecting missing log keys at runtime: warnOnMissingKeys feature, output examples, null handling, configuration.
- [ ] **3.1 README.md** — Project overview, maven coordinates, quick-start snippet, module descriptions.
- [ ] **3.2 Javadoc** — Public API fully documented with `{@code ...}`, `@param`, `@return`, usage examples.
- [ ] **3.3 Usage guide (`docs/usage.md`)** — Practical examples: basic setup, JSON output config, structured logging with `kv()`, contextual prefix, stackWhenTrace, missing key detection, error-only logs.
- [ ] **3.4 Migration guide (`docs/migration.md`)** — Step-by-step from plain SLF4J to Dia-Log.

## Phase 4 — Release Readiness

- [x] **4.0 .gitignore** — Standard Java/Maven/IDE gitignore covering `target/`, `.idea/`, `*.iml`, `.classpath`, `.project`, `.settings/`, `.vscode/`, OS files, logs.
- [ ] **4.1 Maven Central publishing** — GPG signing, nexus-staging-maven-plugin, POM metadata (SCM, license, developers).
- [ ] **4.2 CI pipeline** — GitHub Actions: `mvn verify`, JaCoCo coverage, static analysis.
- [ ] **4.3 Versioning policy** — Semantic versioning strategy.

---

## JSON Output Schema (Current)

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

Key design decisions:
- `ts` is epoch millis (not ISO-8601) for parsing efficiency
- `err.stack` contains sanitized frames (no line numbers, no lambda IDs) for deterministic dedup
- `err.hash` is Wyhash64 of pipe-joined sanitized frames for fast grouping
- `msgTpl` preserves the original message template for structured analysis
- Named placeholders (`{name}`) in `msg` are kept literal (not expanded) for downstream tools

## Generic Type Hierarchy

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

## Module Structure

```
dia-log-root (pom.xml)
├── core/         (dia-log-core)       — DiaLogger, LoggingEventBuilderWrapper, JavaStackSanitizer, Wyhash64
├── logback/      (dia-log-logback)    — ConsoleAppenderJson, RollingFileAppenderJson, ConsoleAppenderDev, JsonLogWriter
├── example/      (dia-log-example)    — Main.java with logback.xml
├── cookbook/      (docs)               — additional.error-only.log.md, stackWhenTrace.md, missing-keys-warn.md
└── .gitignore