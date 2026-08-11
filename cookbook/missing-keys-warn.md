# Missing-Key Detection

The current checkout does not implement a `warnOnMissingKeys` feature and does not emit a `missingKeys` field. The earlier plan for this behavior is still pending in the repository, so the docs here reflect the present code rather than a future design.

## What the Current Writer Does

The JSON writer writes the message as-is and serializes key/value pairs as ordinary top-level fields. If a placeholder like `{ip}` is not provided, it simply remains literal in the message text.

```java
log.atInfo().kv("user", username).log("User {user} logged from {ip}");
```

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User alice logged from {ip}","user":"alice"}
```

## Current State

- There is no `warnOnMissingKeys` option in the current logback writer.
- There is no `missingKeys` field emitted by `JsonLogWriter`.
- If you want this behavior, it would need to be implemented in the writer and exposed through the appender integration.

This note is intentionally explicit so the documentation stays aligned with the implementation in the repository.
