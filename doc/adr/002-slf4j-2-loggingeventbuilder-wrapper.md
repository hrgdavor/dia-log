# 002: SLF4J 2.0 LoggingEventBuilder wrapper pattern

* **Status:** Accepted
* **Date:** 2026-07-26
* **Implementation Status:** Implemented

## Context

SLF4J 2.0 introduced the `LoggingEventBuilder` interface, enabling structured logging with key-value pairs via `addKeyValue()`. However, the raw builder interface does not provide:
- Fluent API extensions like `kv()` shorthand
- Integration with Dia-Log's `stackWhenTraceEnabled()` feature
- Type-safe subclass chaining for custom logger implementations

The project needed a way to extend SLF4J's builder without breaking compatibility with the standard API.

## Options Considered

1. **Use raw SLF4J LoggingEventBuilder directly:** No extra abstraction, but no fluent extensions and no stack trace integration.
2. **Wrap LoggingEventBuilder in a delegating wrapper:** Adds fluent API and stack trace integration, with full backward compatibility.

## Decision

Implement a wrapper pattern around `LoggingEventBuilder`:

- [`LoggingEventBuilderWrapperBase`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java) — Base wrapper that delegates all `LoggingEventBuilder` methods and adds `kv()` shorthand.
- [`LoggingEventBuilderWrapper`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapper.java) — Concrete wrapper for active log events.
- [`LoggingEventBuilderWrapperNoop`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperNoop.java) — Singleton no-op wrapper returned when a log level is disabled, avoiding unnecessary object creation.

The wrapper implements `LoggingEventBuilder` so it can be passed anywhere the standard builder is expected.

## Consequences

* **Positive:** Full backward compatibility with SLF4J 2.0 API; fluent API (`kv()`, `stackWhenTraceEnabled()`) improves developer ergonomics; no-op wrapper avoids allocation overhead when log levels are disabled; `with(LogFiller)` allows injection of additional context without modifying the builder chain.
* **Negative:** Additional abstraction layer adds complexity; subclassing `LoggingEventBuilderWrapperBase` requires understanding the delegation pattern.

## References

- [`LoggingEventBuilderWrapperBase`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java)
- [`LoggingEventBuilderWrapper`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapper.java)
- [`LoggingEventBuilderWrapperNoop`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperNoop.java)
