# MDC (Mapped Diagnostic Context) vs KVPs (Key-Value Pairs)

In SLF4J (Simple Logging Facade for Java), both **MDC (Mapped Diagnostic Context)** and **Key-Value Pairs (KVPs)** are used to add contextual metadata to your logs. However, they solve this problem in fundamentally different ways.

Here is the breakdown of how they work, how they differ, and when to use which.

---

## 1. Mapped Diagnostic Context (MDC)

MDC is a **thread-local** map. It allows you to attach context at the beginning of a thread's execution (like a web request), and every log statement executed on that same thread will automatically include that context.

### How it works:

```java
import org.slf4j.MDC;

public void handleRequest(String userId, String requestId) {
    // Put values into the context
    MDC.put("userId", userId);
    MDC.put("requestId", requestId);

    // Any logs called here (or in methods called from here) automatically get the metadata
    logger.info("Processing payment"); 
    
    // Always clean up MDC when the thread is done!
    MDC.clear(); 
}
```

### Best Used For:

* **Scoped/Ambient Context:** Data that applies to an entire request or transaction (e.g., `tenantId`, `userId`, `traceId`).
* **Cross-cutting Concerns:** When you want metadata added to logs inside deep library code without passing that data through every single method signature.

---

## 2. Key-Value Pairs (SLF4J 2.0+)

Introduced in SLF4J 2.0, Key-Value Pairs allow you to attach metadata **locally and explicitly to a single log statement** using a fluent API.

### How it works:

```java
// Adds 'orderId' and 'amount' ONLY to this specific log statement
logger.atInfo()
      .addKeyValue("orderId", "98765")
      .addKeyValue("amount", 49.99)
      .log("Order placed successfully");
```

### Best Used For:

* **Event-Specific Data:** Data that only makes sense for that exact log line (e.g., item price, execution time of a specific method, specific error codes).
* **Structured Logging:** When you want to ensure data types (like numbers or booleans) are preserved as actual types in JSON logs rather than just being forced into a string map.

---

## Key Differences at a Glance

| Feature                   | MDC (Mapped Diagnostic Context)                                                                                  | Key-Value Pairs (SLF4J 2.0+)                                                  |
| ------------------------- | ---------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **Scope**                 | **Thread-wide**. Inherited by all logs on that thread until cleared.                                             | **Statement-wide**. Applies *only* to the specific log line.                  |
| **Data Type**             | Map of `String` to `String` only.                                                                                | Map of `String` to `Object` (supports typed data).                            |
| **Lifecycle Management**  | **Manual clean-up required** (`MDC.clear()`). Risks memory leaks or "log bleeding" in thread pools if forgotten. | **Automatic**. Scoped entirely to the log statement execution.                |
| **Asynchronous Pitfalls** | Harder to use with reactive programming or thread pools (requires transferring the MDC context manually).        | Natural fit for reactive/async code because it doesn't rely on `ThreadLocal`. |

---

## How They Appear in Your Output

To actually see this metadata, your log formatter (like Logback or Log4j2) needs to be configured to output them—usually as JSON.

If you use both, a JSON layout would render them similarly, but notice the scope difference:

```json
// Log Statement 1 (Using MDC + Key Value)
{
  "@timestamp": "2026-06-04T18:00:00Z",
  "message": "Order placed successfully",
  "userId": "12345",         // <-- From MDC (Thread-level)
  "requestId": "abc-789",    // <-- From MDC (Thread-level)
  "orderId": "98765"         // <-- From Key-Value (Statement-level)
}

// Log Statement 2 (Next line on the same thread, without the Key-Value api)
{
  "@timestamp": "2026-06-04T18:00:01Z",
  "message": "Session continuing",
  "userId": "12345",         // <-- MDC is still here!
  "requestId": "abc-789"     // <-- MDC is still here!
                             // (orderId is gone because it was a Key-Value pair)
}
```

## Which to use,when?

* Use **MDC** for global request identifiers (`traceId`, `userId`) that you want stamped on *every single log* during a web request.
* Use **Key-Value Pairs** for local metrics and specific event details (`cartSize`, `durationMs`) relevant only to that exact point in time.
