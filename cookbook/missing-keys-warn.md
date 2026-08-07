# Detecting Missing Log Keys at Runtime

When using named placeholders like `{user}` in log messages with structured `kv` pairs, it's easy for a key to be missing — a typo in the key name, a refactoring that forgot to add it, or a code path that never set it. In the JSON encoder, `{name}` placeholders are output as literal text in the `msg` field with structured values as top-level JSON fields for downstream processing. The `warnOnMissingKeys` feature flags these gaps at runtime during development and testing.

> **Note:** The old `ConsoleAppenderDev`-based approach (with `expandPlaceholders` and
> `Throwable` stack traces) has been removed — see [ADR-009](../doc/adr/009-consoleappenderdev.md).
> This feature is being re-implemented in the `CustomJsonEncoder` / `JsonLogWriter` pipeline.
> See [cookbook/missing-keys-warn-plan.md](../cookbook/missing-keys-warn-plan.md) for the
> implementation plan.

## The Problem

```java
log.atInfo().kv("user", username).log("User {user} logged from {ip}");
//                                                    ^^^^^^^^^^
// The "ip" key was never provided — {ip} stays literal in JSON output,
// and without warnOnMissingKeys you might not notice until a downstream
// tool fails to parse it.
```

In the JSON log this becomes:
```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User alice logged from {ip}","user":"alice"}
```

The `{ip}` placeholder is silently left in the message. No visible warning.

## The Solution

Enable `warnOnMissingKeys` on the `CustomJsonEncoder` (configured inside a standard
Logback `ConsoleAppender` or `RollingFileAppender`). When a `{name}` placeholder has no
matching key-value pair, the encoder emits a `missingKeys` array field in the JSON output:

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User alice logged from {ip}","user":"alice","missingKeys":["ip"]}
```

Downstream log aggregators (Elasticsearch, Loki, etc.) can filter and alert on
`"missingKeys"`, making typos and regressions visible even in production if desired —
though it is typically enabled only in development and staging.

## How It Works

The `CustomJsonEncoder` delegates to `JsonLogWriter.writeJsonEvent()`
(`logback/.../JsonLogWriter.java:73`). Missing-key detection happens during
JSON serialization:

1. **Scans** `event.getMessage()` — the raw message template (before SLF4J/ Logback
   formatting) — for `{name}` placeholders
2. **Ignores** empty `{}` and numeric `{0}`, `{1}` (positional arguments)
3. **Checks** each named placeholder against the set of kv keys already collected
   in the `allKeys` set (`JsonLogWriter.java:93`)
4. **If** `warnOnMissingKeys=true` and any keys are missing:
   - Writes a `"missingKeys"` JSON array field listing all missing key names

The check uses only kv pairs added via `kv()` / `addKeyValue()` — it does **not**
check MDC entries. Null-valued kv pairs are not flagged as missing; only keys with
no entry at all trigger the warning.

## Output Examples

### With `warnOnMissingKeys=false` (default)

No `missingKeys` field is emitted:
```json
{"ts":1748765696789,"level":"INFO","logger":"c.e.MyClass","thread":"main","msg":"User alice logged from {ip}","user":"alice"}
```

### With `warnOnMissingKeys=true`

A `missingKeys` array appears when a placeholder has no matching kv key:
```json
{"ts":1748765696789,"level":"INFO","logger":"c.e.MyClass","thread":"main","msg":"User alice logged from {ip}","user":"alice","missingKeys":["ip"]}
```

### Multiple missing keys

If several keys are missing, they are all listed:

```java
log.atInfo().log("User {user} from {ip} with role {role}");
```

```json
{"ts":1748765696789,"level":"INFO","logger":"c.e.MyClass","thread":"main","msg":"User {user} from {ip} with role {role}","missingKeys":["user","ip","role"]}
```

### Null values are ok

A key that exists in the kv pairs but has a `null` value is **not** flagged as missing — only keys with no entry at all trigger the warning:

```java
log.atInfo().kv("user", null).log("User {user} from {ip}");
// "user" has a null value — that's fine
// "ip" has no entry at all — that triggers the warning
```

```json
{"ts":1748765696789,"level":"INFO","logger":"c.e.MyClass","thread":"main","msg":"User null from {ip}","user":null,"missingKeys":["ip"]}
```

## When to Use This

| Scenario | `warnOnMissingKeys` |
|----------|-------------------|
| Production | `false` (default) — no overhead |
| Local development | `true` — catch typos immediately |
| Staging / QA | `true` — catch regressions before prod |
| Code review / refactor | `true` — verify all placeholders have keys |

## Configuration in `logback.xml`

```xml
<configuration scan="true" scanPeriod="30 seconds">

    <!-- Console appender with missing key detection -->
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="hr.hrg.dialog.logback.CustomJsonEncoder">
            <includeMDC>true</includeMDC>
            <includeKeys>true</includeKeys>
            <warnOnMissingKeys>true</warnOnMissingKeys>
        </encoder>
    </appender>

    <root level="DEBUG">
        <appender-ref ref="JSON" />
    </root>

</configuration>
```

Since `scan="true"` is set, you can toggle `warnOnMissingKeys` at runtime by editing
`logback.xml` — no restart needed.
