# 008: JsonLogWriter as reusable serialization component

* **Status:** Accepted (updated 2026-07-30)
* **Date:** 2026-07-26
* **Implementation Status:** Implemented

## Context

The project needs to produce JSON log output from multiple appender types:
- Console output (development and production)
- Rolling file output (persistent storage)

Initially, JSON serialization logic would be duplicated across each appender class, leading to:
- Code duplication for field ordering, type handling, and error formatting
- Inconsistent JSON output between appenders
- Difficult maintenance when adding new JSON fields or changing serialization rules

## Options Considered

1. **Duplicate JSON serialization in each appender:** Simple initially, but leads to code duplication and inconsistency.
2. **Extract JSON serialization into a reusable component:** Single source of truth for JSON format, shared by all appenders.

## Decision

Extract JSON serialization into a reusable [`JsonLogWriter`](../logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java) component that:
- Is independent of any specific appender type
- Holds all JSON-related configuration (`includeMDC`, `includeKeys`, `prettyPrint`, `customFields`, `maxStackFrames`)
- Provides a single `writeJsonEvent(ILoggingEvent, OutputStream)` method
- Can be shared by any appender that needs JSON output

### Encoder integration
[`CustomJsonEncoder`](../logback/src/main/java/hr/hrg/dialog/logback/CustomJsonEncoder.java) holds a `JsonLogWriter` instance and delegates all configuration properties to the writer. The encoder is then used with standard Logback appenders:

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="hr.hrg.dialog.logback.CustomJsonEncoder">
        <includeMDC>true</includeMDC>
        <includeKeys>true</includeKeys>
        <customFields>{"env":"prod","version":"1.0"}</customFields>
    </encoder>
</appender>
```

This approach follows Logback best practices — use built-in appenders with custom encoders rather than subclassing appenders.

### JSON output format
Each event produces a single JSON object with fields:
- `ts`: Epoch millis timestamp
- `level`: Log level string
- `logger`: Logger name
- `thread`: Thread name
- `msg`: Formatted message
- `kv`: Structured key-value pairs (if `includeKeys` is true)
- MDC keys (if `includeMDC` is true, with kv taking priority)
- `err`: Exception info with `class`, `msg`, `hash`, and `cause` (if present)
- `source`: Caller data (if `includeSource` is true and no exception)
- Custom static fields from `customFields` configuration

## Consequences

* **Positive:** Single source of truth for JSON serialization logic; consistent output across all appenders; easy to add new JSON fields or change formatting in one place; encoders remain thin — they only bridge the appender to the writer; `JsonLogWriter` can be reused by future appender types (e.g., async appender, network appender).
* **Negative:** `JsonLogWriter` extends `ContextAwareBase`, coupling it to Logback's context model; configuration changes at runtime require the encoder to propagate them to the writer; the writer holds a static `ObjectMapper` and `JsonFactory`, which are thread-safe but not configurable per-instance.

## References

- [`JsonLogWriter.java`](../logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [`CustomJsonEncoder.java`](../logback/src/main/java/hr/hrg/dialog/logback/CustomJsonEncoder.java)
