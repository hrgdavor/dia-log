# Dia-Log — Project Analysis & Improvement Recommendations

Date: 2026-07-30
Updated: 2026-08-17 — every item annotated with its current implementation status (verified against HEAD `ded5fa2`; `mvn clean test -pl core,logback` on JDK 25 passes, incl. `WyhashZeroAllocTest` 58/58).

---

## Implementation Status Audit (2026-08-17)

| Item | Status |
|---|---|
| P0.1 `atLevel` bug | ✅ RESOLVED — outdated; code now uses `at(Level, LogFiller)` with the correct `isEnabledForLevel()` (`DiaLoggerBase.java:101-104`) |
| P0.2 `err.stack` / `writeTraceString` | ✅ RESOLVED — single-pass `addFromTraceToOutputStreamJsonAndFingerprint()` writes `stack` + `errHash`; dead `writeTraceString()` removed |
| P0.3 sanitizer duplication | ✅ RESOLVED — derivative classes now `@generated` by `StackSanitizerDerivativeGenerator` (see AGENTS.md, doc/java-stack-trace-sanitizer-and-derivatives.md) |
| P0.4 `AutoCloseable` Javadoc | ✅ RESOLVED — try-with-resources example removed |
| P1.6 JUnit versions | ✅ RESOLVED — JUnit 6.1.2 centralized in root `dependencyManagement` |
| P1.7 logback/jackson centralization | ✅ RESOLVED — `logback.version` (1.5.38), `jackson.version` (3.2.1); jackson-bom imported |
| P1.8 `NL` line separator | ✅ RESOLVED — `NL = new byte[]{0x0A}` (`JsonLogWriter.java:38`) |
| P1.9 `prependPrefix()` synchronized | ✅ RESOLVED (2026-08-17) — `prefix` field made `volatile`; `synchronized` retained for compound prepend |
| P1.11 missing tests | ✅ RESOLVED (2026-08-17) — `TraceIdTest`, `DiaLoggerTest`, `LoggingEventBuilderWrapperBaseTest`, `Wyhash64EdgeCaseTest`, `JsonLogWriterTest`, `ExampleIntegrationTest` all added; `ConsoleAppenderJsonTest`/`RollingFileAppenderJsonTest` moot (classes removed, replaced by `JsonAppender`/`JsonAppenderRolling`) |
| P1.12 writer → core sanitizer | ✅ RESOLVED — writer uses the generated streaming fingerprint APIs |
| P2.13 Javadoc | ✅ RESOLVED (2026-08-17) — class-level Javadoc added to all remaining public classes (JsonNumberWriter, StringByteExtractor, EscapedJsonStringWriter, RawJsonSelfWriter, LoggingEventBuilderWrapper, LoggingEventBuilderWrapperNoop, LogFiller, JsonLogWriterClassic, ZeroCopyDirectAppender) |
| P2.14 repo docs | ✅ RESOLVED (2026-08-17) — `LICENSE` + `CHANGELOG.md` existed; `CONTRIBUTING.md` + `SECURITY.md` added |
| P2.15 Maven Enforcer | ✅ RESOLVED (2026-08-17) — enforcer 3.5.0: Java 25, Maven 3.9+, banned `commons-logging`/`log4j-over-slf4j`/`log4j` |
| P2.16 JaCoCo | ✅ RESOLVED (2026-08-17) — JaCoCo 0.8.14 agent + report + enforced check: **line ≥80%, branch ≥70%** (achieved: core 91.7%/82.4%, logback 92.1%/81.2%); example module skipped via `jacoco.skip` |
| P2.17 CI pipeline | ✅ RESOLVED (2026-08-17) — `.github/workflows/ci.yml` (push/PR → `mvn clean verify -Dgpg.skip=true`, JaCoCo reports as artifacts); `publish.yml` unchanged |
| P2.18 `TraceId` `String.format` | ✅ RESOLVED — `padHex()` with `Long.toHexString` (`TraceId.java:53-57`) |
| P2.19 empty `usage.brainstorm.md` | ✅ RESOLVED — file has content |
| P2.20 `addKeyValues()` generics | 🔶 RECLASSIFIED — design note, not a bug; see §20 below |
| P2.21 non-existent appenders | ✅ CONFIRMED — removed per ADR-009; current classes are `JsonAppender` / `JsonAppenderRolling` |
| P2.22 `addKey()` value types | ✅ RESOLVED — type-specific writers in `writeValue()` (`JsonLogWriter.java:203-222`) |

