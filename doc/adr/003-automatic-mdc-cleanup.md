# 003: Automatic MDC cleanup via wrapper

* **Status:** Declined
* **Date:** 2026-07-26
* **Implementation Status:** Not implemented
* **Decision Date:** 2026-09-26

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

## Why Declined

The feature was **declined** as a deliberate design decision. MDC management is intentionally left to application code:

1. **MDC is SLF4J's responsibility:** SLF4J already provides `MDC.clear()` for cleanup. Dia-Log should not add another layer of management.
2. **Statement-scoped KVPs are the primary mechanism:** The library's main value proposition is statement-scoped key-value pairs that auto-cleanup. MDC is an optional thread-local feature managed separately.
3. **No breaking change benefit:** Implementing this would require tracking MDC keys in the wrapper, adding complexity without significant benefit to the core design.
4. **Application code can use patterns:** Common patterns like try-finally blocks around `MDC.put()` are straightforward and give full control.
5. **Explicit over implicit:** Requiring developers to explicitly call `MDC.clear()` when they need thread-local cleanup is better than automatic cleanup that might hide issues.

This is a deliberate design decision, not an oversight. The library prioritizes statement-scoped KVPs over MDC management and keeps MDC handling simple and explicit.

## Consequences

* **Positive:** No performance overhead from MDC put/remove operations; MDC is managed by the application as SLF4J intended.
* **Negative:** Developers must manage MDC lifecycle manually via `MDC.put()`/`MDC.remove()`/`MDC.clear()` when using thread-local context.
* **Future consideration:** If automatic MDC cleanup becomes a strong requirement from users, it could be added as an optional feature (e.g., `MDCAdapter` implementation).

## References

- [`LoggingEventBuilderWrapperBase`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java) — delegates `addKeyValue()` to SLF4J without MDC interaction
- [`DiaLoggerBase._contextStart()`](../core/src/main/java/hr/hrg/dialog/core/DiaLoggerBase.java#L29)
- [`doc/mdc.vs.key-value.md`](../doc/mdc.vs.key-value.md)