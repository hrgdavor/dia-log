# Dia-Log

A diagnostic logging library for SLF4J 2.x and Java 25+. It provides structured JSON logging, contextual key/value pairs, and deterministic stack-trace hashing for downstream aggregation.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| [`core`](core/) | `dia-log-core` | `DiaLogger`, `LoggingEventBuilderWrapperBase`, `LoggingEventBuilderWrapperNoop`, `JavaStackSanitizer`, `Wyhash64` |
| [`logback`](logback/) | `dia-log-logback` | `JsonAppender`, `JsonAppenderRolling`, `JsonLogWriter`, `JsonLogWriterClassic` |
| [`example`](example/) | `dia-log-example` | Runnable demo with a sample Logback configuration |

## Quick Start

Use the builder API directly:

```java
// Structured logging with key/value pairs
log.atInfo().kv("userId", id).kv("action", "login")
    .log("User {userId} performed {action}");

// Conditional stack trace — clean message in production, stack when TRACE is enabled
log.atDebug().stackWhenTraceEnabled().kv("state", state)
    .log("Change state to {state}");
```

The current JSON writer emits flat top-level fields such as `ts`, `level`, `logger`, `thread`, `msg`, and your key/value pairs. When an exception is present, the writer emits `errClass`, `errMessage`, `stack`, and `errHash`.

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User {userId} performed {action}","userId":42,"action":"login"}
```

## Features

- **Structured key/value logging** — `kv()` is a shorthand for statement-scoped key/value pairs.
- **Logback integration** — the logback module exposes `JsonAppender` and `JsonAppenderRolling`, both backed by `JsonLogWriter`.
- **Conditional stack visibility** — `stackWhenTraceEnabled()` attaches a synthetic throwable only when TRACE is enabled.
- **Deterministic stack traces** — `JavaStackSanitizer` normalizes frames and produces a stable fingerprint for `errHash`.
- **Generic builder pattern** — `LoggingEventBuilderWrapperBase` keeps fluent chaining intact for subclasses and no-op wrappers.

## Cookbook

Practical guides for common logging patterns:

- [**Error-Only Log File**](cookbook/additional.error-only.log.md) — Separate ERROR logs into their own file with a `ThresholdFilter` and rolling policy.
- [**Stack When Trace**](cookbook/stackWhenTrace.md) — Conditional call-stack visibility using `stackWhenTraceEnabled()` and the current JSON field layout.
- [**Missing Keys Warning**](cookbook/missing-keys-warn.md) — Notes the current state of missing-key detection in this checkout.

## Additional Documentation

- [Stack Trace Sanitizer and Derivatives](doc/java-stack-trace-sanitizer-and-derivatives.md) — Canonical sanitizer behavior and the relationship between `JavaStackTraceWriter`, `JavaStackSanitizerLogback`, and `JavaStackWriterLogback`.

## Example Logback Configuration

The current logback module is configured with standard Logback appenders and the JSON writer that ships with the library. A simple example looks like this:

```xml
<configuration scan="true">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>%msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON" />
    </root>
</configuration>
```

If you want to use the library's custom JSON writer directly, the repository also contains `JsonAppender` and `JsonAppenderRolling`, which write JSON events using `JsonLogWriter`.

## Build

```bash
mvn clean install
```

## Requirements

- Java 25+
- SLF4J 2.0.18
- Logback 1.5.38 (for the logback module)