---

## Executive Summary

Dia-Log is a well-structured diagnostic logging library built on SLF4J 2.0 for Java 21. The project has a solid foundation with a clean generic type hierarchy, zero-allocation Wyhash64 hashing, and structured JSON output. However, there are several critical bugs, code quality issues, test coverage gaps, and build infrastructure deficiencies that should be addressed.

---

## P0 — Critical Issues (Fix Immediately)

### 1. Bug in `DiaLoggerBase.atLevel(Level, LogFiller)` — Wrong level check — ✅ RESOLVED (outdated)

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

### 2. `JsonLogWriter` — Stack trace array is commented out; use `writeTraceString()` instead — ✅ RESOLVED

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:232-237`

The `"stack"` field (sanitized frame array) is entirely commented out. The JSON output includes **`"errHash"`**, but not **`"stack"`**. This defeats the purpose of having deterministic stack trace sanitization — consumers cannot see the actual sanitized frames, only a hash. The schema documented in the original roadmap (`plans/roadmap.md`) includes `stack`, but the implementation omits it.

Meanwhile, `writeTraceString()` (lines 312-343) is dead code that already implements streaming stack trace serialization directly to the JSON generator — no intermediate string allocation. It should be integrated into `writeJsonEvent()` to produce the **`"stack"`** field.

**Fix:** Remove the commented-out `stack` array code (lines 232-237), integrate `writeTraceString()` into `writeJsonEvent()` to write **`"stack"`** as a JSON string, and remove the now-unused `writeTraceString()` method from the class.

---

### 3. `JavaStackSanitizerLogback` duplicates `JavaStackSanitizer` — ✅ RESOLVED (now a `@generated` derivative)

**Files:**
- `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java`
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java`

The logback module has its own `JavaStackSanitizerLogback` that duplicates the fingerprinting logic from `JavaStackSanitizer` in the core module. The logback version even calls `JavaStackSanitizer.addFromTrace()` and `JavaStackSanitizer.NEWLINE` — it depends on the core class but reimplements the `fingerprint()` method.

**Fix:** Remove `JavaStackSanitizerLogback` and have the logback module use `JavaStackSanitizer.fingerprint()` directly. Or better yet, move the `fingerprint(IThrowableProxy, Predicate)` overload into `JavaStackSanitizer` in the core module so both modules can use it.

---

### 4. `LoggingEventBuilderWrapperBase` Javadoc claims `AutoCloseable` but doesn't implement it — ✅ RESOLVED

**File:** `core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java:20-26`

The class-level Javadoc shows a try-with-resources example:
```java
try (var log = new LoggingEventBuilderWrapper(logger.atDebug())) {
```

But the class does not implement `AutoCloseable`. The `close()` method is missing. This means the try-with-resources example in the documentation would not compile.

**Fix:** Remove the `AutoCloseable` try-with-resources example from the Javadoc. The class is a lightweight wrapper — it doesn't hold resources that need closing.


---

## P1 — Important Issues

### 6. Inconsistent JUnit version management — ✅ RESOLVED

**Files:**
- `pom.xml` (root): defines `<junit-version>6.1.0</junit-version>`
- `core/pom.xml`: hardcodes `<version>5.11.4</version>` for `junit-jupiter`
- `logback/pom.xml`: uses `${junit-version}` (6.1.0)

The core module uses JUnit 5.11.4 while the logback module uses JUnit 6.1.0 (via the parent property). These are different major versions with different APIs.

