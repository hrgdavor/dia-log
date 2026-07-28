# Dia-Log Improvement Implementation Plan

## Overview

This plan breaks down the recommended improvements into actionable, ordered steps. Each phase can be executed independently and reviewed before proceeding.

---

## Phase 1: Critical Code Quality Fixes (P0)

### 1.1 Fix `DiaLoggerBase.addPrefix()` Logic

**File:** `core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java`

**Current behavior:** `prefix = prefix + this.prefix` prepends the new prefix to existing.
**Issue:** Counter-intuitive; most developers expect `addPrefix` to append.

**Options:**
- **A:** Reverse to `this.prefix = this.prefix + prefix` (append semantics)
- **B:** Rename method to `prependPrefix()` and document behavior
- **C:** Keep current behavior but add Javadoc explaining the prepend semantics

**Chosen:** Option B — rename to `prependPrefix()` to make the prepend semantics explicit.

**Steps:**
1. Rename `addPrefix()` to `prependPrefix()` in `DiaLoggerBase.java`
2. Update any documentation referencing the old method name

---

### 1.2 Replace `System.out` in `LoggingEventBuilderWrapperBase.closeContext()` — SKIPPED

**File:** `core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java`

**Status:** Skipped — left as-is for now. A code comment has been added to mark this as intentional/known.

**Reason:** The `System.out` print in `closeContext()` is a known issue but was not addressed in this pass. Future work should replace it with SLF4J logging and narrow the catch to `Exception`.

---

### 1.3 Remove Dead Code — SKIPPED

**Files:**
- `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java` — `writeTraceString()` (lines 298-329)
- `logback/src/main/java/hr/hrg/dialog/logback/SegmentedJsonStringWriter.java` — entire file
- `logback/src/main/java/hr/hrg/dialog/logback/CustomJsonEncoder.java` — entire file

**Status:** Skipped — left as-is for now. Code comments have been added to mark these as intentionally kept / dead code to be removed later.

**Reason:** Dead code removal was deferred. The unused classes/methods remain in the codebase with comments indicating they should be removed in a future cleanup.

---

### 1.4 Fix `LoggingEventBuilderWrapperNoop` Return Types

**File:** `core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperNoop.java`

**Issue:** `stackWhenTraceEnabled()` returns `LoggingEventBuilderWrapperBase` instead of `LoggingEventBuilderWrapperNoop`.

**Steps:**
1. Change return type of `stackWhenTraceEnabled()` from `LoggingEventBuilderWrapperBase` to `LoggingEventBuilderWrapperNoop`
2. Verify all no-op methods return `LoggingEventBuilderWrapperNoop` consistently

---

## Phase 2: Test Coverage Expansion (P1)

### 2.1 Core Module Tests

**New test files / additions:**

| Test Class | What to Test | Estimated Lines |
|------------|---------------|-----------------|
| `TraceIdTest` | Generation, uniqueness, byte/string consistency, span ID non-zero | 80 |
| `DiaLoggerTest` | Concrete `DiaLogger` behavior, prefix, `addKeyValues`, `atLevel` | 120 |
| `LoggingEventBuilderWrapperBaseTest` | Edge cases: null keys, supplier returning null, multiple `log()` calls | 100 |
| `Wyhash64EdgeCaseTest` | Empty input, single byte, large input, seed=0 | 60 |

**Steps:**
1. Create `core/src/test/java/hr/hrg/dialog/core/TraceIdTest.java`
2. Create `core/src/test/java/hr/hrg/dialog/core/DiaLoggerTest.java`
3. Add edge case tests to existing `LoggingEventBuilderWrapperTest`
4. Add edge case tests to existing `Wyhash64Test`

---

### 2.2 Logback Module Tests

**New test files:**

| Test Class | What to Test | Estimated Lines |
|------------|---------------|-----------------|
| `ConsoleAppenderJsonTest` | JSON structure, all levels, MDC/keys toggles, exception output, custom fields | 200 |
| `RollingFileAppenderJsonTest` | Same as console but with file output | 150 |
| `JsonLogWriterTest` | Direct writer tests: newline handling, null values, pretty print | 180 |
| `JavaStackSanitizerLogbackTest` | Fingerprint from IThrowableProxy, cause chain | 100 |

**Steps:**
1. Create `logback/src/test/java/hr/hrg/dialog/logback/ConsoleAppenderJsonTest.java`
2. Create `logback/src/test/java/hr/hrg/dialog/logback/RollingFileAppenderJsonTest.java`
3. Create `logback/src/test/java/hr/hrg/dialog/logback/JsonLogWriterTest.java`
4. Create `logback/src/test/java/hr/hrg/dialog/logback/JavaStackSanitizerLogbackTest.java`

