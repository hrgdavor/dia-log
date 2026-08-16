# 003: Automatic MDC cleanup via wrapper

* **Status:** Not accepted
* **Date:** 2026-07-26
* **Implementation Status:** Not implemented

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

**Not accepted.** The automatic MDC cleanup feature described below was considered but **not implemented**. MDC handling is left entirely to SLF4J — Dia-Log does not manage MDC keys.

The proposed (but not implemented) design was:

1. When `addKeyValue(key, value)` is called, the key is added to both the SLF4J event builder AND the thread-local MDC via `MDC.put(key, String.valueOf(value))`.
2. The key is tracked in a `contextKeys` list.
3. After every `log()` call, `closeContext()` is invoked, which removes all tracked keys from MDC via `MDC.remove(key)`.

## Why Not Accepted

The feature was not implemented. Key-value pairs added via `addKeyValue()` in `LoggingEventBuilderWrapperBase` are delegated directly to the underlying SLF4J builder, which handles them as statement-scoped key-value pairs (not MDC entries). MDC remains a separate thread-local mechanism managed by the application code.

## Consequences

* **Positive:** No performance overhead from MDC put/remove operations; MDC is managed by the application as SLF4J intended.
* **Negative:** Developers must manage MDC lifecycle manually via `MDC.put()`/`MDC.remove()`/`MDC.clear()` when using thread-local context.

## References

- [`LoggingEventBuilderWrapperBase`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java) — delegates `addKeyValue()` to SLF4J without MDC interaction
- [`DiaLoggerBase._contextStart()`](../core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java#L29)
- [`doc/mdc.vs.key-value.md`](../doc/mdc.vs.key-value.md)