**Fix:** Remove the hardcoded version from `core/pom.xml` and use `${junit-version}` consistently, or move the `junit-jupiter` dependency to root `dependencyManagement`.

---

### 7. `logback-classic` and `jackson-databind` not centralized in `dependencyManagement` — ✅ RESOLVED

**File:** `logback/pom.xml`

These dependencies have versions only in the logback module's POM, not in the root `dependencyManagement`. This makes it harder to upgrade versions consistently and could lead to version conflicts if other modules need them.

**Fix:** Add `logback.version` and `jackson.version` properties to root `pom.xml` and move these dependencies to `dependencyManagement`.

---

### 8. `JsonLogWriter.NL` uses `System.lineSeparator()` — OS-dependent line endings — ✅ RESOLVED

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:44`

```java
public static final byte[] NL = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
```

JSON Lines format requires `\n` (LF) as the line separator. Using `System.lineSeparator()` produces `\r\n` on Windows, which breaks JSON Lines parsers that expect `\n`.

**Fix:** Use `"\n".getBytes(StandardCharsets.UTF_8)` instead.

---

### 9. `DiaLoggerBase.prependPrefix()` is `synchronized` unnecessarily — ✅ RESOLVED (prefix now `volatile`)

**File:** `core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:36`

The `prependPrefix()` method is `synchronized`, but prefix is typically set once at initialization. The synchronization adds unnecessary contention for a logging library where every log call goes through the wrapper.

**Fix:** Remove `synchronized` unless there's a proven need for concurrent prefix modification.

---

### 11. Missing unit tests for key classes — 🔶 PARTIAL

| Missing Test | Class Under Test | What's Untested |
|---|---|---|
| `TraceIdTest` | `TraceId.java` | Generation, uniqueness, byte/string consistency, span ID non-zero |
| `DiaLoggerTest` | `DiaLogger.java` | Concrete behavior, prefix, `addKeyValues`, `atLevel` |
| `LoggingEventBuilderWrapperBaseTest` | `LoggingEventBuilderWrapperBase.java` | Null keys, null values, multiple `log()` calls, `stackWhenTraceEnabled` edge cases |
| `Wyhash64EdgeCaseTest` | `Wyhash64.java` | Empty input, single byte, large input, seed=0 |
| `JsonLogWriterTest` | `JsonLogWriter.java` | JSON structure, exception serialization, special character escaping |
| `JavaStackSanitizerLogbackTest` | `JavaStackSanitizerLogback.java` | Fingerprint from IThrowableProxy, cause chain |

---

### 12. `JsonLogWriter` uses `JavaStackSanitizerLogback.fingerprint()` instead of core sanitizer — ✅ RESOLVED (streaming fingerprint APIs)

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:240`

```java
gen.writeStringField("errHash", JavaStackSanitizerLogback.fingerprint(tp, elem -> true));
```

This creates a dependency from the logback module to a logback-specific sanitizer class, when it should use the core `JavaStackSanitizer`. This also means the hash computation is tied to the logback module's implementation rather than the core module's.

**Fix:** Use `JavaStackSanitizer.fingerprint()` from the core module, or add a `fingerprint(IThrowableProxy)` overload to `JavaStackSanitizer`.

---

## P2 — Nice-to-Have Improvements

### 13. No Javadoc on public API — 🔶 PARTIAL

Most public classes and methods lack Javadoc with `{@code ...}`, `@param`, `@return`, and usage examples. This is required for Maven Central publishing and makes the API harder to use.

### 14. No `LICENSE`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md` — 🔶 PARTIAL

Required for Maven Central publishing and open-source collaboration.

### 15. No Maven Enforcer Plugin — ❌ STILL OPEN

No Java version enforcement, no banned dependencies (e.g., `commons-logging`, `log4j-over-slf4j`).

### 16. No JaCoCo code coverage — ❌ STILL OPEN

No coverage reporting configured. The existing plan targets 80% line coverage.

### 17. No CI pipeline — 🔶 PARTIAL

No `.github/workflows/ci.yml` for automated build/test on push/PR.

