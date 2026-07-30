# 009: ConsoleAppenderDev for development

* **Status:** Superseded (removed 2026-07-30)
* **Date:** 2026-07-26

## Context

During development, JSON console output is hard to read. Developers need human-readable log output with:
- Placeholder expansion: `{userId}` replaced with actual values
- Traditional log format: timestamp, thread, level, logger, message
- Missing key detection: warn when a `{name}` placeholder has no matching key-value pair

Standard Logback `PatternLayout` cannot:
- Expand `{name}` placeholders from SLF4J 2.0 key-value pairs (it only supports `{}` positional arguments)
- Detect and report missing keys at runtime
- Produce the exact format developers expect from traditional logging frameworks

## Decision

Create `ConsoleAppenderDev` as a development-only console appender that:

### Placeholder expansion
- Parses `{name}` placeholders in the formatted message
- Looks up matching values from the event's key-value pairs
- Substitutes the value into the message for human-readable output
- Leaves numeric placeholders (`{0}`, `{1}`) untouched for SLF4J compatibility

### Missing key detection
- Tracks which `{name}` placeholders were not found in the key-value pairs
- When `warnOnMissingKeys=true`, appends `⚠ MISSING KEYS: key1, key2` to the output
- Attaches a `Throwable` showing the call site where the missing-key log was emitted
- This helps developers catch typos in placeholder names during development

### Output format
```
HH:mm:ss.SSS [thread] LEVEL  logger.name - Expanded message here
```

### Configuration
- `expandPlaceholders` (default: `true`) — enable/disable placeholder expansion
- `warnOnMissingKeys` (default: `false`) — enable missing key warnings with stack trace

## Removal

The custom appender approach (`ConsoleAppenderDev`, `ConsoleAppenderJson`, `RollingFileAppenderJson`) was removed in favor of the standard Logback pattern: built-in appenders configured with [`CustomJsonEncoder`](../logback/src/main/java/hr/hrg/dialog/logback/CustomJsonEncoder.java). The project was in early stage and maintaining both approaches had no benefit.

## References

- [`CustomJsonEncoder.java`](../logback/src/main/java/hr/hrg/dialog/logback/CustomJsonEncoder.java) (replacement)
- [`JsonLogWriter.java`](../logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java)
- [`cookbook/missing-keys-warn.md`](../cookbook/missing-keys-warn.md)
