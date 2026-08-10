## Key Findings Summary

### CRITICAL (5 issues)
1. **JSON schema completely mismatched** — `err.hash` is never emitted, `err.stack` is an empty string instead of an array, `msgTpl` contains formatted message not template, `ctx` nested object doesn't exist, `kv` nested object doesn't exist
2. **ADR-003 describes non-existent feature** — RESOLVED: ADR-003 has been marked **Not accepted**. All documentation referencing automatic MDC cleanup via `closeContext()` and `MDC.put/remove` has been removed/updated. MDC handling is left entirely to SLF4J.
5. **`JsonLogWriter` never writes `err.hash`** — The entire deduplication strategy depends on this field

### HIGH (4 issues)
1. **`JavaStackSanitizerLogback` duplicates core `JavaStackSanitizer`** — Code duplication in logback module
2. **Dead code remains** — `SegmentedJsonStringWriter`, incomplete `StreamDirectJacksonEncoder`
3. **JUnit version mismatch** — Core uses 5.11.4, logback uses 6.1.0
4. **Logback version mismatch** — logback module uses 1.5.18, example uses 1.5.16

### MEDIUM (15 issues)
- Java version mismatch (README says 21+, POM says 25)
- `CustomJsonEncoder` uses `System.lineSeparator()` instead of `\n`
- `isReserved()` references `kv` and `hash` as reserved but they're never written as nested objects
- `DiaLoggerBase.addKeyValues()` uses raw types without self-referential generics
- `DiaLoggerBase` void methods (`debug()`, `info()`, etc.) bypass level-checking optimization
- Multiple ADRs reference non-existent methods (`closeContext()`, `maybeAttachTraceCause()`) and wrong line numbers
- `plan.md` generic type hierarchy doesn't match actual code
- Documentation references non-existent appenders (`ConsoleAppenderDev`, `ConsoleAppenderJson`, `RollingFileAppenderJson`)

### LOW (12 issues)
- Empty `usage.brainstorm.md`
- `cookbook/missing-keys-warn.md` documents removed feature
- `doc/traceid.md` title is "span id" not "TraceId"
- `doc/zero-allocation-wyhash.md` describes `MemorySegment` approach not used in actual code
- Overlapping/conflicting plans (`analysis-report.md` vs `dia-log-improvements.md`)

The report includes specific file references and line numbers for all 36 identified inconsistencies.

