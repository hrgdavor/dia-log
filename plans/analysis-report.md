# Dia-Log — Project Analysis & Improvement Recommendations

Date: 2026-07-30

---

## Executive Summary

Dia-Log is a well-structured diagnostic logging library built on SLF4J 2.0 for Java 21. The project has a solid foundation with a clean generic type hierarchy, zero-allocation Wyhash64 hashing, and structured JSON output. However, there are several critical bugs, code quality issues, test coverage gaps, and build infrastructure deficiencies that should be addressed.

---

## P0 — Critical Issues (Fix Immediately)

### 1. Bug in `DiaLoggerBase.atLevel(Level, LogFiller)` — Wrong level check

**File:** `core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:102`

```java
// Current (buggy):
public L atLevel(Level level, LogFiller filler) {
    if(!delegate.isEnabledForLevel(level)) return noOpWrapper();  // ← uses delegate directly
    return fill(atLevel(level),filler);
}
```

All other `atXxx(Level, LogFiller)` methods use `isEnabledForLevel(level)` (the `DiaLoggerBase` method), but this one uses `delegate.isEnabledForLevel(level)` (the raw `Logger` method). This bypasses any level-checking logic in `DiaLoggerBase` and could cause inconsistent behavior if a subclass overrides `isEnabledForLevel()`.

**Fix:** Change `delegate.isEnabledForLevel(level)` to `isEnabledForLevel(level)`.

---

### 2. `JsonLogWriter` — Stack trace array is commented out; use `writeTraceString()` instead

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:232-237`

The `err.stack` field (sanitized frame array) is entirely commented out. The JSON output only includes `err.hash` but not `err.stack`. This defeats the purpose of having deterministic stack trace sanitization — consumers cannot see the actual sanitized frames, only a hash. The schema documented in `plan.md` includes `stack`, but the implementation omits it.

Meanwhile, `writeTraceString()` (lines 312-343) is dead code that already implements streaming stack trace serialization directly to the JSON generator — no intermediate string allocation. It should be integrated into `writeJsonEvent()` to produce the `err.stack` field.

**Fix:** Remove the commented-out `stack` array code (lines 232-237), integrate `writeTraceString()` into `writeJsonEvent()` to write `err.stack` as a JSON string, and remove the now-unused `writeTraceString()` method from the class.

---

### 3. `JavaStackSanitizerLogback` duplicates `JavaStackSanitizer`

**Files:**
- `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java`
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java`

The logback module has its own `JavaStackSanitizerLogback` that duplicates the fingerprinting logic from `JavaStackSanitizer` in the core module. The logback version even calls `JavaStackSanitizer.addFromTrace()` and `JavaStackSanitizer.NEWLINE` — it depends on the core class but reimplements the `fingerprint()` method.

**Fix:** Remove `JavaStackSanitizerLogback` and have the logback module use `JavaStackSanitizer.fingerprint()` directly. Or better yet, move the `fingerprint(IThrowableProxy, Predicate)` overload into `JavaStackSanitizer` in the core module so both modules can use it.

---

### 4. `LoggingEventBuilderWrapperBase` Javadoc claims `AutoCloseable` but doesn't implement it

**File:** `core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java:20-26`

The class-level Javadoc shows a try-with-resources example:
```java
try (var log = new LoggingEventBuilderWrapper(logger.atDebug())) {
```

But the class does not implement `AutoCloseable`. The `close()` method is missing. This means the try-with-resources example in the documentation would not compile.

**Fix:** Remove the `AutoCloseable` try-with-resources example from the Javadoc. The class is a lightweight wrapper — it doesn't hold resources that need closing. MDC cleanup is handled by SLF4J automatically after `log()` returns.


---

## P1 — Important Issues

### 6. Inconsistent JUnit version management

**Files:**
- `pom.xml` (root): defines `<junit-version>6.1.0</junit-version>`
- `core/pom.xml`: hardcodes `<version>5.11.4</version>` for `junit-jupiter`
- `logback/pom.xml`: uses `${junit-version}` (6.1.0)

