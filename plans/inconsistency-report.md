# Dia-Log Project Inconsistency Analysis

Date: 2026-08-06

> Note (2026-08-17): the root-level `plan2.md` was a condensed summary of this report;
> it has been removed — this file is the authoritative source for all 36 items. The root
> `plan.md`/`plan3.md` were incorporated as [`roadmap.md`](roadmap.md) (archived) and
> [`critical-analysis.md`](critical-analysis.md). See [`README.md`](README.md).

## Executive Summary

This report identifies inconsistencies between documentation, ADRs, plans, and actual code in the Dia-Log project. Many components have evolved since the original design documents were written, leading to mismatches in concepts, APIs, and expected behavior.

---

## 1. CRITICAL: Documentation vs. Actual JSON Output Schema

### 1.1 `err.hash` field is missing from `JsonLogWriter`

**Actual code:**
- [`JsonLogWriter.writeJsonEvent()`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:119-148) writes flat top-level fields: `err.class`, `err.msg`, `stack` (JSON string), and **`errHash`** — but the field is named **`"errHash"`**, not `"hash"`.

**Impact:** Consumers expecting the documented schema will not find it at the expected path. The deduplication strategy depends on this field being emitted, and while it *is* emitted — just with a different name than documented.

### 1.2 `err.stack` is an empty string, not an array

**Documentation claims:**
- [`roadmap.md`](roadmap.md) shows `"stack": ["com.example.MyClass.method", ...]` as an array of sanitized frames
- [`cookbook/stackWhenTrace.md`](cookbook/stackWhenTrace.md:65) shows `"stack":["\tat com.example..."]` as an array

**Actual code:**
- [`JsonLogWriter.java:132`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:132) writes `gen.writeString("")` — an empty string, not an array
- The array serialization code is commented out at lines 232-237

**Impact:** Consumers expecting an array of sanitized frames will receive an empty string.

### 1.3 `msgTpl` field writes formatted message, not template

**Actual code:**
- [`JsonLogWriter.java:83`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:83) writes `event.getFormattedMessage()` — this is the **already-formatted** message with placeholders replaced.
- [`JsonLogWriter.java:151`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:151) writes `event.getMessage()` for `msgTpl` — but `getMessage()` on `ILoggingEvent` returns the formatted message, not the template.

**Impact:** Both `msg` and `msgTpl` contain the same formatted message. The template with literal `{name}` placeholders is lost.

### 1.4 `ctx` field does not exist — MDC keys are flattened

**Documentation claims:**
- [`roadmap.md`](roadmap.md) shows `"ctx": {"requestId": "abc-123"}` as a nested object
- [`doc/adr/004-key-value-pairs-vs-mdc.md`](doc/adr/004-key-value-pairs-vs-mdc.md:39) references `ctx` in the priority rule

**Actual code:**
- [`JsonLogWriter.java:100-117`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:100-117) writes MDC keys directly at the top level of the JSON object, not under a `ctx` key
- There is no `ctx` object in the output

**Impact:** The documented schema does not match actual output. Consumers expecting `ctx` will not find it.

### 1.5 `kv` field is not a nested object

**Documentation claims:**
- [`roadmap.md`](roadmap.md) shows `"kv": {"state": "PAID"}` as a nested object
- [`README.md`](README.md:46) shows `"kv":{"userId":42,"action":"login"}`

**Actual code:**
- [`JsonLogWriter.java:88-98`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:88-98) writes key-value pairs directly at the top level, not under a `kv` key
- The `isReserved()` method at line 181 includes `"kv"` as reserved, suggesting it was intended to be a nested object, but the implementation doesn't create it

**Impact:** The `kv` object documented in the schema does not exist. Keys are flattened to the top level.

---

## 2. RESOLVED: ADR-003 Claims Non-Existent Behavior

### 2.1 `closeContext()` and MDC cleanup do not exist

