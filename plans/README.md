# Plans Folder

Analysis, improvement, and audit documents for the Dia-Log project. Statuses reflect the
codebase at HEAD `ded5fa2` (v1.0.0 released 2026-08-11; `mvn clean test -pl core,logback` on
JDK 25 passes).

## Documents

| File                                                                   | Purpose                                                                                                                            | Status                                                                |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| [`analysis-report.md`](analysis-report.md)                             | Original project analysis + improvement recommendations (2026-07-30); every item annotated with implementation status (2026-08-17) | **Live** — use this as the primary gap list                           |
| [`inconsistency-report.md`](inconsistency-report.md)                   | 36 doc-vs-code inconsistencies (2026-08-06); many resolved by the codebase evolution                                               | **Historical** — schema/docs mismatches largely fixed                 |
| [`dia-log-improvements.md`](dia-log-improvements.md)                   | Phased implementation plan (P0–P6); most phases done, a few items remain (tests, enforcer, JaCoCo, CI, CONTRIBUTING/SECURITY)      | **Stale** — not yet annotated like `analysis-report.md`               |
| [`critical-analysis.md`](critical-analysis.md)                         | Critical analysis: intentional duplication, thread safety, zero-allocation, JSON safety, build issues; status-annotated 2026-08-17 | **Live** — companion to `analysis-report.md`                          |
| [`roadmap.md`](roadmap.md)                                             | Original design roadmap (formerly root `plan.md`); archived as history, annotated with what actually shipped                       | **Archived** — historical reference only                              |
| [`fix-wyhash-unicode-bugs.md`](fix-wyhash-unicode-bugs.md)             | Wyhash64 Unicode/UTF-16 bug fix plan (3 failing tests)                                                                             | ✅ **Implemented** — all 58 `WyhashZeroAllocTest` tests pass           |
| [`align-java-stack-trace-writer.md`](align-java-stack-trace-writer.md) | Manual alignment procedure for `JavaStackTraceWriter`                                                                              | ✅ **Implemented** — superseded by `StackSanitizerDerivativeGenerator` |

## Consolidation history

- 2026-08-17: Root `plan.md` → `roadmap.md` (archived), root `plan3.md` → `critical-analysis.md`
  (status-annotated), root `plan2.md` deleted — its content (condensed 36-issue summary) is
  fully covered by `inconsistency-report.md`. Root plan files removed; link references in
  `inconsistency-report.md` repointed to `roadmap.md`.

## Known remaining gaps (see `analysis-report.md` for details)

- Tests: `DiaLoggerTest`, `Wyhash64EdgeCaseTest`, `LoggingEventBuilderWrapperBaseTest`, `ExampleIntegrationTest` (`TraceIdTest` + `JsonLogWriterTest` added 2026-08-17)
- Build: Maven Enforcer, JaCoCo, `ci.yml` (push/PR verify)
- Docs: `CONTRIBUTING.md`, `SECURITY.md`, remaining Javadoc
- Code: `System.err` error channel; `addKeyValues()` silent failures; `packCharsLow` cleanup
