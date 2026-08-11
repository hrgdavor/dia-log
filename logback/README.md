
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