**Historical issue:**
- [`doc/adr/003-automatic-mdc-cleanup.md`](doc/adr/003-automatic-mdc-cleanup.md) previously described an automatic MDC cleanup feature using `MDC.put()`/`MDC.remove()` and a `closeContext()` method that was never implemented.

**Actual code:**
- [`LoggingEventBuilderWrapperBase`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java) has **no `closeContext()` method**
- [`LoggingEventBuilderWrapperBase`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java) has **no `contextKeys` list**
- [`LoggingEventBuilderWrapperBase`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java) has **no `MDC.put()` or `MDC.remove()` calls**
- The `addKeyValue()` method delegates to `delegate.addKeyValue()` — no MDC interaction

**Resolution:** ADR-003 has been marked **Not accepted**. MDC handling is left entirely to SLF4J — Dia-Log does not manage MDC keys.

### 2.2 `LoggingEventBuilderWrapperBase` does not implement `AutoCloseable`

**ADR-002 and Javadoc claim:**
- [`doc/adr/002-slf4j-2-loggingeventbuilder-wrapper.md`](doc/adr/002-slf4j-2-loggingeventbuilder-wrapper.md:29) states: "The wrapper implements `LoggingEventBuilder` so it can be passed anywhere the standard builder is expected"
- [`LoggingEventBuilderWrapperBase.java:20-26`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java:20-26) Javadoc shows a try-with-resources example:
  ```java
  try (var log = new LoggingEventBuilderWrapper(logger.atDebug())) {
  ```

**Actual code:**
- [`LoggingEventBuilderWrapperBase`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java:38) implements `LoggingEventBuilder` but **not `AutoCloseable`**
- There is no `close()` method
- The try-with-resources example would not compile

**Impact:** Documentation shows usage patterns that don't compile.

---

## 3. CRITICAL: Bug in `DiaLoggerBase.atLevel(Level, LogFiller)`

**File:** [`core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:101-104`](core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:101-104)

```java
public L atLevel(Level level,LogFiller filler) {
    if(!isEnabledForLevel(level)) return noOpWrapper();
    return fill(atLevel(level),filler);
}
```

**Issue:** This method uses `isEnabledForLevel(level)` which is the `DiaLoggerBase` override. However, the analysis report at [`plans/analysis-report.md:17-29`](plans/analysis-report.md:17-29) claims the bug is that it uses `delegate.isEnabledForLevel(level)`. Looking at the actual code, it appears the current code is correct (uses `isEnabledForLevel(level)`), but the analysis report describes a different version of the code. This suggests the analysis report may be outdated or the code was fixed since the report was written.

**Status:** Needs verification — the analysis report describes a bug that may have already been fixed, or the report is referring to a different code path.

---

## 4. HIGH: Duplicate/Dead Code

### 4.1 `JavaStackSanitizerLogback` duplicates `JavaStackSanitizer`

**Files:**
- [`core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java`](core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java)
- [`logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java`](logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java)

**Issue:** `JavaStackSanitizerLogback` reimplements `fingerprint(IThrowableProxy, Predicate)` but delegates to `JavaStackSanitizer.addFromTraceElement()` and uses `JavaStackSanitizer.NEWLINE_BYTES` and `JavaStackSanitizer.DOT_BYTES`. The logback version depends on the core class but reimplements the fingerprinting loop.

**Impact:** Code duplication, maintenance burden, potential for divergence.

### 4.2 `SegmentedJsonStringWriter` is dead code

**File:** [`logback/src/main/java/hr/hrg/dialog/logback/SegmentedJsonStringWriter.java`](logback/src/main/java/hr/hrg/dialog/logback/SegmentedJsonStringWriter.java:7)

```java
// TODO(dia-log): Dead code — intentionally kept for now, remove in future cleanup.
```

**Issue:** The file is marked as dead code but still exists in the codebase. It is not referenced anywhere.

### 4.3 `StreamDirectJacksonEncoder` is incomplete

