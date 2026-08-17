# Dia-Log Project Critical Analysis

> **Status audit added 2026-08-17.** This analysis was originally at the repo root
> (`plan3.md`). Every section is annotated with its current status, verified against the
> codebase at HEAD `ded5fa2`. Sections marked ✅ are resolved, ❌ still open, 🔶 by design /
> partial.

## Executive Summary

This document provides a critical analysis of the dia-log diagnostic logging library, identifying issues, design decisions, and potential improvements while respecting the intentional performance optimizations.

---

## 1. Massive Code Duplication (Intentional Micro-Optimization)

**Status**: `Intentional design decision` - The code duplication across stack trace sanitization classes is **deliberately intentional** to avoid runtime JVM optimizations. — ✅ **Still accurate.** Also enforced in `AGENTS.md` and `doc/java-stack-trace-sanitizer-and-derivatives.md`.

**WORKFLOW**: Modify `JavaStackSanitizer.java` (canonical source), then run the generator to sync all derivatives:

```bash
mvn -pl project-automation compile exec:java \
    -Dexec.mainClass=hr.hrg.dialog.tools.StackSanitizerDerivativeGenerator
```

**DO NOT EDIT DERIVATIVE FILES DIRECTLY** - They are auto-generated and will be overwritten.

**Classes involved**:
- `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java` - **Canonical** implementation
- `core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java` - No-filter core derivative
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java` - Filter-enabled logback variant
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackWriterLogback.java` - No-filter logback variant

**Rationale**:
- Eliminates virtual dispatch overhead in hot paths
- Allows compiler/jit to inline aggressively
- Each variant is self-contained for maximum optimization
- Generator applies marker-based transformations for each variant

---

## 2. Thread Safety Issues (Critical)

**Issues found**:

### 2.1 `DiaLoggerBase.java:36` — ✅ RESOLVED (2026-08-17: `prefix` now `volatile`)
```java
public synchronized void prependPrefix(String prefix){
    if(this.prefix == null)
        this.prefix = prefix;
    else
        this.prefix = prefix+this.prefix;
}
```
- `synchronized` method but `prefix` field is **not volatile** — fixed: field is now `protected volatile String prefix;` (`DiaLoggerBase.java:23`); `synchronized` retained so the compound prepend stays atomic
- Other threads may never see updated prefix value — resolved via volatile
- Race condition if prefix is read while being modified — resolved via volatile

> Note: `AGENTS.md` states `DiaLoggerBase.prefix` *must remain volatile* if `prependPrefix()` exists — the field is still non-volatile, so this item is open.

### 2.2 `JsonAppender.java` and `JsonAppenderRolling.java` — 🔶 BY DESIGN
```java
private OutputStream activeStream;
```
- Not volatile, not thread-safe
- `setOutputStream()` writes, `writeOut()` reads
- Race condition possible during concurrent logging/stream changes

> `AGENTS.md` documents this as intentional: `activeStream` is deliberately non-volatile; `writeOut()` snapshots it to a local variable so a single log event is never split across concurrent stream changes.

### 2.3 `JsonLogWriter` `stackTraceFilter` — 🔶 BY DESIGN
```java
private Predicate<String> stackTraceFilter = null;
```
- Non-volatile, not synchronized
- Filter could change during fingerprinting

> `AGENTS.md` documents this as intentional: the filter is configured once at startup via `setStackTraceFilter()`; no concurrent mutation, so no synchronization required.

---

## 3. Zero-Allocation Claims Compromised (High Impact)

The README advertises "low-allocation hot path" but multiple allocation sites exist:

### 3.1 `Wyhash64.java` `Streaming.finalHash()` — ✅ RESOLVED
```java
byte[] scratch = new byte[16];
```
- **Always allocates when `inputLen < 16 && totalLen > 16`**
- Breaks zero-allocation promise for finalization

> Fixed: `finalHash()` now reads both longs directly from the two `buf` regions with no scratch array (see comment at `Wyhash64.java:1181` "no scratch array allocation").

### 3.2 `StringByteExtractor.java` - Classic Fallback — ❌ STILL OPEN
```java
public static void writeClassic(OutputStream out, String s) throws IOException {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    out.write(bytes, 0, bytes.length);
}
```
- **Always allocates `byte[]` in fallback path** (`StringByteExtractor.java:117-118`)

