

# MDC (Mapped Diagnostic Context) vs KVPs (Key-Value Pairs)

* Use **MDC** for global request identifiers (`traceId`, `userId`) that you want stamped on *every single log* during a web request.
* Use **Key-Value Pairs** for local metrics and specific event details (`cartSize`, `durationMs`) relevant only to that exact point in time.

[More details](mdc.vs.key-value.md)

# stack trace fingerprint and sanitizer

# traceid

In distributed tracing with OpenTelemetry and the W3C Trace Context standard, **TraceId** and **SpanId** are the fundamental identifiers used to track a request as it moves through your Java applications and microservices.

# spanid

# traceparent

# traceid flow
