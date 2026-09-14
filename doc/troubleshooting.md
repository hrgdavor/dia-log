# Dia-Log Troubleshooting Guide

This guide helps diagnose and resolve common issues when using Dia-Log with SLF4J and Logback.

---

## Quick Reference

### Basic Structured Logging

```java
// Structured logging with key/value pairs
log.atInfo().kv("userId", id).kv("action", "login").log("User logged in");

// Conditional stack trace (only when TRACE level)
log.atDebug().stackWhenTraceEnabled().kv("state", state).log("Debug state: {state}");

// Exception with stack trace
log.atError().setCause(ex).stackWhenTraceEnabled().log("Failed to process");
```

### Async Event Forwarding

```java
// Forward logs to HTTP endpoint using EventSnapshotHandler
BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(4096);
appender.setEventSnapshotHandler(queue::add);

// Separate thread handles forwarding (doesn't block logging)
new Thread(() -> {
    while (true) {
        byte[] json = queue.take();
        httpClient.post("https://logs.example.com", json);
    }
}).start();
```

### XZ Compression

```xml
<!-- Add dependency for XZ compression -->
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.12</version>
</dependency>

<!-- In logback.xml, use .xz in fileNamePattern for archived files -->
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
    <file>logs/app.jsonl</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.jsonl.xz</fileNamePattern>
        <maxFileSize>10MB</maxFileSize>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
</appender>
```

---

## Error Patterns

### "No MDC adapter configured"

**Symptom:** `NullPointerException` when accessing MDC in tests

```
java.lang.NullPointerException
    at ch.qos.logback.core.joran.spi.DefaultClassResolver.instantiateClass(DefaultClassResolver.java:39)
    at ch.qos.logback.core.joran.spi.ConfigurationWatchList.doWatch(ConfigurationWatchList.java:57)
```

**Cause:** Fresh `LoggerContext` has no MDC adapter initialized

**Fix:** Initialize MDC adapter in `@BeforeEach`:

```java
@SpringBootTest
@ExtendWith(LogbackTestExtension.class)
public class MyAppIntegrationTest {
    
    @BeforeEach
    void setup(LogbackMDCAdapter adapter) {
        ((LoggerContext) LogManager.getLoggerContext()).getLoggerFactory()
            .addContext(new Context());
        Context context = ((LoggerContext) LogManager.getLoggerContext()).getContext();
        context.getAdapter(LogbackMDCAdapter.class);
    }
}
```

Or programmatically:

```java
LoggerContext context = (LoggerContext) LogManager.getContext(false);
context.start();
```

---

### "GZIP fallback instead of XZ"

**Symptom:** Rotated files have `.gz` extension instead of `.xz`

**Cause:** Missing `org.tukaani:xz` dependency

**Fix:** Add the dependency to your project:

```xml
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.12</version>
</dependency>
```

**Alternative:** Use `.gz` in `fileNamePattern` (no dependency needed):

```xml
<fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.jsonl.gz</fileNamePattern>
```

---

### "BufferFullException: Event too large"

**Symptom:** Log events fail to write with `BufferFullException`

**Cause:** Event buffer capacity is too small (default: 16 MiB)

**Fix:** Increase buffer capacity in logback.xml:

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
    <setEventBufferCapacity>32</setEventBufferCapacity>
</appender>
```

Or programmatically:

```java
appender.setEventBufferCapacity(32 * 1024 * 1024);  // 32 MiB
```

**Note:** Very large events (>16 MiB) should be investigated — they may indicate serialization issues.

---

### "Cannot instantiate stackTraceFilter class"

**Symptom:** Stack trace filtering fails at startup

**Cause:** Class not found or doesn't implement `Predicate<String>`

**Fix:** Verify the class exists and implements the interface:

```java
public class MyFilter implements Predicate<String> {
    @Override
    public boolean test(String className) {
        // Only include your package frames
        return className.startsWith("com.example.");
    }
}
```

And configure in logback.xml:

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
    <stackTraceFilter>com.example.MyFilter</stackTraceFilter>
</appender>
```

---

### "WARN: No appenders could be found for logger"

**Symptom:** Warnings about missing appenders during startup

**Cause:** Logback configuration not loaded or appender not started

**Fix:** Ensure:
1. `logback.xml` is in `src/main/resources`
2. Appender `start()` method is called
3. Logger is attached to the appender