**File:** [`logback/src/main/java/hr/hrg/dialog/logback/StreamDirectJacksonEncoder.java`](logback/src/main/java/hr/hrg/dialog/logback/StreamDirectJacksonEncoder.java:10)

**Issue:** This class has a single method `encodeToStream()` but does not implement Logback's `Encoder` interface. It is not referenced by any other class. It appears to be an incomplete experiment.

### 4.4 `ZeroCopyDirectAppender` has a bug

**File:** [`logback/src/main/java/hr/hrg/dialog/logback/ZeroCopyDirectAppender.java:18-22`](logback/src/main/java/hr/hrg/dialog/logback/ZeroCopyDirectAppender.java:18-22)

```java
public void setOutputStream(OutputStream os) {
    if(g != null) g.close();
    this.g = jsonFactory.createGenerator(writeCtxt,targetOutputStream);  // BUG: uses targetOutputStream before assignment
    this.targetOutputStream = os;  // assignment happens AFTER use
}
```

**Issue:** `targetOutputStream` is used on line 20 before it is assigned on line 21. This will always pass `null` to `createGenerator()`.

---

## 5. HIGH: Dependency Management Inconsistencies

### 5.1 JUnit version mismatch

**Files:**
- [`pom.xml`](pom.xml:28): defines `<junit-version>6.1.0</junit-version>`
- [`core/pom.xml`](core/pom.xml:29): hardcodes `<version>5.11.4</version>` for `junit-jupiter`
- [`logback/pom.xml`](logback/pom.xml:58): uses `${junit-version}` (6.1.0)

**Issue:** Core module uses JUnit 5.11.4 while logback module uses JUnit 6.1.0. These are different major versions with different APIs.

### 5.2 Logback version mismatch across modules

**Files:**
- [`logback/pom.xml`](logback/pom.xml:42): `logback-classic` version 1.5.18
- [`example/pom.xml`](example/pom.xml:36): `logback-classic` version 1.5.16

**Issue:** Different logback versions in different modules.

### 5.3 Versions not centralized in `dependencyManagement`

**Issue:** `logback-classic` and `jackson-databind` versions are only in child POMs, not in root `dependencyManagement`. This makes consistent upgrades difficult.

---

## 6. MEDIUM: Java Version Mismatch

**README.md** states: "Java 21+"

**pom.xml** states:
```xml
<maven.compiler.source>25</maven.compiler.source>
<maven.compiler.target>25</maven.compiler.target>
```

**Issue:** The README says Java 21+ but the POM requires Java 25. The actual code uses Java 25 features (e.g., `Math.unsignedMultiplyHigh` is available since Java 21, but the POM target is 25).

---

## 7. MEDIUM: `JsonLogWriter` Uses `System.lineSeparator()` for NL

**File:** [`JsonLogWriter.java:27`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:27)

```java
public static final byte[] NL = new byte[]{ 0x0A };
```

**Wait** — looking more carefully, `JsonLogWriter.NL` is actually `{ 0x0A }` (Unix LF), which is correct for JSON Lines.

**BUT** [`CustomJsonEncoder.java:31`](logback/src/main/java/hr/hrg/dialog/logback/CustomJsonEncoder.java:31) uses:
```java
private static final byte[] NL = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
```

**Issue:** `CustomJsonEncoder` uses `System.lineSeparator()` which produces `\r\n` on Windows. This breaks JSON Lines format which requires `\n`. The `JsonLogWriter` correctly uses `\n`, but the encoder that calls it adds a different newline.

---

## 8. MEDIUM: `isReserved()` Missing `kv` from Reserved List Logic

**File:** [`JsonLogWriter.java:179-184`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:179-184)

```java
private boolean isReserved(String key) {
    return switch (key) {
        case "ts", "level", "logger", "thread", "msg", "err", "source", "msgTpl", "hash" -> true;
        default -> false;
    };
}
```

