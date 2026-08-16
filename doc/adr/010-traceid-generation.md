# 010: TraceId generation with timestamp

* **Status:** Accepted
* **Date:** 2026-07-26
* **Implementation Status:** Implemented

## Context

Distributed tracing requires unique identifiers that:
- Are globally unique across services
- Can be sorted chronologically for debugging
- Follow the W3C Trace Context standard format
- Can be generated without external coordination

Standard random UUIDs (v4) are globally unique but not sortable by creation time. Pure random IDs make it difficult to:
- Correlate logs across services in chronological order
- Debug race conditions by timestamp
- Identify when a trace was initiated vs when it propagated

## Options Considered

1. **Pure random trace IDs (UUID v4 style):** Globally unique, but not sortable by creation time.
2. **Timestamp-prefixed trace IDs:** Sortable by creation time, with random suffix for uniqueness.

## Decision

Implement [`TraceId`](../core/src/main/java/hr/hrg/dialog/core/TraceId.java) with a hybrid generation strategy:

### 16-byte trace ID structure
- **First 8 bytes**: Millisecond timestamp (`System.currentTimeMillis()`)
- **Last 8 bytes**: Random value (`ThreadLocalRandom.current().nextLong()`)

### Generation methods
- `generateTraceIdBytes()` — Returns a 16-byte array (big-endian)
- `generateTraceId()` — Returns a 32-character lowercase hex string
- `generateSpanId()` — Returns a 16-character hex string (8 random bytes, non-zero)

### Properties
- **Sortable**: Traces created later have higher timestamp prefixes, enabling chronological ordering
- **Globally unique**: 64 bits of randomness per millisecond makes collisions extremely unlikely
- **W3C compatible**: 32 hex chars for traceId, 16 hex chars for spanId match the `traceparent` header format (`00-{traceId}-{spanId}-flags`)
- **OpenTelemetry compatible**: The class provides static methods compatible with `io.opentelemetry.sdk.trace.IdGenerator`

### Example
```
TraceId: 4bf92f3577b34da6a3ce929d0e0e4736
         └─────────┬─────────┘ └─────────┬─────────┘
           timestamp (8 bytes)    random (8 bytes)
```

## Consequences

* **Positive:** Traces are naturally sortable by creation time; no external coordination needed for ID generation; compatible with W3C Trace Context and OpenTelemetry; the timestamp component aids debugging and log correlation.
* **Negative:** Millisecond timestamp precision means two IDs generated in the same millisecond on the same thread could theoretically collide (extremely unlikely with 64-bit random); the timestamp leaks information about when the trace was created (usually acceptable, but worth noting); not compatible with UUID-based systems without conversion.

## References

- [`TraceId.java`](../core/src/main/java/hr/hrg/dialog/core/TraceId.java)
- [`doc/traceid.md`](../doc/traceid.md)
- [`doc/traceid.flow.md`](../doc/traceid.flow.md)
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
