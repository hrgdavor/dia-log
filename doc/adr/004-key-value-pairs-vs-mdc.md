# 004: Key-value pairs vs MDC design

* **Status:** Accepted (updated 2026-08-22)
* **Date:** 2026-07-26
* **Implementation Status:** Implemented

## Context

SLF4J provides two mechanisms for adding structured context to log events:
1. **MDC (Mapped Diagnostic Context)** — Thread-local `Map<String, String>` that applies to every log on that thread
2. **Key-value pairs (SLF4J 2.0)** — Statement-wide `addKeyValue()` on `LoggingEventBuilder` that applies only to the specific log line

The project needed a clear strategy for when to use each mechanism, as they have different semantics, lifetimes, and type constraints.

## Options Considered

1. **Use only MDC for all context:** Simple, but MDC is thread-local and requires manual cleanup; limited to `String → String`.
2. **Use only key-value pairs for all context:** Statement-scoped values and typed values, but global identifiers (traceId, userId) would need to be repeated on every log statement.
3. **Dual-context model with clear separation:** MDC for global thread-local context, KVP for local statement-wide context.

## Decision

Adopt a dual-context model with clear separation of concerns:

### MDC — Global, thread-local context
- Used for identifiers that should appear on every log line within a request/operation
- Examples: `traceId`, `spanId`, `userId`, `tenant`, `requestId`
- Managed manually by the application (or via interceptors/filters)
- Persists across multiple log statements on the same thread
- Limited to `String → String` mapping

### Key-value pairs — Local, statement-wide context
- Used for data relevant only to the specific log event
- Examples: `statusCode`, `durationMs`, `cartSize`, `orderId`
- Scoped to the specific log statement by SLF4J's `LoggingEventBuilder`
- Supports `String → Object` with typed values (integers, booleans, etc.)
- Does not leak to subsequent log statements

### Duplicate key handling
When both MDC and key-value pairs contain the same key, **both values are emitted** in the JSON output (KV first, then MDC). Duplicate keys are not deduped on the hot path. Downstream log ingestion handles rare duplicate-key cases — most JSON parsers apply last-wins or array semantics, and log aggregation systems (Loki, Elasticsearch, Splunk) accept duplicate object keys without issue. Dedup was removed for performance reasons; see [ADR-012](./012-remove-key-dedup.md).

## Consequences

* **Positive:** Clear mental model: MDC for "who/where", KVP for "what happened"; KVP is naturally scoped to the log statement; typed values in KVP enable proper JSON serialization (numbers stay numbers); no per-event key-tracking set on the hot path.
* **Negative:** Developers must understand when to use MDC vs KVP; MDC requires manual cleanup (though frameworks like Spring can automate this); rare duplicate keys may appear in output when the same key is set in both contexts.

## References

- [`doc/mdc.vs.key-value.md`](../doc/mdc.vs.key-value.md)
- [`JsonLogWriter.writeJsonEvent()`](../logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java#L186)
- [`LoggingEventBuilderWrapperBase.addKeyValue()`](../core/src/main/java/hr/hrg/dialog/core/LoggingEventBuilderWrapperBase.java#L110)