**Issue:** `"kv"` is listed as reserved in the switch, but the actual code never creates a `kv` object. The reserved check is used to prevent MDC keys from overwriting top-level fields, but since `kv` is never written as a nested object, this reservation is meaningless.

---

## 9. MEDIUM: `DiaLoggerBase.addKeyValues()` Uses Raw Types

**File:** [`DiaLoggerBase.java:43-50`](core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:43-50)

```java
public static  <L1 extends  LoggingEventBuilderWrapperBase> L1 addKeyValues(L1 builder, Object ...keyVal) {
    for(int i=1; i< keyVal.length; i+=2) {
        Object key = keyVal[i-1];
        if(key == null) continue;
        builder.addKeyValue(key.toString(), keyVal[i]);
    }
    return builder;
}
```

**Issue:** The method doesn't use the self-referential generic type pattern (`L extends LoggingEventBuilderWrapperBase<L>`), which means it doesn't support proper subclass chaining. The generic parameter `L1` is not bound to the actual type of `builder`.

---

## 10. MEDIUM: `LoggingEventBuilderWrapperNoop.stackWhenTraceEnabled()` Return Type

**File:** [`LoggingEventBuilderWrapperNoop.java:22`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperNoop.java:22)

```java
@Override
public LoggingEventBuilderWrapperNoop stackWhenTraceEnabled() { return INSTANCE;}
```

**Issue:** The return type is `LoggingEventBuilderWrapperNoop`, which is correct. However, the analysis report at [`plans/analysis-report.md:58`](plans/analysis-report.md:58) claims it returns `LoggingEventBuilderWrapperBase`. This may have been fixed since the report was written, or the report is outdated.

---

## 11. MEDIUM: `prependPrefix()` is `synchronized`

**File:** [`DiaLoggerBase.java:36`](core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:36)

```java
public synchronized void prependPrefix(String prefix){
```

**Issue:** The method is `synchronized`, but prefix is typically set once at initialization. The synchronization adds unnecessary contention for a logging library.

---

## 12. MEDIUM: `TraceId.generateTraceId()` Uses `String.format()`

**File:** [`TraceId.java:37-41`](core/src/main/java/hr/hrg/dialog/core/TraceId.java:37-41)

```java
public static String generateTraceId() {
    long timestampMs = System.currentTimeMillis();
    long randomPart = ThreadLocalRandom.current().nextLong();
    return padHex(timestampMs) + padHex(randomPart);
}
```

**Wait** — looking at the actual code, it uses `padHex()` which uses `Long.toHexString()` and `"0".repeat()`, not `String.format()`. The analysis report at [`plans/analysis-report.md:173-178`](plans/analysis-report.md:173-178) claims it uses `String.format("%016x%016x", ...)`. This appears to have been fixed since the report was written.

---

## 13. MEDIUM: `usage.brainstorm.md` is Empty

**File:** [`usage.brainstorm.md`](usage.brainstorm.md)

**Issue:** The file is essentially a placeholder with no content.

---

## 14. MEDIUM: Documentation References Non-Existent Files

### 14.1 `ConsoleAppenderDev`, `ConsoleAppenderJson`, `RollingFileAppenderJson`

**Referenced in:**
- [`doc/adr/009-consoleappenderdev.md`](doc/adr/009-consoleappenderdev.md:20) — describes `ConsoleAppenderDev` as created
- [`plans/dia-log-improvements.md`](plans/dia-log-improvements.md:187-189) — lists these as files to document
- [`cookbook/missing-keys-warn.md`](cookbook/missing-keys-warn.md:3) — references `ConsoleAppenderDev` as removed
- [`cookbook/stackWhenTrace.md`](cookbook/stackWhenTrace.md:56) — references `ConsoleAppenderJson`

