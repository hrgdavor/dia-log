# 003: Automatic MDC cleanup via wrapper

* **Status:** Accepted
* **Date:** 2026-07-26

## Context

SLF4J's MDC (Mapped Diagnostic Context) is thread-local and persists until explicitly removed with `MDC.remove()`. When using structured logging with key-value pairs via `addKeyValue()`, developers often forget to clean up MDC entries, causing context to leak into subsequent log statements on the same thread.

This is especially problematic in:
- Web request handlers where per-request context should not bleed to the next request
- Background job processors where context from one job affects another
- Any long-lived thread that processes multiple independent units of work

## Options Considered

1. **Manual MDC cleanup:** Developers call `MDC.remove()` after each log. Error-prone and easy to forget.
2. **Automatic cleanup via wrapper:** The `LoggingEventBuilderWrapperBase` tracks keys and removes them from MDC after `log()` completes.

## Decision

The `LoggingEventBuilderWrapperBase` automatically manages MDC lifecycle:

1. When `addKeyValue(key, value)` is called, the key is added to both the SLF4J event builder AND the thread-local MDC via `MDC.put(key, String.valueOf(value))`.
2. The key is tracked in a `contextKeys` list.
3. After every `log()` call, `closeContext()` is invoked, which:
   - Calls the `clear` runnable (typically `contextEnd()` from `DiaLoggerBase`)
   - Removes all tracked keys from MDC via `MDC.remove(key)`
   - Clears the `contextKeys` list
   - Sets `closed = true` to prevent double-close

This ensures that key-value pairs added to a single log statement are automatically cleaned up after the log is emitted, preventing cross-contamination.

## Consequences

* **Positive:** Eliminates a common source of logging bugs (forgotten MDC cleanup); developers can use `kv()` without worrying about manual cleanup; works seamlessly with try-with-resources for explicit scope control; the `clear` runnable allows `DiaLoggerBase` to execute `contextEnd()` after each log.
* **Negative:** Slight performance overhead from MDC put/remove operations; if a developer manually calls `MDC.remove()` before `log()`, the wrapper's cleanup is still attempted (harmless but redundant); the `closed` flag prevents reuse of the wrapper after `log()` is called.

## References

- [`LoggingEventBuilderWrapperBase.closeContext()`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java#L209)
- [`DiaLoggerBase._contextStart()`](../core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java#L33)
- [`doc/mdc.vs.key-value.md`](../doc/mdc.vs.key-value.md)
