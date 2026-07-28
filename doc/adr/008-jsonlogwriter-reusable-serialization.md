# 008: JsonLogWriter as reusable serialization component

* **Status:** Accepted
* **Date:** 2026-07-26

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

### Appender integration
Both [`ConsoleAppenderJson`](../logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderJson.java) and [`RollingFileAppenderJson`](../logback/src/main/java/hr/hrg/dialog/logback/RollingFileAppenderJson.java):
- Hold a `JsonLogWriter` instance
- Delegate all configuration properties to the writer
- Call `jsonWriter.writeJsonEvent(event, out)` in their `writeOut()` methods
- Add a newline and flush after each event

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

* **Positive:** Single source of truth for JSON serialization logic; consistent output across all appenders; easy to add new JSON fields or change formatting in one place; appenders remain thin — they only handle I/O (output stream, locking, flushing); `JsonLogWriter` can be reused by future appender types (e.g., async appender, network appender).
* **Negative:** `JsonLogWriter` extends `ContextAwareBase`, coupling it to Logback's context model; configuration changes at runtime require the appender to propagate them to the writer; the writer holds a static `ObjectMapper` and `JsonFactory`, which are thread-safe but not configurable per-instance.

## References

- [`JsonLogWriter.java`](../logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [`ConsoleAppenderJson.java`](../logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderJson.java)
- [`RollingFileAppenderJson.java`](../logback/src/main/java/hr/hrg/dialog/logback/RollingFileAppenderJson.java)
