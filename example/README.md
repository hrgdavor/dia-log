# Dia-Log Example

This module is a runnable demo that shows how to use Dia-Log with SLF4J 2.x and Logback to produce structured JSON logging output.

## What It Demonstrates

- Basic logging at all levels (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`)
- Structured key/value pairs via the fluent `kv()` API
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

### Example Output

A simple key/value log line looks like:

```json
{"ts":1748765696789,"level":"INFO","logger":"hr.hrg.dialog.example.Main","thread":"main","msg":"Request completed","method":"GET","path":"/api/users","statusCode":200,"durationMs":42}
```

An exception log line additionally includes `errClass`, `errMessage`, `stack`, and `errHash`.

### stackWhenTraceEnabled()

`stackWhenTraceEnabled()` attaches a synthetic throwable only when TRACE is enabled. With the default `DEBUG` root level, no stack is emitted. Switch the root logger to `TRACE` in `logback.xml` to see the call stack appear as `errClass` / `errHash`.