**Actual files in `logback/src/main/java/hr/hrg/dialog/logback/`:**
- `CustomJsonEncoder.java`
- `JavaStackSanitizerLogback.java`
- `JsonLogWriter.java`
- `SegmentedJsonStringWriter.java` (dead code)
- `StreamDirectJacksonEncoder.java` (incomplete)
- `ZeroCopyDirectAppender.java` (buggy)

**Issue:** Multiple documentation files reference appenders that don't exist. Only `CustomJsonEncoder` exists as the production encoder.

### 14.2 `SegmentedJsonStringWriter` referenced as dead code in plans

**Referenced in:**
- [`plans/dia-log-improvements.md`](plans/dia-log-improvements.md:45) — lists `SegmentedJsonStringWriter.java` as dead code to remove

**Actual:** The file exists and is marked with a TODO comment as dead code.

---

## 15. MEDIUM: `roadmap.md` Generic Type Hierarchy is Outdated (archived)

**File:** [`roadmap.md`](roadmap.md) (Generic Type Hierarchy section)

**Documented hierarchy:**
```
LoggingEventBuilderWrapper<L extends LoggingEventBuilderWrapper<L>>
  └─ abstract self() → subclasses return (L) this

DiaLogger<L extends LoggingEventBuilderWrapper<L>>
  ├─ abstract initBuilder(LoggingEventBuilder) → L
  ├─ abstract noOpWrapper() → L
  ├─ abstract contextStart(L) → void
  └─ abstract contextEnd() → void
```

**Actual code:**
- [`LoggingEventBuilderWrapperBase`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java:38) does **not** have a `self()` method
- [`DiaLoggerBase`](core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:8) does **not** have `contextStart()` or `contextEnd()` methods
- The generic pattern is simpler: `DiaLoggerBase<L extends LoggingEventBuilderWrapperBase>` with `initBuilder()` and `noOpWrapper()`

**Impact:** The documented type hierarchy does not match the actual implementation.

---

## 16. MEDIUM: `roadmap.md` Lists `TraceId` in Core Module Contents (archived)

**File:** [`roadmap.md`](roadmap.md) (module table)

**Documented:**
```
| `core`    | `dia-log-core`    | `DiaLogger.java`, `LoggingEventBuilderWrapper.java`, `JavaStackSanitizer.java`, `Wyhash64.java`             |
```

**Issue:** `TraceId.java` is not listed in the core module contents table, but it exists in the core module.

---

## 17. MEDIUM: `README.md` Example Output Doesn't Match Code

**README.md** shows:
```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User {userId} performed {action}","kv":{"userId":42,"action":"login"}}
```

**Actual `JsonLogWriter` output:**
- `msg` would contain the **formatted** message (placeholders replaced), not the template with `{userId}` literal
- `kv` does not exist as a nested object — keys are flattened to top level
- No `kv` object is created

**Impact:** The README example is misleading about both the message format and the key-value structure.

---

## 18. MEDIUM: `README.md` Module Table Missing `TraceId`

**File:** [`README.md`](README.md:9)

**Documented:**
```
| [`core`](core/) | `dia-log-core` | `DiaLogger`, `LoggingEventBuilderWrapper`, `JavaStackSanitizer`, `Wyhash64` |
```

**Issue:** `TraceId` is not listed in the core module description.

---

## 19. LOW: `JsonLogWriter` `addKey()` Doesn't Handle All Types Optimally

**File:** [`JsonLogWriter.java:186-200`](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java:186-200)

```java
protected void addKey(JsonGenerator gen, String key, Object value) throws IOException {
    if (value == null) return;
    gen.writeName(key);
    switch (value) {
        case String s -> gen.writeString(s);
        case Long l -> gen.writeNumber(l);
        case Integer i -> gen.writeNumber(i);
        case Double d -> gen.writeNumber(d);
        case Number n -> gen.writeNumber(n.toString());
        case Boolean b -> gen.writeBoolean(b);
        default -> MAPPER.writeValue(gen, value);
    }
}
```

