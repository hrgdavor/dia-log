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

1. `Wyhash64.Streaming.finalHash()` line 1093 - allocates `byte[16]`
2. `JsonLogWriter.writeJsonEvent()` line 96 - allocates `HashSet<String>`
3. `StringByteExtractor.writeClassic()` - allocates `byte[]` per string
4. `Float.toString()` / `Double.toString()` in JsonNumberWriter

### Thread Safety Requirements

- `DiaLoggerBase.prefix` must remain `volatile` if `prependPrefix()` exists
- `JsonLogWriter.stackTraceFilter` - make volatile or document as thread-confined
- `JsonAppender.activeStream` - consider ThreadLocal for per-thread writers

### JSON Escape Discipline

All string keys passed to `writeFieldPrefixRawKey()` in `JsonLogWriter.java:184` are **NOT JSON-escaped**. If accepting user input, add escaping.

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