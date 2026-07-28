# 007: stackWhenTraceEnabled() conditional stack trace

* **Status:** Accepted
* **Date:** 2026-07-26

## Context

Developers often want call-stack visibility when debugging, but including a full stack trace on every log statement is:
- **Expensive**: Capturing a stack trace is ~10-100x slower than a simple log call
- **Noisy**: Production logs become cluttered with stack traces that are rarely useful
- **Redundant**: Most log statements don't need a stack trace — only the unusual ones do

The common pattern of "log at DEBUG with stack trace, but only when debugging" leads to:
- Two separate log statements (one at DEBUG, one at TRACE) that can get out of sync
- Duplicate log lines when both levels are enabled
- Developers forgetting to add the stack trace when they need it

## Options Considered

1. **Always include stack trace on exception logs:** Simple, but noisy and expensive in production.
2. **Separate TRACE-level log for stack trace:** No duplicates, but requires two log statements that can drift apart.
3. **Conditional stack trace on the same log line:** Single log statement with optional throwable cause when TRACE is enabled.

## Decision

Introduce `stackWhenTraceEnabled()` as a fluent configuration method on the builder wrapper:

```java
log.atDebug().stackWhenTraceEnabled()
    .kv("state", state)
    .log("Change state to {state}");
```

### Behavior
- The log is emitted at the requested level (e.g., DEBUG) with a clean message
- If TRACE is enabled on the underlying logger, a `Throwable` (capturing the call stack) is attached as the cause
- If TRACE is not enabled, no throwable is attached — zero overhead
- Only one log line is ever produced (no duplicate at TRACE level)

### Implementation
In [`LoggingEventBuilderWrapperBase.maybeAttachTraceCause()`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java#L203):
```java
private void maybeAttachTraceCause() {
    if (stackWhenTraceEnabled && logger != null && logger.isTraceEnabled()) {
        delegate.setCause(new Throwable("stackWhenTraceEnabled"));
    }
}
```

This is called at the start of every `log()` overload, before delegating to the underlying builder.

## Consequences

* **Positive:** Single log line with conditional stack trace — no duplicates; zero overhead when TRACE is disabled (just a boolean check); developers can add stack visibility to any log statement without changing the log level; the throwable's message ("stackWhenTraceEnabled") clearly indicates why the stack is present.
* **Negative:** The stack trace appears as a throwable cause in the output, which may be unexpected for developers who don't understand the feature; the `new Throwable()` capture happens at log time, not at exception time, so the stack shows the logging call site (which is usually what you want); requires the wrapper to hold a reference to the underlying `Logger` for `isTraceEnabled()` check.

## References

- [`LoggingEventBuilderWrapperBase.maybeAttachTraceCause()`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java#L203)
- [`cookbook/stackWhenTrace.md`](../cookbook/stackWhenTrace.md)