**Issue:** For `Number` types (Integer, Long, Double), the code uses type-specific methods which is good. But for the `default` case, it falls back to `MAPPER.writeValue()` which is slower than type-specific methods. The analysis report notes this but it's a minor performance issue.

---

## 20. LOW: `DiaLoggerBase` Void Methods Don't Use Wrapper

**File:** [`DiaLoggerBase.java:128-166`](core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java:128-166)

**Issue:** The `debug(Marker, String, ...)` and similar void methods call `_contextStart(delegate.atDebug())` directly without checking if the level is enabled. This means they will attempt to log even when the level is disabled, unlike the `atXxx()` methods which return `noOpWrapper()` when disabled.

**Example:**
```java
public void debug(Marker arg0, String arg1, Object arg2, Object arg3) {
    _contextStart(delegate.atDebug()).addMarker(arg0).log(arg1, arg2, arg3);
}
```

**Impact:** These methods bypass the level-checking optimization that `atXxx()` methods provide.

---

## 21. LOW: `LoggingEventBuilderWrapperBase` Javadoc Claims `AutoCloseable` (Repeated)

Already covered in section 2.2, but worth noting again as it appears in both the class Javadoc and ADR-002.

---

## 22. LOW: `roadmap.md` References `DefaultDiaLogger` as Unimplemented (archived)

**File:** [`roadmap.md`](roadmap.md) (Phase 1, item 1.1)

**Documented:**
```
- [ ] **1.1 Concrete DiaLogger subclass** — e.g. `DefaultDiaLogger<L>` implementing `contextStart()`/`contextEnd()`/`initBuilder()`/`noOpWrapper()` for common use cases
```

**Actual:** [`DiaLogger.java`](core/src/main/java/hr/hrg/dialog/core/DiaLogger.java:6) is a concrete subclass that implements `initBuilder()` and `noOpWrapper()`. There is no `contextStart()`/`contextEnd()` in the actual API.

---

## 23. LOW: `roadmap.md` Phase 1 Items Marked Complete But Not Actually Complete (archived)

**File:** [`roadmap.md`](roadmap.md) (Phase 1 checklist)

**Documented as complete:**
- `DiaLogger` — "Supports `contextStart(L)`/`contextEnd()` lifecycle" — these methods don't exist
- `LoggingEventBuilderWrapper` — "supports `AutoCloseable`" — it doesn't implement `AutoCloseable`
- `JavaStackSanitizer` — "Provides `getSanitizedFrames()` (individual frames) and `getFingerprint()` (pipe-delimited)" — the actual API uses `addFromTrace()` and `fingerprint()`, not these method names

---

## 24. LOW: `doc/adr/006-javastacksanitizer.md` References Non-Existent Method

**File:** [`doc/adr/006-javastacksanitizer.md`](doc/adr/006-javastacksanitizer.md:38)

**References:**
- [`JavaStackSanitizer.fingerprint()`](doc/adr/006-javastacksanitizer.md:38) — this method exists
- [`Wyhash64.Streaming`](doc/adr/006-javastacksanitizer.md:36) at line 161 — the `Streaming` class exists but the line reference may be off

**Issue:** The line number references in ADRs may be outdated if files have been modified since the ADRs were written.

---

## 25. LOW: `doc/adr/004-key-value-pairs-vs-mdc.md` References Wrong Line Numbers

**File:** [`doc/adr/004-key-value-pairs-vs-mdc.md`](doc/adr/004-key-value-pairs-vs-mdc.md:39)

**References:**
- [`JsonLogWriter.writeJsonEvent()`](doc/adr/004-key-value-pairs-vs-mdc.md:39) at line 162 — the actual method starts at line 65
- [`LoggingEventBuilderWrapperBase.addKeyValue()`](doc/adr/004-key-value-pairs-vs-mdc.md:50) at line 110 — the actual method is at line 103

**Issue:** Line number references in ADRs are outdated.

---