The core module uses JUnit 5.11.4 while the logback module uses JUnit 6.1.0 (via the parent property). These are different major versions with different APIs.

**Fix:** Remove the hardcoded version from `core/pom.xml` and use `${junit-version}` consistently, or move the `junit-jupiter` dependency to root `dependencyManagement`.

---

### 7. `logback-classic` and `jackson-databind` not centralized in `dependencyManagement`

**File:** `logback/pom.xml`

These dependencies have versions only in the logback module's POM, not in the root `dependencyManagement`. This makes it harder to upgrade versions consistently and could lead to version conflicts if other modules need them.

**Fix:** Add `logback.version` and `jackson.version` properties to root `pom.xml` and move these dependencies to `dependencyManagement`.

---

### 8. `JsonLogWriter.NL` uses `System.lineSeparator()` — OS-dependent line endings

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:44`

```java
public static final byte[] NL = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
```

JSON Lines format requires `\n` (LF) as the line separator. Using `System.lineSeparator()` produces `\r\n` on Windows, which breaks JSON Lines parsers that expect `\n`.

**Fix:** Use `"\n".getBytes(StandardCharsets.UTF_8)` instead.

---

### 9. `DiaLoggerBase.prependPrefix()` is `synchronized` unnecessarily

**File:** `core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:36`

The `prependPrefix()` method is `synchronized`, but prefix is typically set once at initialization. The synchronization adds unnecessary contention for a logging library where every log call goes through the wrapper.

**Fix:** Remove `synchronized` unless there's a proven need for concurrent prefix modification.

---

### 11. Missing unit tests for key classes

| Missing Test | Class Under Test | What's Untested |
|---|---|---|
| `TraceIdTest` | `TraceId.java` | Generation, uniqueness, byte/string consistency, span ID non-zero |
| `DiaLoggerTest` | `DiaLogger.java` | Concrete behavior, prefix, `addKeyValues`, `atLevel` |
| `LoggingEventBuilderWrapperBaseTest` | `LoggingEventBuilderWrapperBase.java` | Null keys, null values, multiple `log()` calls, `stackWhenTraceEnabled` edge cases |
| `Wyhash64EdgeCaseTest` | `Wyhash64.java` | Empty input, single byte, large input, seed=0 |
| `JsonLogWriterTest` | `JsonLogWriter.java` | JSON structure, exception serialization, special character escaping |
| `JavaStackSanitizerLogbackTest` | `JavaStackSanitizerLogback.java` | Fingerprint from IThrowableProxy, cause chain |

---

### 12. `JsonLogWriter` uses `JavaStackSanitizerLogback.fingerprint()` instead of core sanitizer

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:240`

```java
gen.writeStringField("hash", JavaStackSanitizerLogback.fingerprint(tp, elem -> true));
```

This creates a dependency from the logback module to a logback-specific sanitizer class, when it should use the core `JavaStackSanitizer`. This also means the hash computation is tied to the logback module's implementation rather than the core module's.

**Fix:** Use `JavaStackSanitizer.fingerprint()` from the core module, or add a `fingerprint(IThrowableProxy)` overload to `JavaStackSanitizer`.

---

## P2 — Nice-to-Have Improvements

### 13. No Javadoc on public API

Most public classes and methods lack Javadoc with `{@code ...}`, `@param`, `@return`, and usage examples. This is required for Maven Central publishing and makes the API harder to use.

### 14. No `LICENSE`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`

Required for Maven Central publishing and open-source collaboration.

### 15. No Maven Enforcer Plugin

No Java version enforcement, no banned dependencies (e.g., `commons-logging`, `log4j-over-slf4j`).

### 16. No JaCoCo code coverage

No coverage reporting configured. The existing plan targets 80% line coverage.

### 17. No CI pipeline

No `.github/workflows/ci.yml` for automated build/test on push/PR.

### 18. `TraceId.generateTraceId()` uses `String.format()` — allocates `Formatter`

**File:** `core/src/main/java/hr/hrg/dialog/core/TraceId.java:37-41`

`String.format("%016x%016x", ...)` allocates a `Formatter` object. For a hot-path method called on every log event, this is wasteful.

