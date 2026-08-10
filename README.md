# Dia-Log

A diagnostic logging library built on SLF4J 2.0 for Java 21. Provides structured JSON logging, contextual key-value pairs, and deterministic stack trace deduplication.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| [`core`](core/) | `dia-log-core` | `DiaLogger`, `LoggingEventBuilderWrapper`, `JavaStackSanitizer`, `Wyhash64` |
| [`logback`](logback/) | `dia-log-logback` | `CustomJsonEncoder`, `JsonLogWriter` |
| [`example`](example/) | `dia-log-example` | Runnable demo with `logback.xml` |

## Quick Start

Add to your `logback.xml`:

```xml
<configuration scan="true">
    <!-- JSON console output with CustomJsonEncoder -->
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="hr.hrg.dialog.logback.CustomJsonEncoder">
            <includeMDC>true</includeMDC>
            <includeKeys>true</includeKeys>
        </encoder>
    </appender>

    <root level="DEBUG">
        <appender-ref ref="JSON" />
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

- **Structured key-value logging** — `kv()` shorthand for statement-scoped key-value pairs
- **JSON encoder** — `CustomJsonEncoder` for use with standard Logback appenders (`ConsoleAppender`, `RollingFileAppender`)
- **`stackWhenTrace()`** — conditional call-stack visibility (clean message normally, throwable when TRACE enabled)
- **Deterministic stack traces** — `JavaStackSanitizer` normalizes frames for deduplication
- **Hash-based dedup** — Wyhash64 fingerprint in `err.hash` for fast grouping in Elasticsearch/Loki
- **Generic builder pattern** — `LoggingEventBuilderWrapper<L>` with `self()` for type-safe subclass chaining

## Cookbook

Practical guides for common logging patterns:

- [**Error-Only Log File**](cookbook/additional.error-only.log.md) — Separate ERROR logs into their own file with ThresholdFilter, zgrep examples for JSON and text
- [**Stack When Trace**](cookbook/stackWhenTrace.md) — Conditional call-stack visibility using `stackWhenTrace()`, output examples in JSON and plain text
- [**Missing Keys Warning**](cookbook/missing-keys-warn.md) — Detect missing log keys at runtime with `warnOnMissingKeys`

## Additional Documentation

- [Stack Trace Sanitizer and Derivatives](doc/java-stack-trace-sanitizer-and-derivatives.md) — Canonical sanitizer behavior and detailed mapping of `JavaStackTraceWriter` (JavaStackWriter), `JavaStackSanitizerLogback`, and `JavaStackWriterLogback`.

## Build

```bash
mvn clean install
```

# Using optimized JSON appenders

To have access to underlying OuputStream and circumvent jackson for optimized stack trace writing, Appenders had to be made,
because encoders generate byte arrays, and that is not in-line with goal of lowering number of allocations.

```xml
<configuration>

    <!-- Console Appender -->
    <appender name="CONSOLE" class="hr.hrg.dialog.logback.DirectJsonAppender$Console">
        <target>System.out</target>
    </appender>

    <!-- Rolling File Appender -->
    <appender name="ROLLING" class="hr.hrg.dialog.logback.DirectJsonAppender$Rolling">
        <file>logs/app.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/app-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
                <maxFileSize>100MB</maxFileSize>
            </timeBasedFileNamingAndTriggeringPolicy>
            <maxHistory>14</maxHistory>
        </rollingPolicy>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
        <appender-ref ref="ROLLING" />
    </root>
</configuration>
```

## Requirements

- Java 21+
- SLF4J 2.0.17
- Logback 1.5+ (for the logback module)