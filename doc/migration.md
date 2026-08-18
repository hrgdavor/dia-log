# Migrating from Plain SLF4J to Dia-Log

Step-by-step guide to adopt Dia-Log in an existing SLF4J application. Dia-Log wraps the
SLF4J 2.0 API, so you can migrate incrementally — one logger or one module at a time.

## Step 1 — Add dependencies

```xml
<dependency>
    <groupId>hr.hrg.dialog</groupId>
    <artifactId>dia-log-core</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>hr.hrg.dialog</groupId>
    <artifactId>dia-log-logback</artifactId>
    <version>1.0.0</version>
</dependency>
```

`dia-log-logback` brings `logback-classic`, Jackson 3 (`tools.jackson`), and `xz`.
Requires **Java 25+**.

## Step 2 — Swap the logger type

`DiaLogger` implements the full SLF4J `Logger` interface, so every existing call site
compiles unchanged:

```java
// before
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
private static final Logger log = LoggerFactory.getLogger(MyService.class);

// after
import hr.hrg.dialog.core.DiaLogger;
import org.slf4j.LoggerFactory;
private static final DiaLogger log = new DiaLogger(LoggerFactory.getLogger(MyService.class));
```

Existing `log.debug(...)`, `log.info("User {}", id)`, `log.error("boom", e)` calls keep
working — they are delegated to the underlying SLF4J logger. Do this incrementally:
plain `Logger` and `DiaLogger` can coexist.

## Step 3 — Replace statement-scoped `MDC` with `kv()`

MDC is thread-wide and needs manual cleanup; `kv()` is scoped to one log statement and is
the Dia-Log replacement for event-local data:

```java
// before
MDC.put("state", state);
log.info("Change state to {}", state);
MDC.remove("state");

// after
log.atInfo().kv("state", state).log("Change state to {state}");
```

The value is written as its own top-level JSON field (`"state": ...`), and the `{state}`
placeholder is kept literal in the message (expand with `jlx`, see `doc/usage.md`).
Keep MDC for request/thread-wide context (traceId, tenant) — those are written as flat
fields too.

## Step 4 — Switch to the fluent builder

`atXxx()` returns a wrapper that supports `kv()`, `setCause()`, `addMarker()`,
`stackWhenTraceEnabled()`, and `log(...)` overloads:

```java
// before
log.info("User {} logged in", userId);

// after — structured
log.atInfo().kv("userId", userId).kv("action", "login")
   .log("User {userId} logged in");

// before
log.debug("Change state to {}", state);

// after — stack only when TRACE is enabled
log.atDebug().stackWhenTraceEnabled().kv("state", state)
   .log("Change state to {state}");
```

## Step 5 — Configure the JSON output

Point logback at `JsonAppender` (console) or `JsonAppenderRolling` (file + XZ rotation):

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
<root level="INFO"><appender-ref ref="JSON"/></root>
```

No `<encoder>` is required — the appender writes JSON directly and installs a no-op
encoder itself when none is configured.

Each log event becomes one JSON line (`ts`, `level`, `logger`, `thread`, `msg`, flat KV
and MDC fields, and for exceptions `errClass`, `errMessage`, `stack`, `errHash`).

## Step 6 — What changes in the output

| Concern | Plain SLF4J | Dia-Log |
|---|---|---|
| Format | configured pattern | JSON Lines (one object per event) |
| Key/value data | `MDC` (thread-wide, manual cleanup) | `kv()` (statement-scoped, automatic) |
| Placeholders `{}` | interpolated by SLF4J | still interpolated |
| `{name}` placeholders | n/a | kept literal in `msg`; field carries the value |
| Stack traces | full `Throwable` text | sanitized `stack` string + deterministic `errHash` |
| Disabled levels | formatting skipped at the source | no-op wrapper — fluent chain still compiles, nothing logged |

## Step 7 — Verify

```bash
mvn clean verify
```

Run the app, tail the JSON file, and confirm each line parses (e.g. `jq .` or
[`jlx`](https://github.com/hrgdavor/zig-jlx)). The example module
(`example/src/main/java/hr/hrg/dialog/example/Main.java`) is a runnable reference.

## Notes

- **Rolling back is trivial** — `DiaLogger` delegates to a normal SLF4J logger; reverting
  a call site to `LoggerFactory.getLogger(...)` changes nothing else.
- `addKeyValues(log.atInfo(), "a", 1, "b", 2)` is the varargs form of multiple `kv()`s
  (throws `IllegalArgumentException` on an odd argument count).
- `prependPrefix("app")` stamps a `"prefix"` field on every event from that logger.
- For the zero-allocation fast paths, run with
  `--add-opens java.base/java.lang=ALL-UNNAMED` (see `doc/wyhash64-zero-allocation.md`).
