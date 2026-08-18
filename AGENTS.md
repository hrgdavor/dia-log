# Dia-Log Agent Guidelines

## Performance-Critical Code Patterns

### Code Duplication is Intentional Micro-Optimization

The stack trace sanitization classes exhibit massive code duplication as a **deliberate performance optimization**:

- `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java` - **Canonical source** - modify this file ONLY
- `core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java` - Core, no-filter derivative
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java` - Filter-enabled, logback input
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackWriterLogback.java` - No-filter, logback input

**WORKFLOW**: Modify `JavaStackSanitizer.java` (canonical source), then run the generator to sync all derivatives:

```bash
mvn -pl project-automation compile exec:java \
    -Dexec.mainClass=hr.hrg.dialog.tools.StackSanitizerDerivativeGenerator
```

**DO NOT EDIT DERIVATIVE FILES DIRECTLY** - They are auto-generated and will be overwritten.

**DO NOT REFACTOR INTO A COMMON BASE CLASS** - This would:
- Introduce virtual dispatch overhead in hot paths
- Prevent JIT inlining optimizations  
- Potentially degrade logging performance

**Key locations to check when modifying**:
- Lines 15-300 in `JavaStackSanitizer.java` (main normalization logic)
- Lambda handling at `// LAMBDA_SUFFIX_FOR_CLASS` and `// LAMBDA_PREFIX_FOR_METHOD` markers
- Filter logic at `filter.test(className)` checks
- Fallback logic blocks marked with `// @sanitizer:begin` and `// @sanitizer:end`

### Hotspot Allocation Patterns to Avoid

When adding new features, check these allocation hotspots:

- `StringByteExtractor.writeClassic()` - allocates a `byte[]` per string (fallback path only;
  the VarHandle fast path is allocation-free)

**Dev/diagnostic variants are excluded from zero-allocation efforts.** Classes such as
`JsonLogWriterDev` (missing-key reporting), `JsonLogWriterClassic`, and benchmark fixtures
are tools, not hot paths — do **not** add micro-optimizations (guard scans, strided reads,
buffer reuse) to them; keep them straightforward and correct. Allocating in a dev variant
to avoid a scan that costs more than the allocation is the wrong trade.

Previously flagged and **already resolved — do not reintroduce**:

- `Wyhash64.Streaming.finalHash()` - no longer allocates a scratch `byte[16]`; the final
  16-byte window is read directly from `buf`
- `JsonLogWriter.writeJsonEvent()` - `allKeys` is lazily allocated, only when KV pairs exist
  **and** MDC is present (dedup against MDC is the only use)
- `Float.toString()` / `Double.toString()` in `JsonNumberWriter` - replaced by Ryu
  (`RyuFloat` / `RyuDouble`); also note `String.value` UTF-16 byte order is platform-native,
  never assume it (Wyhash64 probes it once at class init)

### Thread Safety Requirements

- `DiaLoggerBase.prefix` must remain `volatile` if `prependPrefix()` exists
- `JsonLogWriter.stackTraceFilter` - configured once at startup via `setStackTraceFilter()`; no concurrent mutation, no additional synchronization required
- `JsonAppender.activeStream` - intentionally non-volatile; `writeOut()` snapshots it to a local variable to avoid splitting a single log event across concurrent stream changes
- `JsonAppenderRolling.activeStream` - intentionally non-volatile; `writeOut()` snapshots it to a local variable to avoid splitting a single log event across concurrent stream changes

### JSON Escape Discipline

All string keys passed to `writeFieldPrefixRawKey()` in `JsonLogWriter.java` are **JSON-escaped**
(quotes, backslash, control chars) via `EscapedJsonStringWriter`, because KV/MDC keys are user
input. Raw unescaped bytes are written only where the caller explicitly requests raw JSON
(`RawValue` / `RawJsonBytes` passthrough).

### Java Markdown Comments (`///`)

`///` line comments are an intentional lightweight **"Java markdown" doc style** used for
concise class-level notes in this project (e.g. the `JsonLogWriter` header at
`logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`). They are **not** a
C#-style inconsistency and must **not** be converted to `/** */` Javadoc. Treat them as the
project's accepted short-form documentation; use standard Javadoc only where
`@param`/`@return`/`{@code ...}` documentation is genuinely needed for API-facing members.

---

## File Locations Reference

**Core classes**:
- `core/src/main/java/hr/hrg/dialog/core/`

**Logback adapter classes**:
- `logback/src/main/java/hr/hrg/dialog/logback/`

**Test classes**:
- `core/src/test/java/hr/hrg/dialog/core/`
- `logback/src/test/java/hr/hrg/dialog/logback/`

**Benchmarks**:
- `core/src/test/java/hr/hrg/dialog/core/*Benchmark.java`
- `logback/src/test/java/hr/hrg/dialog/logback/*Benchmark.java`