---

### 2.3 Integration Test

**New file:** `example/src/test/java/hr/hrg/dialog/example/ExampleIntegrationTest.java`

**What to test:**
- Run example `Main.java` and capture output
- Verify JSON lines are valid
- Verify all log levels appear
- Verify structured KV pairs are present

**Steps:**
1. Create integration test using `System.setOut` capture or `ByteArrayOutputStream`
2. Invoke `Main.main()` and parse output
3. Assert JSON structure and content

---

## Phase 3: Build & Dependency Management (P1)

### 3.1 Centralize Dependency Versions

**File:** `pom.xml` (root)

**Current issue:** `junit-jupiter` version in child POMs, `logback-classic` and `jackson-databind` only in `logback/pom.xml`.

**Steps:**
1. Add `logback.version` and `jackson.version` properties to root `pom.xml`
2. Move `junit-jupiter` version to root `dependencyManagement`
3. Update child POMs to use `${logback.version}` and `${jackson.version}`
4. Remove version tags from child POM dependencies where inherited

---

### 3.2 Add Maven Enforcer Plugin

**File:** `pom.xml`

**Steps:**
1. Add `maven-enforcer-plugin` to root `build/plugins`
2. Require Java 21
3. Require Maven 3.9+
4. Ban `commons-logging`, `log4j-over-slf4j` (avoid duplicate bindings)

---

### 3.3 Add JaCoCo Code Coverage

**File:** `pom.xml`

**Steps:**
1. Add `jacoco-maven-plugin` to root `build/plugins`
2. Configure `prepare-agent` for test phase
3. Add `report` goal to generate HTML/XML reports
4. Set coverage thresholds (e.g., 80% line, 70% branch)

---

### 3.4 Add CI/CD Pipeline

**New file:** `.github/workflows/ci.yml`

**Steps:**
1. Create workflow triggered on push and pull_request
2. Setup Java 21 (Temurin)
3. Run `mvn verify -B`
4. Upload JaCoCo reports as artifacts
5. Optionally add `mvn spotbugs:check` or `mvn checkstyle:check`

---

## Phase 4: Documentation & API Polish (P2)

### 4.1 Add Javadoc to Public API

**Files to document:**
- `core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java`
- `core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java`
- `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java`
- `core/src/main/java/hr/hrg/dialog/core/Wyhash64.java`
- `core/src/main/java/hr/hrg/dialog/core/TraceId.java`
- `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`
- `logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderJson.java`
- `logback/src/main/java/hr/hrg/dialog/logback/RollingFileAppenderJson.java`
- `logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderDev.java`

**Steps:**
1. Add class-level Javadoc with `{@code}` examples to each file
2. Add `@param`/`@return` to all public methods
3. Generate Javadoc with `mvn javadoc:javadoc` and verify output

---

### 4.2 Add Missing Project Documentation

**New files:**
- `LICENSE` — Choose and add appropriate license (e.g., MIT, Apache 2.0)
- `CHANGELOG.md` — Initial entry for v1.0.0-SNAPSHOT
- `CONTRIBUTING.md` — Build instructions, PR process, code style
- `SECURITY.md` — Vulnerability reporting process

**Steps:**
1. Add `LICENSE` file
2. Create `CHANGELOG.md` with initial version
3. Create `CONTRIBUTING.md` with build/test instructions
4. Create `SECURITY.md` with reporting instructions

---

### 4.3 Fix Example `logback.xml`

**File:** `example/src/main/resources/logback.xml`

**Issue:** `DEV` appender is defined but not referenced in `<root>`.

**Steps:**
1. Either add `<appender-ref ref="DEV" />` to root, or remove the `DEV` appender definition
2. Update README if example configuration changes

---

### 4.4 Clean Up `usage.brainstorm.md`

**File:** `usage.brainstorm.md`

**Current state:** Essentially empty placeholder.

**Steps:**
1. Either remove the file or flesh it out with actual usage brainstorming notes
2. If keeping, add concrete usage patterns and open questions

---

## Phase 5: Performance Optimizations (P3)

### 5.1 Optimize `ConsoleAppenderDev` Placeholder Expansion

**File:** `logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderDev.java`

**Current:** O(n²) due to `String.indexOf` in loop + linear KV search.

