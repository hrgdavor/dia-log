# Dia-Log Documentation

This directory contains detailed documentation on key concepts, design decisions, and integration patterns used in Dia-Log.

---

## MDC vs Key-Value Pairs

**MDC is thread-local** (`String→String`), applies to every log on that thread, requires manual cleanup. 

**Key-Value Pairs** are statement-wide (`String→Object` with typed values), apply only to the specific log line, clean up automatically. 

Use MDC for global request identifiers (traceId, userId) stamped on every log. Use KVP for local event-specific data (cartSize, durationMs) relevant only to that point in time.

[Full document](mdc.vs.key-value.md)

---

## Stack Trace Sanitizer

Full stack trace is written to log, but fingerprint hash uses sanitized frames. Line numbers excluded to avoid hash changes from unrelated code edits. Filter defines which frames to include in the fingerprint — should be limited to app packages. 

[Full document](stack.trace.sanitizer.md)

---

## traceid, spanid, traceparent

**TraceId** (32 hex chars, 16 bytes) identifies the entire distributed transaction and never changes across hops. 

**SpanId** (16 hex chars, 8 bytes) identifies a single unit of work and changes at each service. 

The **traceparent** header format is `00-{traceId}-{spanId}-{flags}`. W3C standard ensures interoperability across Java, Node.js, Go, etc.


 **TraceId** never changes, **SpanId** changes at every hop (caller puts its own active **SpanId**), TraceFlags controls recording. OpenTelemetry Java handles it automatically via instrumentation.

[Full document](traceid.md)

---

## Trace ID Flow Example

Client generates **TraceId** `4bf92f3577b34da6a3ce929d0e0e4736` and calls Microservice A with header `00-4bf92f...-00f067...-01`. Microservice A extracts it, does its own work with **SpanId** `88a123...`, then calls Microservice B with header `00-4bf92f...-88a123...-01`. **TraceId** remains identical across all three services; **SpanId** changes at each hop to identify the caller.

[Full document](traceid.flow.md)