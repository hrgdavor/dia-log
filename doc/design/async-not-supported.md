# Async Logging Is Intentionally Not Supported

**Decision Date:** 2026-09-26  
**Status:** Deliberate design choice, not an oversight

---

## Summary

Dia-Log does **not** provide async appenders (e.g., `JsonAppenderAsync` with a bounded queue). This is a **deliberate design decision**, not a missing feature.

The library's core philosophy is that **zero-allocation, low-latency logging makes async unnecessary**. The performance and memory characteristics of Dia-Log's design make async logging problematic and counterproductive.

---

## Why Async Is Not Provided

### 1. Zero-Allocation Philosophy

Dia-Log is built around a zero-allocation hot path:

- **Every log line** writes directly into a reusable buffer
- **No intermediate objects** between the application and the output
- **GC pressure is minimal** because allocations are bounded and predictable

**Async logging breaks this philosophy:**
- Each log event must be **copied into a queue entry** (allocation)
- Queue entries are **boxed objects** with overhead
- The async thread must **process items one at a time** (additional work)
- **Queue management** adds complexity and GC pressure

```java
// Async pattern (what we DON'T do):
BlockingQueue<LogEvent> queue = new ArrayBlockingQueue<>(4096);
appender.setAsyncQueue(queue);

// Every log line:
LogEvent event = new LogEvent(...);  // Allocation
queue.offer(event);  // Synchronization + allocation overhead

// Async thread:
while (true) {
    LogEvent event = queue.take();  // Block + allocation
    serialize(event);  // More allocations
    write(event);
}
```

**This contradicts Dia-Log's zero-allocation goal.**

### 2. Async Is Not Free

Async logging has measurable costs:

| Cost | Description |
|------|-------------|
| **Queue allocation** | Every log event requires a queue entry object |
| **Synchronization** | Queue operations require locks or volatile reads |
| **Thread context** | Async thread adds CPU overhead and scheduling complexity |
| **GC pressure** | Queue entries are short-lived objects (high churn) |
| **Latency** | Log events wait in queue before being processed |

These costs are **non-trivial** and negate the benefits of async logging.

### 3. Stack Traces Are More Difficult

Dia-Log's **conditional stack trace** feature (`stackWhenTraceEnabled()`) is a core differentiator. Async logging makes this significantly more complex:

- **Thread safety**: Stack traces must be captured before async queuing
- **Memory consistency**: The async thread must see a consistent snapshot
- **Capture overhead**: Capturing a stack trace adds CPU work before queueing
- **Error handling**: If the async thread fails, in-flight stack traces are lost

**In a zero-allocation design**, capturing stack traces is efficient because:
- The stack is **already traversed** during fingerprinting
- No additional work is needed
- The trace is written directly to the output buffer

**With async**, capturing traces adds complexity without benefit.

### 4. The Real Problem: Async Doesn't Solve the Real Bottleneck

What is async logging supposed to solve?

> "Async logging decouples log generation from I/O, allowing the application to continue immediately."

**But this is already achieved by Dia-Log's design:**

1. **Zero-allocation**: No GC pauses during log generation
2. **Bulk writes**: Logback's `ReusableByteArrayOutputStream` writes events in bulk
3. **Direct I/O**: `JsonAppender` writes directly to the output stream
4. **No blocking**: The application never waits for I/O completion

**Async logging doesn't add value** because the application is never blocked in the first place.

### 5. EventSnapshotHandler Is the Async Alternative

For users who want to **forward logs to external systems** (HTTP endpoints, log aggregation services), Dia-Log provides `EventSnapshotHandler`:

```java
// Forward each serialized JSON event to an HTTP endpoint
BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4096);
appender.setEventSnapshotHandler(queue::add);

// Dedicated thread drains the queue asynchronously
new Thread(() -> {
    while (true) {
        byte[] json = queue.take();
        httpClient.post("https://logs.example.com", json);
    }
}).start();
```

**This is the correct pattern:**
- The **logging thread** serializes events to JSON (zero-allocation)
- A **separate thread** handles external forwarding (doesn't block logging)
- **No async appender** is needed; the application controls the forwarding

This gives users full control over async behavior while maintaining Dia-Log's zero-allocation guarantees.

---

## Comparison: Dia-Log vs Traditional Async Logging

| Feature | Dia-Log | Traditional Async (Log4j2, etc.) |
|---------|---------|----------------------------------|
| Hot path allocations | 0 B/op | Queue entries per event |
| GC pressure | Minimal (bounded buffers) | High (queue churn) |
| Stack trace support | Simple, zero-cost | Complex, requires capture |
| Thread count | Application thread | +1 async thread |
| Control | Application controls I/O | Library manages queue |
| Backpressure | Configurable buffer | Fixed queue size |

**Dia-Log's approach is simpler, more predictable, and better aligned with its zero-allocation goals.**

---

## When to Use Async Logging

If your use case requires async logging (e.g., high-throughput microservices with strict latency requirements), consider:

1. **Using `EventSnapshotHandler`** with a separate forwarding thread
2. **Using Logback's built-in async appenders** (`AsyncAppender`) before `JsonAppender`
3. **Using a dedicated logging framework** that supports async (e.g., Log4j2)

**But be aware:** These solutions have higher memory usage and GC pressure than Dia-Log's direct approach.

---

## Future Considerations

This decision is **permanent** for Dia-Log 1.x and is unlikely to change. The library's design is intentionally simple and focused on zero-allocation performance.

If async logging becomes a strong requirement from users, the recommended approach is:
1. **Use `EventSnapshotHandler`** for external forwarding
2. **Use Logback's async appenders** for internal buffering
3. **Don't add async to Dia-Log** — it would compromise the zero-allocation guarantee

---

## References

- [01-fundamentals.md](../perf/01-fundamentals.md) — Zero-allocation philosophy
- [08-packed-word-varhandle-stores.md](../perf/08-packed-word-varhandle-stores.md) — Direct buffer writing
- [EventSnapshotHandler](../logback/src/main/java/hr/hrg/dialog/logback/EventSnapshotHandler.java) — Async forwarding pattern
- [doc/migration.md](../migration.md) — Migration guide mentions async alternatives

---

**Document Owner:** Davor Hrg  
**Last Updated:** 2026-09-26
