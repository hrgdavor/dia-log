
The logback module currently exposes `JsonAppender` and `JsonAppenderRolling` rather than a `CustomJsonEncoder` wrapper. Both classes delegate to `JsonLogWriter`, which emits flat JSON fields such as `ts`, `level`, `logger`, `thread`, `msg`, your key/value pairs, and exception fields like `errClass`, `errMessage`, `stack`, and `errHash`.

Example usage from Java:

```java
Logger logger = LoggerFactory.getLogger(MyClass.class);
logger.atInfo()
    .kv("userId", 42)
    .kv("action", "login")
    .log("User {userId} performed {action}");
```

The resulting JSON shape is similar to:

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User {userId} performed {action}","userId":42,"action":"login"}
```

`JsonLogWriterClassic` remains available as an alternative implementation, but `JsonLogWriter` is the default high-throughput path.

## Filtering stack-trace frames during fingerprinting

By default, every stack-trace frame is included in both the written `stack` field and the `errHash` fingerprint. If you want to exclude noisy or framework frames (for example, to make `errHash` group errors by your application's call path only), both `JsonAppender` and `JsonAppenderRolling` accept a logback-configurable `<stackTraceFilter>`.

The value is the **fully-qualified class name** of a class that implements `java.util.function.Predicate<String>` and has a public no-arg constructor. The predicate is fed the fully-qualified class name of each stack frame; frames for which `test(...)` returns `false` are excluded from **both** the serialized `stack` field and the `errHash` fingerprint.

### Example filter class

```java
package com.example;

import java.util.function.Predicate;

// Keep only application frames; drop JDK/framework internals.
public class AppFramesOnly implements Predicate<String> {
    public AppFramesOnly() {}

    @Override
    public boolean test(String frameClassName) {
        return frameClassName.startsWith("com.example");
    }
}
```

### Wiring it up in logback.xml

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
    <file>logs/app.jsonl</file>
    <stackTraceFilter>com.example.AppFramesOnly</stackTraceFilter>
    <encoder>
        <pattern>%msg%n</pattern>
    </encoder>
</appender>
```

> **Note:** `JsonAppender` extends `OutputStreamAppender`, so its output stream must be assigned programmatically (see the [example](../example/README.md)). `JsonAppenderRolling` can be configured with `<file>`/`<rollingPolicy>` directly.

The same `<stackTraceFilter>` element works for `JsonAppenderRolling`:

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
    <file>logs/app.jsonl</file>
    <stackTraceFilter>com.example.AppFramesOnly</stackTraceFilter>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.jsonl</fileNamePattern>
        <maxHistory>7</maxHistory>
    </rollingPolicy>
    <encoder>
        <pattern>%msg%n</pattern>
    </encoder>
</appender>
```

If `<stackTraceFilter>` is omitted or left blank, the default accept-all predicate is used (all frames are included), preserving existing behavior.

### Behavior

- Frames rejected by the predicate are skipped in the single-pass writer, so they affect neither the JSON `stack` content nor the `errHash` value.
- If **all** frames are rejected, the sanitizer falls back to hashing/writing the top 3 raw frames (the same fallback used when a filter matches nothing), so `errHash` is still deterministic.
- The predicate receives the frame's fully-qualified class name exactly as reported by the JVM (including lambda suffixes such as `$$Lambda$42/0x...`), so write your matchers accordingly (e.g. `startsWith(...)` rather than exact equality).
