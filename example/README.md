# Dia-Log Example

This module is a runnable demo that shows how to use Dia-Log with SLF4J 2.x and Logback to produce structured JSON logging output.

## What It Demonstrates

- Basic logging at all levels (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`)
- Structured key/value pairs via the fluent `kv()` API
- `{key}` placeholders in the message that reference key/value pairs and MDC entries
- Conditional call-stack visibility with `stackWhenTraceEnabled()`
- Parameterized (SLF4J) log messages
- Exception logging, including nested causes
- MDC context propagation

## Build

From the repository root:

```bash
mvn clean install
```

This compiles the `core`, `logback`, and `example` modules.

## Run

The example jar does not bundle its dependencies, so run it with the project classes and dependency jars on the classpath. From the repository root:

```bash
mvn -q -pl example dependency:build-classpath -Dmdep.outputFile=example-cp.txt
```

Then:

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp "example/target/classes;core/target/classes;logback/target/classes;$(cat example-cp.txt)" \
  hr.hrg.dialog.example.Main
```

The example waits briefly on startup, then emits a series of JSON log lines to the console.

To pretty-print the JSON output through `jq`:

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED \
  -cp "example/target/classes;core/target/classes;logback/target/classes;$(cat example-cp.txt)" \
  hr.hrg.dialog.example.Main | jq .
```

## Configuration

The demo is configured by [`src/main/resources/logback.xml`](src/main/resources/logback.xml), which attaches a `JsonAppenderRolling` (rolling file) to the root logger.

Because `JsonAppender` extends `OutputStreamAppender`, its output stream must be assigned programmatically. The demo's `Main.main()` attaches a console `JsonAppender` writing to `System.out` via `attachConsoleJsonAppender()`.

Both appenders are backed by `JsonLogWriter`, which emits one JSON object per line (JSON Lines format). The root level is `DEBUG`.

For reading/tailing the generated logs, this module also ships a [`jlx.conf`](jlx.conf) that sets `message_expand = curly` so `{key}` placeholders in messages are expanded on display (see [`{key}` Placeholders](#key-placeholders-in-the-message)).

### JSON Field Layout

The current writer emits flat top-level fields:

| Field | Meaning |
|-------|---------|
| `ts` | Event timestamp (epoch millis) |
| `level` | Log level |
| `logger` | Logger name |
| `thread` | Thread name |
| `msg` | Formatted message |
| ... | Your key/value pairs, written as top-level fields |
| `errClass` | Exception class (when present) |
| `errMessage` | Exception message (when present) |
| `stack` | Sanitized stack-trace text (when present) |
| `errHash` | Deterministic 64-bit fingerprint for deduplication (when present) |

By default every stack frame contributes to `stack` and `errHash`. Both appenders accept a logback-configurable `<stackTraceFilter>` (a fully-qualified class name implementing `Predicate<String>`) to exclude frames from fingerprinting — see [Filtering stack-trace frames](../logback/README.md#filtering-stack-trace-frames-during-fingerprinting).

### Example Output

A simple key/value log line looks like:

```json
{"ts":1748765696789,"level":"INFO","logger":"hr.hrg.dialog.example.Main","thread":"main","msg":"Request completed","method":"GET","path":"/api/users","statusCode":200,"durationMs":42}
```

An exception log line additionally includes `errClass`, `errMessage`, `stack`, and `errHash`.

### stackWhenTraceEnabled()

`stackWhenTraceEnabled()` attaches a synthetic throwable only when TRACE is enabled. With the default `DEBUG` root level, no stack is emitted. Switch the root logger to `TRACE` in `logback.xml` to see the call stack appear as `errClass` / `errHash`.

## `{key}` Placeholders in the Message

Values you attach as key/value pairs (via `.kv(...)`) or as MDC entries can be referenced from the message with `{key}` syntax:

```java
log.atInfo()
    .kv("method", "GET")
    .kv("path", "/api/users")
    .kv("statusCode", 200)
    .log("Request {method} {path} -> {statusCode}");
```

Each value is still written as its **own** top-level JSON field, so the log stays machine-readable:

```json
{"ts":1748765696789,"level":"INFO","logger":"hr.hrg.dialog.example.Main","thread":"main","msg":"Request {method} {path} -> {statusCode}","method":"GET","path":"/api/users","statusCode":200}
```

The `{key}` tokens are written into the JSON `msg` field **as-is** — the message is not interpolated at log time. This keeps the stored event compact and queryable (you can filter on the structured `method`/`path`/`statusCode` fields), while the message template stays readable.

MDC entries work the same way. With `MDC.put("userId", "alice")`, you can write:

```java
log.atInfo().log("User {userId} logged in");
```

### Displaying the expanded message with `jlx`

To see the `{key}` tokens expanded to their values when tailing/displaying the log, use [`jlx`](https://github.com/hrgdavor/zig-jlx) with `message_expand = curly`. This module ships a ready-made [`jlx.conf`](jlx.conf):

```ini
[folders]
timestamp = ts
level     = level
message   = msg
output    = {timestamp:datetime} [{level:6}] {logger} | {message}
message_expand = curly
```

```bash
# Pretty-print the file with {key} placeholders expanded
jlx -c jlx.conf logs/example.jsonl

# Follow live output
jlx -c jlx.conf -f logs/example.jsonl
```

With `message_expand = curly`, the line above renders as:

```
2025-06-01 12:34:56   INFO hr.hrg.dialog.example.Main | Request GET /api/users -> 200
```

Without `message_expand`, the `{method}`, `{path}`, and `{statusCode}` tokens appear literally in the message. The raw JSON file is never modified — expansion happens only in `jlx`'s display output.
