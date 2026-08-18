# Dia-Log Usage Guide

Practical usage patterns for `dia-log-core` + `dia-log-logback`. The example module
(`example/`) contains a runnable `Main` demonstrating all of this.

## Requirements

- **Java 25+** (enforced by the Maven Enforcer)
- Maven Central artifacts:
  - `hr.hrg.dialog:dia-log-core`
  - `hr.hrg.dialog:dia-log-logback` (brings `logback-classic`, Jackson 3, `xz`)
- Optional, for the zero-allocation fast paths:
  `--add-opens java.base/java.lang=ALL-UNNAMED` (see `doc/wyhash64-zero-allocation.md`).

## 1. Basic setup

Create a logger with `DiaLogger` (a full SLF4J `Logger`):

```java
import hr.hrg.dialog.core.DiaLogger;
import org.slf4j.LoggerFactory;

DiaLogger log = new DiaLogger(LoggerFactory.getLogger("com.example.App"));
```

Configure logback to write JSON. Console:

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
</appender>
<root level="INFO"><appender-ref ref="JSON"/></root>
```

No `<encoder>` is needed — `JsonAppender.writeOut()` writes JSON directly and installs a
no-op encoder itself when none is configured. Rolling file with XZ-compressed archives
(`JsonAppenderRolling`):

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
    <file>logs/app.jsonl</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.jsonl.xz</fileNamePattern>
        <maxFileSize>10MB</maxFileSize>
        <maxHistory>30</maxHistory>
        <totalSizeCap>2GB</totalSizeCap>
    </rollingPolicy>
</appender>
```

The active `<file>` must **not** end in `.xz` — only the archived `fileNamePattern` uses it.

## 2. Structured key/value pairs

`kv()` is statement-scoped (applies only to that log line), unlike MDC:

```java
log.atInfo()
   .kv("userId", 42)
   .kv("action", "login")
   .log("User {userId} performed {action}");
```

- Every KV pair is written as its **own top-level JSON field**.
- `{key}` placeholders in the message are **kept literal** — the message is not
  interpolated at log time; `jlx` (below) can expand them when reading.
- Fluent chain: `.kv(...)` returns the wrapper, so pairs can be chained.

Varargs helper for many pairs:

```java
DiaLoggerBase.addKeyValues(log.atInfo(), "a", 1, "b", 2, "c", 3).log("...");
// throws IllegalArgumentException on an odd number of arguments
```

## 3. Contextual prefix

`prependPrefix()` attaches a `"prefix"` key/value to every event from that logger.
Repeated calls **prepend** (each new prefix goes in front):

```java
log.prependPrefix("app");      // prefix = "app"
log.prependPrefix("web");      // prefix = "webapp"
```

## 4. Conditional stack traces

Attach the caller's stack as a `Throwable` **only when TRACE is enabled** — one log line,
no duplicate:

```java
log.atDebug().stackWhenTraceEnabled()
   .kv("state", "PAID")
   .log("Change state to {state}");
```

When TRACE is off, the event is a normal message with no stack.

## 5. Exceptions

Pass the throwable and the event gets `errClass`, `errMessage`, `stack` (sanitized
frames), and `errHash` (a deterministic fingerprint for grouping/dedup):

```java
try {
    risky();
} catch (Exception e) {
    log.error("Operation failed: {}", e.getMessage(), e);
}
// or fluently:
log.atError().kv("orderId", 12345).setCause(e).log("Failed to process order");
```

`errHash` is stable across line-number changes, so the same failure in different places
fingerprints identically. Exclude noisy frames with `<stackTraceFilter>` on the appender
(a fully-qualified class implementing `Predicate<String>` with a public no-arg ctor).

## 6. MDC

`MDC.put(...)` values are written as flat top-level fields (reserved names such as
`ts`, `level`, `msg`, `errClass`, `errHash`, … are skipped):

```java
MDC.put("requestId", "req-42");
MDC.put("tenant", "acme");
log.info("processing request"); // {"requestId":"req-42","tenant":"acme",...}
MDC.clear();
```

Prefer `kv()` for statement-scoped values; keep MDC for request/thread-wide context.

## 7. Disabled levels — no-op wrappers

When a level is disabled, `atDebug()`/`atInfo()`/… return a **no-op wrapper**: the fluent
chain still type-checks and compiles, but `log()` does nothing and nothing is allocated
for the event.

## 8. Missing keys

An unresolvable `{key}` placeholder stays **literal** in the message in every writer. For
development, the writer overload `JsonLogWriterDev` (via `JsonAppenderDev` /
`JsonAppenderRollingDev`) additionally emits a `"missingKeys":["ip"]` field — no boolean
config, the class is the switch; `{}` positionals and `{{escaped}}` braces are ignored and
`null` values count as present. See `cookbook/missing-keys-warn.md`.

## 9. Reading the logs

Output is JSON Lines. [`jlx`](https://github.com/hrgdavor/zig-jlx) reads/filters/expands
them (e.g. `message_expand = curly` replaces `{key}` placeholders with the field values).

## 10. JSON schema (flat)

```json
{"ts":1787000000000,"level":"INFO","logger":"com.example.App","thread":"main",
 "msg":"User 42 logged in","userId":42,"requestId":"req-42",
 "errClass":"java.lang.RuntimeException","errMessage":"boom",
 "stack":"java.lang.RuntimeException\\ncom.example.App.main","errHash":1234567890}
```

- `ts` — epoch millis; `level`, `logger`, `thread`, `msg` — standard.
- KV pairs and MDC keys are flat top-level fields.
- Exceptions add `errClass`, `errMessage`, `stack` (JSON-escaped string), `errHash`.

## Related

- `cookbook/additional.error-only.log.md` — separate ERROR-only file via `ThresholdFilter`.
- `cookbook/stackWhenTrace.md` — deep dive on `stackWhenTraceEnabled()`.
- `example/` — runnable end-to-end example (`Main.java`, `logback.xml`, `jlx.conf`).
