# hybrid lock-free write pattern

Writing directly to `mmap` when free, falling back to a queue when contested should be effective.

---

### Part 1: How the JVM Handles Log Allocations

The JVM Garbage Collector doesn't inspect application-level semantics like "this object is a log event." Instead, memory allocation optimization relies on lower-level JVM mechanics:

1. **TLABs (Thread-Local Allocation Buffers):** The JVM reserves a tiny slice of the Young Generation (Eden space) for each thread. When a worker thread creates a short-lived `LogEvent`, it increments a local pointer without any global locking. It naturally lands in the Young Gen because *all* standard allocations land there first.
2. **JIT Escape Analysis & Scalar Replacement:** If a logging framework builds an internal event object that never leaves the current method (e.g., fully formatted locally), the C2 JIT compiler can perform **scalar replacement**. It dismantles the object into primitive CPU registers/stack variables, bypassing the heap completely.
3. **Queue Handoff Breaks Scalar Replacement:** The moment you pass a `LogEvent` instance to an async queue, it **escapes the local thread context**. Escape Analysis fails, forcing a standard allocation inside that thread's TLAB.

---

### Part 2: The Optimistic Fast-Path / Queue Fallback Pattern

Your second idea—**writing directly to `mmap` if no thread is writing, but delegating to a queue if contested**—is a well-established design in high-performance concurrent systems (often called **Optimistic Direct Writes with Async Offloading**).

#### How It Works Under the Hood

```
CALLING THREAD (Worker)
       │
       ▼
 Try tryLock() / tryAcquire()
 ┌───────────────┴───────────────┐
 │ (SUCCESS: Uncontested)        │ (FAIL: Contested)
 ▼                               ▼
Direct MMAP Write              Enqueue Log Object to Lock-Free Queue
 (Formats directly into MMAP)     (Allocates event on TLAB, worker returns)
 │                               │
 ▼                               ▼
Release Lock                    BACKGROUND DRAIN THREAD
                                 (Acquires MMAP lock when free,
                                  drains queue batch into MMAP)

```

#### Why This Logic Works

* **Zero Queue Overhead Under Low/Medium Load:** When your service experiences normal thread interleaving, threads acquire the lock instantly, format directly into `mmap`, and exit without creating an intermediary queue item or incurring context switches.
* **Non-Blocking Fallback Under High Load:** Under sudden concurrency spikes, caller threads don't block waiting for the `mmap` lock. They push the `LogEvent` into a lock-free queue (like a `JCTools` MPMC queue or Agrona RingBuffer) in ~10 nanoseconds and resume business logic.
* **Batch Efficiency:** A single background consumer thread wakes up when contention occurs, locks the `mmap` writer, and drains the queued events in a single **bulk write batch**, maximizing CPU store-buffer efficiency.

---

### Implementation Skeleton (Java 22+ / MemorySegment)

```java
public class HybridMmapAppender {
    private final ReentrantLock mmapLock = new ReentrantLock();
    private final Queue<LogEvent> fallbackQueue = new MpmcArrayQueue<>(1024);
    private final MemorySegment mmapSegment;
    private long writeOffset = 0;

    public void log(String level, String message, Object... args) {
        // 1. Try to acquire the direct write lock without blocking
        if (mmapLock.tryLock()) {
            try {
                // FAST PATH: Direct write (Single-threaded execution right now)
                // Drain any lingering items from previous contention first
                drainQueueToMmap();
                writeDirectToMmap(level, message, args);
            } finally {
                mmapLock.unlock();
            }
        } else {
            // SLOW PATH: Contested! Do not block worker thread.
            // Allocate object to TLAB (Young Gen) and hand off.
            fallbackQueue.offer(new LogEvent(level, message, args));
        }
    }

    private void drainQueueToMmap() {
        LogEvent event;
        while ((event = fallbackQueue.poll()) != null) {
            writeDirectToMmap(event.level, event.message, event.args);
        }
    }

    private void writeDirectToMmap(String level, String msg, Object[] args) {
        // Format string directly to mmapSegment using relative writeOffset...
    }
}

```

---

### Key Takeaway

Your logic is sound. Combining an **optimistic direct write** with an **async queue fallback** gives you the best of both worlds:

1. **Low Contention:** Latency stays near zero because caller threads never block on locked `mmap` segments.
2. **Adaptive Memory Profile:** Zero queue allocation overhead during baseline traffic, seamlessly falling back to cheap TLAB allocations only when concurrent threads actively race for the logger.