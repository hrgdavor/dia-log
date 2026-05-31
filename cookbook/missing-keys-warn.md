# Detecting Missing Log Keys at Runtime

When using named placeholders like `{user}` in log messages with structured `kv` pairs, it's easy for a key to be missing — a typo in the key name, a refactoring that forgot to add it, or a code path that never set it. In production, the JSON appender keeps the literal `{user}` in the message and you might not notice until a downstream tool fails to parse it. The dev appender has a built-in safety net for this.

## The Problem

```java
log.atInfo().kv("user", username).log("User {user} logged from {ip}");
//                                                    ^^^^^^^^^^
// The "ip" key was never provided — {ip} stays literal in JSON output,
// and in the dev appender it just shows as {ip} with no indication it's wrong.
```

In the JSON log this becomes:
```json
{"msg":"User alice logged from {ip}","kv":{"user":"alice"}}
```

The `{ip}` placeholder is silently left in the message. In the dev console it just shows as `{ip}` — easy to miss.

## The Solution

Enable `warnOnMissingKeys` on the `ConsoleAppenderDev` appender. When a `{name}` placeholder has no matching key-value pair, the appender:

1. Appends `⚠ MISSING KEYS: ip` to the log line
2. Writes a `Throwable` stack trace showing exactly where the log was called from

```xml
<appender name="DEV" class="hr.hrg.dialog.logback.ConsoleAppenderDev">
    <expandPlaceholders>true</expandPlaceholders>
    <warnOnMissingKeys>true</warnOnMissingKeys>
</appender>
```

This is opt-in — keep it off in production, enable it when hunting missing keys or during development.

## Output Examples

### With `warnOnMissingKeys=false` (default)

```
12:34:56.789 [main] INFO  c.e.MyClass - User alice logged from {ip}
```

The placeholder stays as-is. No warning.

### With `warnOnMissingKeys=true`

```
12:34:56.789 [main] INFO  c.e.MyClass - User alice logged from {ip} ⚠ MISSING KEYS: ip
java.lang.Throwable: Missing keys: ip
	at com.example.MyClass.login(MyClass.java:27)
	at com.example.Main.main(Main.java:12)
	...
```

Two things happen:
1. The log line gets a visible `⚠ MISSING KEYS: ip` suffix
2. A `Throwable` stack trace is written immediately after, showing the exact call site

### Multiple missing keys

If several keys are missing, they're all listed:

```java
log.atInfo().log("User {user} from {ip} with role {role}");
```

```
12:34:56.789 [main] INFO  c.e.MyClass - User {user} from {ip} with role {role} ⚠ MISSING KEYS: user, ip, role
java.lang.Throwable: Missing keys: user, ip, role
	at com.example.MyClass.loadProfile(MyClass.java:42)
	...
```

### Null values are ok

A key that exists in the `kv` pairs but has a `null` value is **not** flagged as missing — only keys with no entry at all trigger the warning:

```java
log.atInfo().kv("user", null).log("User {user} from {ip}");
// "user" has a null value — that's fine
// "ip" has no entry at all — that triggers the warning
```

```
12:34:56.789 [main] INFO  c.e.MyClass - User null from {ip} ⚠ MISSING KEYS: ip
java.lang.Throwable: Missing keys: ip
	...
```

## How It Works

The `ConsoleAppenderDev` processes the message template during output:

1. It scans for `{name}` placeholders (ignoring empty `{}` and numeric `{0}`)
2. For each named placeholder, it looks up the key in the event's key-value pairs
3. If the key is missing (no entry at all, not just null), it tracks it as missing
4. After writing the message line, if `warnOnMissingKeys=true` and there are missing keys:
   - Appends ` ⚠ MISSING KEYS: key1, key2` to the output
   - Creates a `new Throwable("Missing keys: key1, key2")` and writes its stack trace

The throwable is written directly to the output stream — it doesn't modify the `ILoggingEvent`, so it only affects the dev console output. The JSON appender is unaffected.

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

    <!-- Dev appender with missing key detection -->
    <appender name="DEV" class="hr.hrg.dialog.logback.ConsoleAppenderDev">
        <expandPlaceholders>true</expandPlaceholders>
        <warnOnMissingKeys>true</warnOnMissingKeys>
    </appender>

    <!-- JSON appender for production (no missing key detection — keeps literals) -->
    <appender name="JSON" class="hr.hrg.dialog.logback.ConsoleAppenderJson">
        <includeMDC>true</includeMDC>
        <includeKeys>true</includeKeys>
    </appender>

    <!-- Use both in development, JSON only in production -->
    <root level="DEBUG">
        <appender-ref ref="DEV" />
        <appender-ref ref="JSON" />
    </root>

</configuration>
```

Since `scan="true"` is set, you can toggle `warnOnMissingKeys` at runtime by editing `logback.xml` — no restart needed.