```xml
<configuration>
    <appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
        <!-- ... -->
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

---

### "Stack trace not appearing when expected"

**Symptom:** `stackWhenTraceEnabled()` doesn't show stack trace

**Cause:** Log level is not TRACE

**Fix:** Set the log level to TRACE:

```xml
<root level="TRACE">
    <appender-ref ref="JSON"/>
</root>
```

Or programmatically:

```java
log.setLevel(Level.TRACE);
```

---

### "Duplicate keys in JSON output"

**Symptom:** Same key appears multiple times in JSON

**Cause:** Both MDC and KV pairs use the same key

**Example:**

```java
MDC.put("userId", "123");  // MDC entry
log.atInfo().kv("userId", 456).log("...");  // KV pair with same key
```

**Result:**

```json
{"userId":"123","userId":456,...}  // Duplicate!
```

**Solution:** Use different keys or accept duplicates (last-wins semantics in most parsers)

---

### "Performance degradation under load"

**Symptom:** Log latency increases significantly at high throughput

**Possible causes and fixes:**

1. **Frequent object allocations in logging code**
   - Use `kv()` with simple types (String, int, long, boolean)
   - Avoid complex objects as KV values

2. **Large stack traces**
   - Use `stackWhenTraceEnabled()` with TRACE level (not DEBUG)
   - Configure stack trace filter to exclude framework frames

3. **Sync I/O on bottleneck device**
   - Use `JsonAppenderRolling` with buffered output
   - Ensure disk I/O is not the bottleneck

4. **Too many appenders**
   - Consolidate appenders where possible
   - Use single output with filtering at application level

---

### "EventSnapshotHandler blocks logging"

**Symptom:** Logging thread blocked waiting for snapshot handler

**Cause:** Snapshot handler implementation is slow or blocking

**Fix:** Ensure the handler does not block:

```java
public class AsyncSnapshotHandler implements EventSnapshotHandler {
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(1024);
    
    @Override
    public void onEvent(byte[] json) {
        // Don't block! Just add to queue
        queue.offer(json);
    }
    
    // Separate thread processes the queue
    @Override
    public void start() {
        new Thread(() -> {
            while (true) {
                byte[] json = queue.take();
                process(json);  // Do HTTP calls here
            }
        }).start();
    }
}
```

---

### "ClassNotFoundException: JacksonException"

**Symptom:** Jackson serialization errors

**Cause:** Missing Jackson dependency

**Fix:** Add Jackson 3.x dependency:

```xml
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-core</artifactId>
    <version>3.2.1</version>
</dependency>
<dependency>
    <groupId>tools.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>3.2.1</version>
</dependency>
```

The logback module depends on Jackson 3.x for KV serialization.

---

## Configuration Validation

### Verify Logback Configuration

```bash
# Run with validation
java -jar app.jar --validate-config

# Or check logback status
tail -f logs/app.jsonl | grep -i "status\|error\|warn"
```

### Check Event Buffer Usage

```java
JsonAppender appender = ...;
System.out.println("Buffer capacity: " + appender.getEventBufferCapacity());
System.out.println("Current size: " + appender.getEventBuffer().buf.length);
```

---

## Performance Tips

### Optimize Stack Trace Filtering

```xml
<!-- Only fingerprint app frames -->
<stackTraceFilter>com.example</stackTraceFilter>
```

### Use Reusable Buffers

```java
// Don't create new appenders per request
// Create once and reuse
private static final JsonAppender appender = new JsonAppender();
appender.start();
```

### Monitor Memory Usage

```java
// Check for memory leaks
public void monitor() {
    Runtime runtime = Runtime.getRuntime();
    System.out.println("Memory: " + (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 + " MB");
}
```

---

## Getting Help

If you encounter an issue not covered here:

1. Check the logs for error messages
2. Verify your configuration matches the examples
3. Check the [documentation](../README.md)
4. Review the [benchmark results](../doc/perf-exploration/) for performance guidance

---

## Related Documentation

- [Usage Guide](../usage.md) — Basic usage patterns
- [Migration Guide](../migration.md) — Moving from other logging libraries
- [Performance Guide](../doc/perf/README.md) — Performance optimization techniques
- [EventSnapshotHandler](../logback/src/main/java/hr/hrg/dialog/logback/EventSnapshotHandler.java) — Async forwarding pattern
