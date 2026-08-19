# Plans Folder

Analysis, improvement, and audit documents for the Dia-Log project. Statuses reflect the
codebase at HEAD `ded5fa2` (v1.0.0 released 2026-08-11; `mvn clean verify -Dgpg.skip=true`
on JDK 25 passes with enforcer + JaCoCo).

## Documents

| File                                                                   | Purpose                                                                                                                            | Status                                                                |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| [`analysis-report.md`](analysis-report.md)                             | Original project analysis + improvement recommendations (2026-07-30); every item annotated with implementation status (2026-08-17) | ✅ **All items resolved (2026-08-17)**                               |
| [`inconsistency-report.md`](inconsistency-report.md)                   | 36 doc-vs-code inconsistencies (2026-08-06); many resolved by the codebase evolution                                               | **Historical** — schema/docs mismatches largely fixed                 |
| [`dia-log-improvements.md`](dia-log-improvements.md)                   | Phased implementation plan (P0–P6); most phases done, a few items remain (tests, enforcer, JaCoCo, CI, CONTRIBUTING/SECURITY)      | **Stale** — superseded by `analysis-report.md`                        |
| [`critical-analysis.md`](critical-analysis.md)                         | Critical analysis: intentional duplication, thread safety, zero-allocation, JSON safety, build issues; status-annotated 2026-08-17 | **Live** — companion to `analysis-report.md`                          |
| [`roadmap.md`](roadmap.md)                                             | Original design roadmap (formerly root `plan.md`); archived as history, annotated with what actually shipped                       | **Archived** — historical reference only                              |
| [`fix-wyhash-unicode-bugs.md`](fix-wyhash-unicode-bugs.md)             | Wyhash64 Unicode/UTF-16 bug fix plan (3 failing tests)                                                                             | ✅ **Implemented** — all 58 `WyhashZeroAllocTest` tests pass           |
| [`align-java-stack-trace-writer.md`](align-java-stack-trace-writer.md) | Manual alignment procedure for `JavaStackTraceWriter`                                                                              | ✅ **Implemented** — superseded by `StackSanitizerDerivativeGenerator` |

## Consolidation history

- 2026-08-17: Root `plan.md` → `roadmap.md` (archived), root `plan3.md` → `critical-analysis.md`
  (status-annotated), root `plan2.md` deleted — its content (condensed 36-issue summary) is
  fully covered by `inconsistency-report.md`. Root plan files removed; link references in
  `inconsistency-report.md` repointed to `roadmap.md`.

## Status (2026-08-17)

**Everything actionable in these plans is implemented and verified:**
`mvn clean verify -Dgpg.skip=true -pl core,logback,example` → BUILD SUCCESS (enforcer,
JaCoCo report + check, 300+ tests).

- Tests: all planned files added (`TraceIdTest`, `DiaLoggerTest`, `Wyhash64EdgeCaseTest`,
  `LoggingEventBuilderWrapperBaseTest`, `JsonLogWriterTest`, `ExampleIntegrationTest`)
- Code: `prefix` volatile, lazy `allKeys`, JSON-key escaping, `System.err` removal,
  `addKeyValues()` validation, `packChars`/`packCharsLow` removal, `@FunctionalInterface`
  on `LogFiller`, `@ThreadSafe`/`@NotThreadSafe` annotations, class Javadoc completed
- Build/docs: Maven Enforcer, JaCoCo (enforced check: line ≥80% / branch ≥70%;
  actual core 91.7%/82.4%, logback 92.1%/81.2%), `.github/workflows/ci.yml`,
  `CONTRIBUTING.md`, `SECURITY.md`, usage guide (`doc/usage.md`), migration guide
  (`doc/migration.md`), zero-allocation design doc (`doc/wyhash64-zero-allocation.md`)

Non-blocking observations that remain open (see `critical-analysis.md`): the
`StringByteExtractor.writeClassic()` and `Float/Double.toString()` allocation hotspots
(3.2/3.3), the silent MDC-suppression catch (5.2), and per-byte buffering in
`writeLatin1()` (7.3).

## errHash Field Emission Note

The `errHash` field is emitted as **`"errHash"`** — a single JSON key named *errHash* under the exception info object, with its value being the Wyhash64 fingerprint of the sanitized stack trace. It is not named `"hash"`, `"error hash"`, or placed under an `"errors"`/`"exception"` container; it is written directly as `KEY_ERR_HASH` (line 65, JsonLogWriter.java) alongside `errClass` and `errMessage`. This differs from the original roadmap schema that documented `"hash": <value>` at top level — the current code emits a nested field named `"errHash"`, consistent with how `err.class`/`err.message`/`stack` are written.