## 26. RESOLVED: `doc/adr/003-automatic-mdc-cleanup.md` References Non-Existent Method

**File:** [`doc/adr/003-automatic-mdc-cleanup.md`](doc/adr/003-automatic-mdc-cleanup.md)

**Resolution:** ADR-003 has been marked **Not accepted**. The referenced `closeContext()` method references have been removed.

---

## 27. LOW: `doc/adr/007-stackwhentraceenabled.md` References Non-Existent Method

**File:** [`doc/adr/007-stackwhentraceenabled.md`](doc/adr/007-stackwhentraceenabled.md:41)

**References:**
- [`LoggingEventBuilderWrapperBase.maybeAttachTraceCause()`](doc/adr/007-stackwhentraceenabled.md:41) at line 203 — the actual method is `beforeLog()` at line 183

---

## 28. LOW: `doc/adr/011-noop-wrapper-pattern.md` Incorrect Behavior Description

**File:** [`doc/adr/011-noop-wrapper-pattern.md`](doc/adr/011-noop-wrapper-pattern.md:36)

**Claims:**
- "The no-op wrapper holds a `null` logger, so `maybeAttachTraceCause()` is skipped — but the wrapper is still returned so the chain can continue"

**Actual:**
- [`LoggingEventBuilderWrapperNoop`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperNoop.java:12) passes `null` as the logger to the super constructor
- [`LoggingEventBuilderWrapperBase.beforeLog()`](core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java:183-188) checks `logger != null` before attaching trace cause
- So the description is correct, but the method name `maybeAttachTraceCause()` is wrong — it's actually `beforeLog()`

---

## 29. LOW: `cookbook/missing-keys-warn.md` is Entirely About Removed Feature — ✅ RESOLVED

**File:** [`cookbook/missing-keys-warn.md`](cookbook/missing-keys-warn.md:1)

**Issue:** The cookbook previously documented the removed `ConsoleAppenderDev` / missing-key
warning feature.

**Resolution (2026-08-18):** The cookbook no longer references `ConsoleAppenderDev`; it now
explicitly states that `warnOnMissingKeys` is **not implemented** and that unresolvable
`{key}` placeholders stay literal — aligned with the current code.

---

## 30. LOW: `cookbook/stackWhenTrace.md` References Non-Existent `ConsoleAppenderJson`

**File:** [`cookbook/stackWhenTrace.md`](cookbook/stackWhenTrace.md:56)

**References:**
- `ConsoleAppenderJson` — this appender does not exist. The actual encoder is `CustomJsonEncoder`.

---

## 31. LOW: `doc/traceid.md` Title is "span id" Not "TraceId"

**File:** [`doc/traceid.md`](doc/traceid.md:1)

**Issue:** The file title/heading is `# span id` but the content is about both TraceId and SpanId. This is misleading.

---

## 32. LOW: `doc/stack.trace.sanitizer.md` Mentions `ThrowableProxyConverter`

**File:** [`doc/stack.trace.sanitizer.md`](doc/stack.trace.sanitizer.md:3)

**Issue:** The document mentions `ThrowableProxyConverter` which is a Logback class, not a Dia-Log class. This is confusing in the context of Dia-Log documentation.

---

## 33. LOW: `doc/zero-allocation-wyhash.md` Describes Unimplemented Features — ✅ RESOLVED

**File:** ~~`doc/zero-allocation-wyhash.md`~~ — **removed 2026-08-18**

**Describes:**
- `MemorySegment.ofArray(char[])` for zero-copy char[] hashing
- `ValueLayout.JAVA_LONG_UNALIGNED` for unaligned reads

**Actual code:**
- [`Wyhash64.java`](core/src/main/java/hr/hrg/dialog/core/Wyhash64.java) uses `StringByteExtractor` with `VarHandle` for String access, not `MemorySegment`
- The `Streaming` class uses a `byte[]` buffer, not `MemorySegment`

