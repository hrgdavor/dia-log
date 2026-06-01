# Dia-Log

A diagnostic logging library built on SLF4J 2.0 for Java 21. Provides structured JSON logging, contextual key-value pairs, automatic MDC cleanup, and deterministic stack trace deduplication.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| [`core`](core/) | `dia-log-core` | `DiaLogger`, `LoggingEventBuilderWrapper`, `JavaStackSanitizer`, `Wyhash64` |
| [`logback`](logback/) | `dia-log-logback` | `ConsoleAppenderJson`, `RollingFileAppenderJson`, `ConsoleAppenderDev`, `JsonLogWriter` |
| [`example`](example/) | `dia-log-example` | Runnable demo with `logback.xml` |

## Quick Start

Add to your `logback.xml`:

```xml
<configuration scan="true">
    <!-- JSON console output for production -->
    <appender name="JSON" class="hr.hrg.dialog.logback.ConsoleAppenderJson">
        <includeMDC>true</includeMDC>
        <includeKeys>true</includeKeys>
    </appender>

    <!-- Dev console with placeholder expansion -->
    <appender name="DEV" class="hr.hrg.dialog.logback.ConsoleAppenderDev">
        <expandPlaceholders>true</expandPlaceholders>
        <warnOnMissingKeys>true</warnOnMissingKeys>
    </appender>

    <root level="DEBUG">
        <appender-ref ref="JSON" />
        <appender-ref ref="DEV" />
    </root>
</configuration>
```

Use in code:

```java
// Structured logging with key-value pairs
log.atInfo().kv("userId", id).kv("action", "login").log("User {userId} performed {action}");

// Conditional stack trace — clean message in production, call stack when TRACE enabled
log.atDebug().stackWhenTrace().kv("state", state).log("Change state to {state}");
```

JSON output:

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User {userId} performed {action}","kv":{"userId":42,"action":"login"}}
```

## Features

- **Structured key-value logging** — `kv()` shorthand with automatic MDC cleanup
- **JSON console/file appenders** — production-ready JSON Lines output
- **Dev console appender** — `{name}` placeholder expansion with missing-key detection
- **`stackWhenTrace()`** — conditional call-stack visibility (clean message normally, throwable when TRACE enabled)
- **Deterministic stack traces** — `JavaStackSanitizer` normalizes frames for deduplication
- **Hash-based dedup** — Wyhash64 fingerprint in `err.hash` for fast grouping in Elasticsearch/Loki
- **Generic builder pattern** — `LoggingEventBuilderWrapper<L>` with `self()` for type-safe subclass chaining

## Cookbook

Practical guides for common logging patterns:

- [**Error-Only Log File**](cookbook/additional.error-only.log.md) — Separate ERROR logs into their own file with ThresholdFilter, zgrep examples for JSON and text
- [**Stack When Trace**](cookbook/stackWhenTrace.md) — Conditional call-stack visibility using `stackWhenTrace()`, output examples in JSON and plain text
- [**Missing Keys Warning**](cookbook/missing-keys-warn.md) — Detect missing log keys at runtime with `warnOnMissingKeys`

## Build

```bash
mvn clean install
```

## Requirements

- Java 21+
- SLF4J 2.0.17
- Logback 1.5+ (for the logback module)