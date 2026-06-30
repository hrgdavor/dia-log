# span id

In distributed tracing with OpenTelemetry and the W3C Trace Context standard, **TraceId** and **SpanId** are the fundamental identifiers used to track a request as it moves through your Java applications and microservices.

Think of a **Trace** as the entire journey of a request, and a **Span** as an individual leg of that journey.

---

## The Core Definitions

### 1. TraceId (The Journey)

* **What it is:** A globally unique identifier that represents an entire end-to-end transaction or request across your entire system.
* **W3C Format:** A 32-hex-character string (16 bytes), for example: `4bf92f3577b34da6a3ce929d0e0e4736`.
* **Role:** Every single microservice, database call, or internal method involved in fulfilling that specific user request will share the exact same `TraceId`.

### 2. SpanId (The Leg of the Journey)

* **What it is:** A unique identifier for a specific, single unit of work within that trace (e.g., an HTTP request handler, a database query, or a specific method execution).
* **W3C Format:** A 16-hex-character string (8 bytes), for example: `00f067aa0ba902b7`.
* **Role:** While the `TraceId` stays constant across the entire request lifecycle, the `SpanId` changes as the request moves into different operations or services.

---

## How They Relate: The Hierarchy

The relationship between a `TraceId` and a `SpanId` is a **one-to-many, hierarchical relationship**.

A single `TraceId` contains many `SpanId`s. To reconstruct the exact path of a request, OpenTelemetry links spans together using a `ParentSpanId`.

* **Root Span:** The very first operation (e.g., an API Gateway receiving a request). It generates a `TraceId` and a `SpanId`, but has no parent.
* **Child Spans:** When the Gateway calls an internal Java microservice, OpenTelemetry passes the `TraceId` and the current `SpanId` across the network via HTTP headers (this is called **Context Propagation**).
* The Java service receives these headers, keeps the same `TraceId`, creates a *new* `SpanId` for its own work, and notes the Gateway's `SpanId` as its `ParentSpanId`.

---

## In OpenTelemetry Java

When you use the OpenTelemetry Java SDK, this correlation happens automatically behind the scenes (via instrumentation libraries for Spring Boot, Quarkus, Logback, etc.).

If you look at the W3C standard header (`traceparent`) being sent between your Java services, it looks like this:

```text
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             │  └─────────────┬──────────────┘ └───────┬──────┘ └─┬┘
             ▼                ▼                        ▼          ▼
          Version          TraceId                  SpanId     TraceFlags

```

### Accessing them in Java Code

If you ever need to programmatically access these IDs in your Java application (for custom logging or business logic), you can pull them from the current OpenTelemetry context:

```java
import io.opentelemetry.api.trace.Span;

// Get the currently active span in the executing thread
Span currentSpan = Span.current();

// Extract the IDs
String traceId = currentSpan.getSpanContext().getTraceId();
String spanId = currentSpan.getSpanContext().getSpanId();

System.out.println("TraceID: " + traceId);
System.out.println("SpanID: " + spanId);

```

### Why this matters for Java Developers

By adhering to the W3C specification, OpenTelemetry Java ensures that if your Java service calls a Node.js service, which then calls a Go service, the `TraceId` remains perfectly intact. When you look at an APM tool (like Jaeger, Zipkin, or Datadog), you will see a flawless, chronological tree view of the entire request.


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