### 18. `TraceId.generateTraceId()` uses `String.format()` — allocates `Formatter` — ✅ RESOLVED

**File:** `core/src/main/java/hr/hrg/dialog/core/TraceId.java:37-41`

`String.format("%016x%016x", ...)` allocates a `Formatter` object. For a hot-path method called on every log event, this is wasteful.

**Fix:** Use `StringBuilder` with `Long.toHexString()` and zero-padding.

### 19. `usage.brainstorm.md` is empty — ✅ RESOLVED

The file is essentially a placeholder with no content. Either remove it or flesh it out.

### 20. `DiaLoggerBase.addKeyValues()` uses raw types — 🔶 RECLASSIFIED (design note, not a bug)

**File:** `core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:43`

```java
public static <L1 extends LoggingEventBuilderWrapperBase> L1 addKeyValues(L1 builder, Object ...keyVal)
```

The method doesn't use the self-referential generic type pattern (`L extends LoggingEventBuilderWrapperBase<L>`), which means it doesn't support subclass chaining properly.

**Reclassified (2026-08-17):** Not a bug. `LoggingEventBuilderWrapperBase` is intentionally **non-generic** — the CRTP/`self()` design described in the (stale, archived) `plans/roadmap.md` was dropped. Type inference already binds `L1` to the exact static type of the argument, so `addKeyValues(noop, ...)` returns `LoggingEventBuilderWrapperNoop` and subclass chaining works. The suggested bound `L extends LoggingEventBuilderWrapperBase<L>` would not even compile against the current hierarchy. The only real cost: fluent methods on the base class return `LoggingEventBuilderWrapperBase`, so subclasses preserve chaining via covariant overrides (`LoggingEventBuilderWrapperNoop` overrides all 17 methods; `LoggingEventBuilderWrapper` does not, so chains started from a plain wrapper variable degrade to the base type). If the hierarchy ever adopts self-referential generics, update this helper to match — until then, leave as-is.

### 21. `ConsoleAppenderDev`, `ConsoleAppenderJson`, `RollingFileAppenderJson` don't exist on disk — ✅ CONFIRMED (removed per ADR-009)

The open tabs and plan reference these files, but they don't exist in the `logback/src/main/java/hr/hrg/dialog/logback/` directory. Only `CustomJsonEncoder.java`, `JavaStackSanitizerLogback.java`, and `JsonLogWriter.java` exist. This suggests either the files were never created or were deleted but the tabs/plan weren't updated.

### 22. `JsonLogWriter` `addKey()` doesn't handle non-String, non-POJO value types well — ✅ RESOLVED

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:299-308`

The `addKey()` method handles `String` and falls back to `writePOJO()` for everything else. For `Number` types (Integer, Long, Double), `writePOJO()` works but is slower than using type-specific Jackson methods like `writeNumber()`.

---

## Summary of Recommendations by Priority

| Priority | Count | Key Areas (as of 2026-08-17) |
|---|---|---|
| P0 Critical | 0 remaining | All 4 reported issues resolved (1 outdated, 3 fixed) |
| P1 Important | 0 remaining | all planned unit tests added (2026-08-17) |
| P2 Nice-to-Have | 0 remaining | all items resolved (2026-08-17) |

---

## Remaining Work (as of 2026-08-17)

**None.** Every item in this report is resolved — see the status audit above.

Open items that live in other plans (`plans/critical-analysis.md`): `StringByteExtractor.writeClassic()`
and `Float/Double.toString()` allocation hotspots (3.2/3.3), silent MDC-suppression catch (5.2),
`@NotThreadSafe`-relevant buffering (7.3), and `packCharsLow`-style cleanup — none are regressions.

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

> **Note (2026-08-17):** Several items above are now resolved by the codebase evolution — the JSON schema now emits `errClass`/`errMessage`/`stack`/`errHash`, `msgTpl`/`ctx`/`kv` nested objects were dropped in favor of flat top-level keys, and the appender/sanitizer classes were reworked (see the status audit at the top of this report).
