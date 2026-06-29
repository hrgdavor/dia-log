# traceparent

The `traceparent` header is the core of the W3C Trace Context specification. Its entire purpose is to pass telemetry metadata from one service to the next across a network call (like an HTTP request or a message queue), ensuring that the `TraceId` and `SpanId` remain linked.

The header string is strictly formatted, always lower-case hexadecimal, hyphen-separated, and exactly **55 characters** long.

Here is the breakdown of each segment using the example:
`00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`

---

## 1. Version (`00`)

* **Length:** 2 hex characters (1 byte).
* **Current Value:** Almost always `00`.
* **What it means:** This identifies the version of the W3C Trace Context specification being used. Currently, `00` is the only widely adopted, stable release version. If the specification updates in the future with new fields or formats, this number will increment (e.g., to `01`), allowing newer systems to parse the header differently while remaining backward compatible.

---

## 2. TraceId (`4bf92f3577b34da6a3ce929d0e0e4736`)

* **Length:** 32 hex characters (16 bytes).
* **What it means:** This is the globally unique identifier for the entire distributed transaction.
* **Behavior:** When a user clicks a button or triggers an API, the first service (the root) randomly generates this ID. As the request propagates from a Java gateway to an internal microservice, down to a database or a third-party API, **this segment never changes**. Every service involved uses this exact ID to tag its logs and metrics for that specific request.
* **Constraint:** A TraceId consisting entirely of zeros (`00000000000000000000000000000000`) is considered invalid.

---

## 3. Parent SpanId (`00f067aa0ba902b7`)

* **Length:** 16 hex characters (8 bytes).
* **What it means:** This identifies the specific *caller* or the exact operation that initiated the current network request.
* **Behavior:** Unlike the TraceId, **this value changes at every hop**. When Service A sends an HTTP request to Service B, Service A puts its *own* active `SpanId` into this slot. When Service B receives the header, it reads this segment and treats it as its `ParentSpanId`. Service B then generates a brand-new `SpanId` for its own internal operations.
* **Constraint:** A SpanId consisting entirely of zeros (`0000000000000000`) is considered invalid.

---

## 4. TraceFlags (`01`)

* **Length:** 2 hex characters (1 byte), representing 8 bitmaps.
* **What it means:** This controls the recording and sampling behavior for the trace.
* **The Values:**
* `01` (**Sampled**): The originating service has decided to record this trace. It tells receiving services: *"Hey, I am saving this trace data, you should capture and send your spans for this trace to the APM collector too."*
* `00` (**Not Sampled**): The originating service chose not to record this trace (often done to save bandwidth/storage on high-traffic systems). It tells receiving services: *"I am ignoring this trace, you can skip capturing detail for it as well."*


* **Note:** The final 7 bits are currently reserved for future use, meaning `00` and `01` are the only flags you will see in standard setups today.

---

## How OpenTelemetry Java Handles It

When an incoming HTTP request hits your Java application (for instance, a Spring Boot controller), OpenTelemetry automatically intercepts the request, grabs the `traceparent` string, parses these 4 segments, and sets up the local execution context.

If your Java code then calls a database or makes another external HTTP call, OpenTelemetry will construct a *new* `traceparent` header, keep the `TraceId` exactly the same, swap the `SpanId` out for the Java app's current SpanId, and forward it along.
