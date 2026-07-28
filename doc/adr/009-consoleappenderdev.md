# 009: ConsoleAppenderDev for development

* **Status:** Accepted
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

## Options Considered

1. **Use standard Logback PatternLayout:** Familiar format, but no placeholder expansion from key-value pairs and no missing key detection.
2. **Custom development appender with placeholder expansion:** Provides readable output and catches missing keys during development.

## Decision

Create [`ConsoleAppenderDev`](../logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderDev.java) as a development-only console appender that:

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

## Consequences

* **Positive:** Developers get readable console output during development without changing log statements; missing key detection catches bugs early (typos in placeholder names); zero impact on production: use `ConsoleAppenderJson` for production, `ConsoleAppenderDev` for development; the appender is self-contained — no external configuration needed beyond `logback.xml`.
* **Negative:** Placeholder expansion adds CPU overhead (string parsing and lookup); the missing key stack trace can be verbose in development output; only works with `{name}` style placeholders, not SLF4J's `{}` positional arguments (by design).

## References

- [`ConsoleAppenderDev.java`](../logback/src/main/java/hr/hrg/dialog/logback/ConsoleAppenderDev.java)
- [`cookbook/missing-keys-warn.md`](../cookbook/missing-keys-warn.md)