**Optimized approach:**
1. Build `Map<String, String>` from `List<KeyValuePair>` once
2. Use `Matcher` with regex `\\{([^}]+)\\}` to find all placeholders in one pass
3. Replace using map lookup

**Steps:**
1. Refactor `expandMessageTracked()` to use `Pattern.compile("\\{([^}]+)\\}")`
2. Build map from KV pairs before matching
3. Update tests to verify behavior is unchanged

---

### 5.2 Optimize `ConsoleAppenderDev.writeThrowable()`

**File:** `logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderDev.java`

**Current:** Allocates `ByteArrayOutputStream` + `PrintStream`.

**Optimized:**
```java
private static void writeThrowable(OutputStream out, Throwable t) throws IOException {
    try (PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8.name())) {
        t.printStackTrace(ps);
    }
}
```

**Steps:**
1. Replace `writeThrowable()` implementation with direct stream wrapping
2. Verify tests still pass

---

### 5.3 Refactor `Wyhash64` to Eliminate Duplication — COMPLETED (intentional)

**File:** `core/src/main/java/hr/hrg/dialog/core/Wyhash64.java`

**Status:** Completed — duplication is intentional for performance.

**Reason:** The `hash(byte[])` and `hash(ByteBuffer)` methods contain identical logic. This duplication is intentional — extracting a shared method would introduce abstraction overhead (virtual calls, interface dispatch, or extra indirection) that would degrade performance in this hot-path code. The JIT compiler can better optimize the duplicated, self-contained methods. A class-level Javadoc comment has been added to document this decision.

---

### 5.4 Optimize `TraceId` String Generation

**File:** `core/src/main/java/hr/hrg/dialog/core/TraceId.java`

**Current:** Uses `String.format("%016x%016x", ...)`.

**Optimized:**
```java
public static String generateTraceId() {
    long timestampMs = System.currentTimeMillis();
    long randomPart = ThreadLocalRandom.current().nextLong();
    return HEX_LOWERCASE.formatHex(new byte[] {
        (byte)(timestampMs >> 56), (byte)(timestampMs >> 48), ...
    });
}
```

Or use `StringBuilder` with `Long.toHexString()` and zero-padding.

**Steps:**
1. Replace `String.format` with `StringBuilder` + `Long.toHexString` + padding
2. Add benchmark or microtest to verify performance improvement

---

## Phase 6: Maven Central Publishing Prep (P4)

### 6.1 Add Publishing Plugins

**Files to modify:** `pom.xml` (root and child modules)

**Steps:**
1. Add `maven-source-plugin` to root `build/plugins`
2. Add `maven-javadoc-plugin` to root `build/plugins`
3. Add `maven-gpg-plugin` (profile for release)
4. Add `nexus-staging-maven-plugin` for automated deployment
5. Add SCM, license, developer info to root POM

---

### 6.2 Add Release Profile

**File:** `pom.xml`

**Steps:**
1. Create `release` profile that activates GPG signing, source jar, javadoc jar
2. Document release process in `CONTRIBUTING.md`

---

## Execution Order & Dependencies

```
Phase 1 (P0 fixes)
    └── Phase 2 (Tests) — tests validate Phase 1 fixes
        └── Phase 3 (Build/CI) — CI runs tests
            └── Phase 4 (Docs) — docs describe the tested API
                └── Phase 5 (Perf) — perf optimizations on stable code
                    └── Phase 6 (Publishing) — final release prep
```

Each phase should be completed, reviewed, and committed before starting the next.

---

## Risk Assessment

| Change | Risk | Mitigation |
|--------|------|------------|
| `prependPrefix()` rename | Low — method rename only, behavior unchanged | Update references in docs |
| `closeContext()` logging change | Low — internal behavior | Add test for warning log |
| Dead code removal | Low — if references confirmed | Search entire codebase + check git history |
| Return type fix in Noop | Low — internal | Add test verifying fluent chaining |
| Placeholder expansion rewrite | Medium — subtle behavior changes | Comprehensive tests before/after |
| Wyhash64 duplication | None — intentional for performance | Documented in class Javadoc |

---

## Success Criteria

- [ ] All P0 fixes implemented and tested
- [ ] Test coverage ≥ 80% (JaCoCo)
- [ ] CI pipeline green on all branches
- [ ] All public API documented with Javadoc
- [ ] No dead code remains
- [ ] `mvn verify` passes cleanly with enforcer + spotbugs + checkstyle
- [ ] README example `logback.xml` is valid and functional