**Fix:** Use `StringBuilder` with `Long.toHexString()` and zero-padding.

### 19. `usage.brainstorm.md` is empty

The file is essentially a placeholder with no content. Either remove it or flesh it out.

### 20. `DiaLoggerBase.addKeyValues()` uses raw types

**File:** `core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:43`

```java
public static <L1 extends LoggingEventBuilderWrapperBase> L1 addKeyValues(L1 builder, Object ...keyVal)
```

The method doesn't use the self-referential generic type pattern (`L extends LoggingEventBuilderWrapperBase<L>`), which means it doesn't support subclass chaining properly.

### 21. `ConsoleAppenderDev`, `ConsoleAppenderJson`, `RollingFileAppenderJson` don't exist on disk

The open tabs and plan reference these files, but they don't exist in the `logback/src/main/java/hr/hrg/dialog/logback/` directory. Only `CustomJsonEncoder.java`, `JavaStackSanitizerLogback.java`, and `JsonLogWriter.java` exist. This suggests either the files were never created or were deleted but the tabs/plan weren't updated.

### 22. `JsonLogWriter` `addKey()` doesn't handle non-String, non-POJO value types well

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:299-308`

The `addKey()` method handles `String` and falls back to `writePOJO()` for everything else. For `Number` types (Integer, Long, Double), `writePOJO()` works but is slower than using type-specific Jackson methods like `writeNumber()`.

---

## Summary of Recommendations by Priority

| Priority | Count | Key Areas |
|---|---|---|
| P0 Critical | 5 | Bug fixes, dead code, DRY violations, missing AutoCloseable |
| P1 Important | 7 | Dependency management, test coverage, reserved fields, line endings |
| P2 Nice-to-Have | 10 | Documentation, CI/CD, performance, publishing prep |

---

## Suggested Execution Order

```
Phase 1: P0 Fixes (1-2 days)
  ├── Fix atLevel() bug in DiaLoggerBase
  ├── Integrate writeTraceString() into writeJsonEvent() for err.stack output, remove commented-out array
  ├── Remove JavaStackSanitizerLogback duplication
  ├── Fix AutoCloseable Javadoc mismatch
  └── Remove writeTraceString() dead code (after integrating it)

Phase 2: P1 Fixes (2-3 days)
  ├── Centralize dependency versions in root POM
  ├── Fix NL line separator in JsonLogWriter
  ├── Fix isReserved() to include all schema fields
  ├── Fix prependPrefix() synchronization
  ├── Fix JsonLogWriter to use core JavaStackSanitizer
  └── Add missing unit tests

Phase 3: P2 Improvements (ongoing)
  ├── Add Javadoc to public API
  ├── Add LICENSE, CHANGELOG.md, CONTRIBUTING.md, SECURITY.md
  ├── Add Maven Enforcer Plugin + JaCoCo + CI pipeline
  ├── Optimize TraceId.generateTraceId()
  └── Clean up usage.brainstorm.md
```

---

## Comparison with Existing Plan (`plans/dia-log-improvements.md`)

The existing improvement plan covers many of the same areas but has some gaps:

1. **Missing:** The `atLevel(Level, LogFiller)` bug is not mentioned in the existing plan.
2. **Missing:** The `JavaStackSanitizerLogback` duplication is not addressed.
3. **Missing:** The `isReserved()` incomplete field list is not mentioned.
4. **Missing:** The `NL` line separator issue is not mentioned.
5. **Missing:** The P0.2 recommendation to use `writeTraceString()` for stack trace output (instead of just uncommenting the array) is not in the existing plan.
6. **Outdated:** The plan references `ConsoleAppenderDev.java`, `ConsoleAppenderJson.java`, and `RollingFileAppenderJson.java` as existing files, but they don't exist on disk.
7. **Outdated:** The plan references `SegmentedJsonStringWriter.java` as dead code, but it doesn't exist on disk.
8. **Overlapping:** The existing plan's Phase 1 items (1.1-1.4) overlap with P0 items in this analysis but add different items (e.g., `prependPrefix()` rename which is already done).