**Issue:** The documentation described a different implementation approach than what was actually built.

**Resolution:** The obsolete document was removed; the current implementation is documented
in [`doc/wyhash64-zero-allocation.md`](doc/wyhash64-zero-allocation.md) (String byte-order
probing, manual char[] packing, strided Latin-1 slices, allocation-free `finalHash()`).

---

## 34. LOW: `plans/align-java-stack-trace-writer.md` Describes Work That May Be Complete

**File:** [`plans/align-java-stack-trace-writer.md`](plans/align-java-stack-trace-writer.md)

**Issue:** This plan describes aligning `JavaStackTraceWriter` with `JavaStackSanitizer`. Looking at the actual code:
- `JavaStackTraceWriter` has commented-out filter and fallback code
- The delimiter logic (newline before each frame) matches `JavaStackSanitizer`
- Constants are used instead of string literals

It appears the alignment work has been done, but the plan file wasn't updated to reflect completion.

---

## 35. LOW: `plans/dia-log-improvements.md` References Removed/Non-Existent Files

**File:** [`plans/dia-log-improvements.md`](plans/dia-log-improvements.md:187-189)

**Lists for Javadoc:**
- `ConsoleAppenderJson.java` — doesn't exist
- `RollingFileAppenderJson.java` — doesn't exist
- `ConsoleAppenderDev.java` — doesn't exist (removed per ADR-009)

---

## 36. LOW: `plans/analysis-report.md` and `plans/dia-log-improvements.md` Overlap and Conflict

**Issue:** Both documents exist and cover similar ground. The analysis report (dated 2026-07-30) is more recent and identifies issues not in the improvement plan. However, some items in the analysis report describe bugs that may have already been fixed (e.g., `atLevel()` bug, `String.format()` in `TraceId`).

---

## Summary of Findings by Severity

| Severity | Count | Key Areas |
|----------|-------|-----------|
| CRITICAL | 4 | JSON schema mismatches (hash, stack, msgTpl, ctx, kv) — ADR-003 resolved |
| HIGH | 4 | Duplicate sanitizer, dead code, incomplete encoder, appender bug, dependency mismatches |
| MEDIUM | 15 | Java version, line endings, generic types, non-existent file references, outdated line numbers |
| LOW | 12 | Empty files, misleading titles, unimplemented feature docs, overlapping plans |

---

## Recommended Actions

1. **Fix JSON output schema** — Implement `err.hash`, fix `err.stack` to be an array, decide on `kv` vs flattened keys, decide on `ctx` vs flattened MDC
2. **Update or remove ADR-003** — DONE: ADR-003 has been marked **Not accepted** to reflect that the automatic MDC cleanup feature was never implemented
3. **Fix `AutoCloseable` Javadoc** — Either implement `AutoCloseable` or remove the try-with-resources example
4. **Consolidate `JavaStackSanitizerLogback`** — Remove duplication, use core `JavaStackSanitizer` directly
5. **Fix dependency versions** — Centralize JUnit, logback, and jackson versions in root `dependencyManagement`
6. **Fix `ZeroCopyDirectAppender.setOutputStream()` bug** — Assign `targetOutputStream` before using it
7. **Remove dead code** — `SegmentedJsonStringWriter`, `StreamDirectJacksonEncoder`, or complete them
8. **Update all line number references** in ADRs and plans
9. **Update `README.md`** — Fix Java version, module contents, and JSON output example
10. **Remove or update `cookbook/missing-keys-warn.md`** — ✅ DONE (2026-08-18): the cookbook no longer references the removed `ConsoleAppenderDev`; it documents that `warnOnMissingKeys` is not implemented.
11. **Keep `plans/roadmap.md` archived** — the original roadmap is superseded; do not treat it as current (the live gap list is `plans/analysis-report.md`)
12. **Fix `CustomJsonEncoder` newline** — Use `\n` instead of `System.lineSeparator()`