### 3.3 `JsonNumberWriter.java` — ❌ STILL OPEN
```java
STRING_STRATEGY.write(out, Float.toString(value));
STRING_STRATEGY.write(out, Double.toString(value));
```
- `Float.toString()` and `Double.toString()` **always allocate** `String` objects

> Related in-flight work: the working tree contains an uncommitted `core/src/main/java/hr/hrg/dialog/ryu/` package (Ryu float/double formatting) plus `JsonNumberWriter` modifications — likely aimed at this hotspot.

### 3.4 `JsonLogWriter.java` — ✅ RESOLVED (2026-08-17: lazy init)
```java
Set<String> allKeys = new HashSet<>();
```
- **Always allocates HashSet**, even when no KV pairs or MDC — fixed: `allKeys` is now lazily created only when KV pairs exist (`JsonLogWriter.java:98`), so events without KV/MDC allocate no HashSet
- Should be lazily initialized

---

## 4. API Design Issues

### 4.1 `DiaLoggerBase.java` - Massive Boilerplate — ✅ STILL TRUE
- Implements full SLF4J `Logger` interface directly
- **437 lines** of repetitive delegation (still 437 lines)
- High surface area for API breakage if SLF4J changes

### 4.2 `DiaLoggerBase.addKeyValues()` - Silent Failures — ✅ STILL TRUE (companion to reclassified §20 in `plans/analysis-report.md`)
```java
public static  <L1 extends  LoggingEventBuilderWrapperBase> L1 addKeyValues(L1 builder, Object ...keyVal) {
    for(int i=1; i< keyVal.length; i+=2) {
        Object key = keyVal[i-1];
        if(key == null) continue;  // Silently skipped
```
- Odd-length arrays silently drop last element
- Null keys silently skipped with no warning
- No validation of key-value pair completeness

> Note: this is a *different* concern from the generics item (P2.20 in the analysis report, which was reclassified as not-a-bug). The silent-failure behavior here is a real (minor) API ergonomics issue that remains.

### 4.3 `JavaStackSanitizerLogback.java` - Unused Parameter — 🔶 OUTDATED (regenerated)
```java
public static long fingerprint(IThrowableProxy rootCause, Predicate<String> filter) {
    // ... filter parameter exists but is never used in fallback path
```
> The class has been regenerated by `StackSanitizerDerivativeGenerator` since this was written; the current `fingerprint()` variants use the filter in the streaming path (`addFromTraceToOutputStreamJsonAndFingerprint`). Re-verify before acting.

---

## 5. Error Handling Issues

### 5.1 `JsonLogWriter.java` - Wrong Error Channel — ❌ STILL OPEN
```java
catch (IOException e) {
    System.err.println(Instant.now() + " Failed to write JSON log event for logger: " + event.getLoggerName());
    e.printStackTrace(System.err);
    throw e;
}
```
- Uses `System.err` instead of SLF4J's `addError()` or logging framework's error channel (still at `JsonLogWriter.java:167-170`)

### 5.2 `JsonLogWriter.java` - Silent MDC Suppression — ❌ STILL OPEN
```java
try {
    mdcMap = event.getMDCPropertyMap();
} catch (Exception ignored) {}
```
- Silently swallows ALL exceptions from MDC retrieval (still at `JsonLogWriter.java:111-115`)
- Could hide configuration errors

---

## 6. JSON Safety Issues

