# 011: No-op wrapper pattern for disabled levels

* **Status:** Accepted
* **Date:** 2026-07-26
* **Implementation Status:** Implemented

## Context

When a log level is disabled (e.g., DEBUG is disabled), calling `logger.atDebug()` should:
- Return quickly without creating unnecessary objects
- Allow method chaining without side effects
- Support `stackWhenTraceEnabled()` even when the original level is disabled (so TRACE-level stack traces can still be emitted)

Creating a full `LoggingEventBuilderWrapper` for every disabled level check would:
- Allocate objects that are immediately discarded
- Add GC pressure in high-throughput applications
- Complicate the `atXxx()` methods with null checks and conditional logic

## Options Considered

1. **Return null when level is disabled:** Simplest, but breaks fluent API chaining and requires null checks everywhere.
2. **Create a new wrapper for each disabled call:** Works with fluent API, but creates unnecessary garbage.
3. **Singleton no-op wrapper:** Zero allocation, fluent API works seamlessly, special handling for `stackWhenTraceEnabled()`.

## Decision

Implement [`LoggingEventBuilderWrapperNoop`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperNoop.java) as a singleton no-op wrapper:

### Singleton pattern
- Private constructor creates a wrapper around `NOPLoggingEventBuilder.singleton()`
- `singleton()` method returns the single instance
- All methods return `INSTANCE` (for fluent API) or do nothing (for `log()`)

### Special behavior for stackWhenTraceEnabled
- Even when the original level is disabled, `stackWhenTraceEnabled()` is still functional
- This allows `log.atDebug().stackWhenTraceEnabled().kv(...).log(...)` to emit a TRACE-level log with stack trace when TRACE is enabled, even if DEBUG is disabled
- The no-op wrapper holds a `null` logger, so `maybeAttachTraceCause()` is skipped — but the wrapper is still returned so the chain can continue

### Usage in DiaLoggerBase
```java
public L atDebug() {
    if(!isDebugEnabled()) return noOpWrapper();
    return _contextStart(delegate.atDebug());
}
```

When the level is disabled, `noOpWrapper()` returns the singleton, and all subsequent `kv()`, `stackWhenTraceEnabled()`, and `log()` calls are no-ops.

## Consequences

* **Positive:** Zero allocation overhead for disabled log levels; fluent API works seamlessly — developers don't need to check for null; `stackWhenTraceEnabled()` can still trigger TRACE-level logs from disabled levels; thread-safe singleton with no synchronization overhead.
* **Negative:** The no-op wrapper's `stackWhenTraceEnabled()` cannot actually attach a trace cause because it holds no `Logger` reference (by design — the original level is disabled); developers might be confused why `stackWhenTraceEnabled()` on a disabled level doesn't produce output at the original level (it produces TRACE-level output instead); the singleton pattern means the wrapper cannot hold per-call state (which is fine since it's a no-op).

## References

- [`LoggingEventBuilderWrapperNoop.java`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperNoop.java)
- [`DiaLoggerBase.atDebug()`](../core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java#L69)