### 6.1 `JsonLogWriter.java` - Unescaped Keys — ✅ RESOLVED (2026-08-17)
```java
private static void writeFieldPrefixRawKey(OutputStream out, String key) throws IOException {
    out.write(',');
    EscapedJsonStringWriter.writeJsonStringOrNull(out, key);  // now JSON-escaped
    out.write(':');
}
```
- **KV/MDC keys are NOT JSON-escaped** — fixed: keys are now escaped (quotes, backslash, control chars) via `EscapedJsonStringWriter`, since KV/MDC keys are user input
- Keys containing `"`, `\`, or other special chars produce malformed JSON — resolved; `AGENTS.md` updated accordingly

---

## 7. Code Quality Issues

### 7.1 Unused/Dead Code — 🔶 PARTIAL
- `Wyhash64.java` - `packChars()` and `packCharsLow()` — both still present (`Wyhash64.java:625,633`); `packCharsLow` usage not re-verified
- `TraceId.java` - Unused class — ❌ OUTDATED: `TraceId` is now a documented public API class (byte[]/String form, OTel-compatible static methods). Still no `TraceIdTest`.

### 7.2 `JavaStackWriterLogback.java` - Unused Parameter — 🔶 OUTDATED (regenerated)
```java
public static long fingerprint(IThrowableProxy rootCause, Predicate<String> filter) {
    // filter parameter accepted but never passed to addFromTrace
```
> Same regeneration caveat as §4.3.

### 7.3 Inconsistent Buffering — 🔶 OPEN (minor)
- `StringByteExtractor.writeLatin1()` writes byte-by-byte
- May be inefficient with unbuffered streams

---

## 8. Build/POM Issues

### 8.1 GPG Signing Configuration — 🔶 PARTIAL
- Parent POM configures `maven-gpg-plugin`
- `example` module must skip signing
- `project-automation` module may fail on build

> Partially addressed: child POMs configure `skipSource`/`skip` for source/javadoc plugins; `.github/workflows/publish.yml` exists for releases (JDK 25, GPG secrets). Whether `example`/`project-automation` artifacts should be published at all is a remaining packaging question.

### 8.2 Jackson Dependency — ✅ STILL TRUE
- Uses `tools.jackson` (Jackson 3 Tooling profile) not standard `com.fasterxml.jackson` — still the case (`jackson.version` 3.2.1, `tools.jackson.core`/`tools.jackson.databind` in `logback/pom.xml`)
- Non-standard dependency coordinates could confuse users

---

## 9. Concurrency Hazards

### 9.1 Inconsistent Thread-Safety Model in `DiaLoggerBase` — ❌ STILL OPEN
- `synchronized prependPrefix()` exists
- But `prefix` is not `volatile`
- Many other fields have no protection

### 9.2 Multiple Synchronization Points Needed — 🔶 BY DESIGN (documented)
- `activeStream` in appenders needs `volatile` or `synchronized` access — documented intentional (AGENTS.md)
- `stackTraceFilter` needs volatile semantics or explicit synchronization — documented intentional (AGENTS.md)

---

## 10. Documentation Gaps

### 10.1 Missing API Documentation — 🔶 PARTIAL
- `RawJsonSelfWriter` and `RawJsonBytes` - no usage examples — still the case
- `packCharsLow()` in Wyhash64 - should be removed or documented — still present

### 10.2 Comment Style Inconsistency — ✅ STILL TRUE
- Some files use `///` C# style comments (not standard Java documentation) — e.g. `JsonLogWriter` class comment

---

## Recommendations Summary — status updated 2026-08-17

### Immediate Fixes (High Priority)
1. Make `prefix` field `volatile` or remove synchronization from `prependPrefix()` — ✅ DONE (volatile added 2026-08-17)
2. Make `activeStream` and `stackTraceFilter` volatile in appenders/writer — 🔶 DECLINED by design (AGENTS.md documents intentional non-volatility + snapshot)
3. Escape JSON keys in `writeFieldPrefixRawKey()` — ✅ DONE (2026-08-17)
4. Remove dead code (`packCharsLow`, `TraceId.java`) — 🔶 `TraceId` kept (now public API); `packCharsLow` still present
5. Use `addError()` instead of `System.err` for IOException — ❌ OPEN

### Medium Priority
1. Lazily initialize `allKeys` HashSet in `JsonLogWriter` — ✅ DONE (2026-08-17)
2. Use `StringBuilder` instead of `StringBuffer` in `addFromTraceToStringBuffer()` — 🔶 VERIFY (derivative files regenerated since)
3. Document that key duplication is intentional performance optimization — ✅ DONE (AGENTS.md + doc/java-stack-trace-sanitizer-and-derivatives.md)

### Low Priority
1. Consider code generation that produces non-commented variants — ✅ DONE (StackSanitizerDerivativeGenerator)
2. Add proper `@ThreadSafe` or `@NotThreadSafe` annotations — ❌ OPEN
3. Add `@FunctionalInterface` to `LogFiller` — 🔶 VERIFY

### Accepted Trade-offs
1. **Code duplication is intentional** - Modify `JavaStackSanitizer.java` only, then regenerate — ✅ still the rule
2. **Generator preserves comments** - Commented code in derivatives is intentional scaffolding for debugging — ✅ still the rule
3. **Java 25 + --add-opens required** - Document requirement clearly for users — ✅ documented in README (`--add-opens java.base/java.lang=ALL-UNNAMED` for `StringByteExtractor`)
