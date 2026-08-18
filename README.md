# Dia-Log

A diagnostic logging library for SLF4J 2.x and Java 25+. It provides structured JSON logging, contextual key/value pairs, and deterministic stack-trace hashing for downstream aggregation.

## Modules

| Module                | Artifact          | Description                                                                                                       |
| --------------------- | ----------------- | ----------------------------------------------------------------------------------------------------------------- |
| [`core`](core/)       | `dia-log-core`    | `DiaLogger`, `LoggingEventBuilderWrapperBase`, `LoggingEventBuilderWrapperNoop`, `JavaStackSanitizer`, `Wyhash64` |
| [`logback`](logback/) | `dia-log-logback` | `JsonAppender`, `JsonAppenderRolling`, `JsonLogWriter`, `JsonLogWriterClassic`                                    |
| [`example`](example/) | `dia-log-example` | Runnable demo with a sample Logback configuration                                                                 |

## Quick Start

Use the builder API directly:

```java
// Structured logging with key/value pairs
log.atInfo().kv("userId", id).kv("action", "login")
    .log("User {userId} performed {action}");

// Conditional stack trace — clean message in production, stack when TRACE is enabled
log.atDebug().stackWhenTraceEnabled().kv("state", state)
    .log("Change state to {state}");
```

The current JSON writer emits flat top-level fields such as `ts`, `level`, `logger`, `thread`, `msg`, and your key/value pairs. When an exception is present, the writer emits `errClass`, `errMessage`, `stack`, and `errHash`.

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User {userId} performed {action}","userId":42,"action":"login"}
```

## Features

- **Structured key/value logging** — `kv()` is a shorthand for statement-scoped key/value pairs.
- **Logback integration** — the logback module exposes `JsonAppender` and `JsonAppenderRolling`, both backed by `JsonLogWriter`.
- **Conditional stack visibility** — `stackWhenTraceEnabled()` attaches a synthetic throwable only when TRACE is enabled.
- **Deterministic stack traces** — `JavaStackSanitizer` normalizes frames and produces a stable fingerprint for `errHash`. The logback appenders can exclude noisy/framework frames from fingerprinting via a configurable `<stackTraceFilter>` predicate (see [Filtering stack-trace frames](logback/README.md#filtering-stack-trace-frames-during-fingerprinting)).
- **Generic builder pattern** — `LoggingEventBuilderWrapperBase` keeps fluent chaining intact for subclasses and no-op wrappers.

## Performance Optimizations

Dia-Log is built around a simple goal: **emit structured JSON as fast as possible while creating as few objects as possible**. Logging sits on the hot path of every request, and every allocation adds pressure on the garbage collector. The optimizations below are a best-effort engineering effort to keep both latency and GC pressure low — and the project remains open to suggestions for further improvements.

### Reducing allocations across the hot path

The `JsonLogWriter` hot path is aggressively tuned to avoid allocation:

- **Pre-encoded key bytes** — Common field names (`"ts":`, `"level":`, `"logger":`, `"msg":`, etc.) and literals (`true`, `false`, `null`) are stored once as `byte[]` constants, so writing a field name never allocates a new byte array per event.
- **Direct string data access** — With `--add-opens java.base/java.lang=ALL-UNNAMED`, `StringByteExtractor` uses `VarHandle` to read a `String`'s backing `byte[]` and `coder` directly, streaming UTF-8 bytes to the output without an intermediate `String.getBytes()` array. When `--add-opens` is unavailable it transparently falls back to the classic path, so behavior is identical either way.
- **Low-allocation stack-trace writing** — `JavaStackSanitizer` writes stack frames straight to the output stream instead of building intermediate strings, dramatically reducing allocation for throwable-heavy events.

### Sidestepping Jackson for top-level values

`JsonLogWriter` writes the well-known top-level fields (`ts`, `level`, `logger`, `thread`, `msg`, and the `err*`/`stack` fields) **directly to the `OutputStream`**, bypassing Jackson entirely. Jackson is only invoked for the *generic* key/value and MDC values that Dia-Log cannot predict ahead of time.

This matters because the predictable fields make up most of every event's output. By writing them with hand-tuned, allocation-free code instead of routing them through a general-purpose serializer, Dia-Log avoids Jackson's per-field machinery (generator state, intermediate buffers, and internal objects) for the common case — while still delegating the genuinely arbitrary values to Jackson for correctness. The result is measurably lower latency and dramatically lower allocation than a full Jackson serialization of the same payload.

### Number writing without intermediate strings

Numeric fields are written with `JsonNumberWriter`, which converts `int`/`long`/`double`/`float` **directly into the output stream** using a precomputed ASCII lookup table for digit pairs (00–99). It never builds a `String` or an intermediate `byte[]` for the number — digits are computed and emitted straight to the output. This avoids both the `String.valueOf(...)` allocation and the transient UTF-8 encoding step a naive implementation would introduce.

### Buffer and streaming-hash object reuse

- **Reusable number buffers** — Each `JsonLogWriter` instance owns reusable `int`/`long` scratch buffers (`intNumberBuffer`, `longNumberBuffer`) that are filled and written per event, so no per-number buffer is allocated.
- **Streaming hash reuse** — `Wyhash64.Streaming` is designed to be reset and reused (`reset(...)`) on the hot path instead of creating a fresh hasher per event, keeping hashing allocation-free. The fingerprint entry points (`fingerprint(...)`, `addFromTraceToOutputStream*AndFingerprint(...)`) take a caller-supplied hasher as a parameter and reset it internally — there are no no-stream convenience overloads and no hidden `ThreadLocal` state. `JsonLogWriter` owns its hasher as a plain field, exactly like its number buffers.

### Single-pass hash + write

Stack-trace fingerprinting and writing used to be two separate passes (traverse the trace twice). Dia-Log now offers **single-pass** APIs (`addFromTraceToOutputStreamJsonAndFingerprint(...)` and friends) that compute the `errHash` fingerprint *while* writing the stack JSON in one traversal. `JsonLogWriter` uses these, so a throwable event is serialized and fingerprinted in a single pass over the trace instead of two — cutting both CPU work and allocation.

### Measured impact

These optimizations are validated with JMH benchmarks (`-prof gc`). On the latest run (2026-08-18, JDK 25, AMD Ryzen 9 7945HX), `JsonLogWriter` beats the classic generator path in both latency and allocation:

| Benchmark method                | includeThrowable | Avg time    | Alloc norm |
| ------------------------------- | ---------------- | ----------- | ---------- |
| `writeWithJsonLogWriter`        | false            | 0.507 us/op | 272 B/op   |
| `writeWithJsonLogWriterClassic` | false            | 0.610 us/op | 784 B/op   |
| `writeWithJsonLogWriter`        | true             | 5.706 us/op | 272 B/op   |
| `writeWithJsonLogWriterClassic` | true             | 6.218 us/op | 872 B/op   |

Allocation summary of the dedicated allocation benchmark (`AllocationBenchmark` /
`JsonLogWriterDevBenchmark`, `-prof gc`, fast paths with `--add-opens`):

| Path                                                                       | Alloc norm    |
| -------------------------------------------------------------------------- | ------------- |
| `Wyhash64.hash` — String (Latin-1/UTF-16), char[], byte[]                  | 0 B/op        |
| `Wyhash64.Streaming` — reused hasher, `finalHash()`                        | 0 B/op        |
| `EscapedJsonStringWriter`, `StringByteExtractor`, `JsonNumberWriter` (Ryu) | 0 B/op        |
| `JsonLogWriter` — no KV                                                    | 0 B/op        |
| `JsonLogWriter` — with KV (no MDC)                                         | 0 B/op        |
| `JsonLogWriter` — throwable event                                          | 0 B/op        |
| `fingerprint(Throwable, …)` — caller-owned reusable hasher                 | 88 B/op        |
| `JsonLogWriterDev` (missing-key reporting, dev-only)                       | 256–464 B/op  |

In short, allocation is avoided across the hot path by:

- **Pre-encoding** field names and JSON literals as `byte[]` constants — writing a field never builds a byte array.
- **Direct string access** — `StringByteExtractor` / `EscapedJsonStringWriter` stream a `String`'s backing bytes straight to the output via `VarHandle` (with `--add-opens`), skipping `getBytes()`.
- **Writing numbers without strings** — `JsonNumberWriter` (Ryu for float/double, digit-pair table for int/long) emits digits directly into reusable scratch buffers, never via `String.valueOf(...)`.
- **Reusing stateful helpers** — `Wyhash64.Streaming` and the single-pass stack write+fingerprint compute `errHash` in one traversal with a caller-owned reusable hasher (passed as a parameter, never hidden in a `ThreadLocal`); `StringHashSet` (a resettable key-dedup set) is cleared per event instead of reallocated.
- **Lazy allocation** — the KV/MDC dedup set is created only when MDC is actually present, and disabled log levels route through a singleton no-op builder.

Full results, the allocations found and removed (lazy `allKeys`, caller-owned reusable
hasher), and the documented remaining allocations: [Allocation Benchmark Results](doc/allocation-benchmark-results.md).

A head-to-head of logback writing paths — stock pattern encoder vs the optimized JSON
writer vs a Jackson-generator encoder, with and without traces — is in
[Logback Writer Comparison](doc/logback-writer-comparison-benchmark-results.md): the
optimized writer allocates a constant 208 B/op whether or not a throwable is attached,
while the default pattern encoder jumps from 728 to ~12–15 KB/op when rendering a trace.

### Current best effort, open to improvement

This is the **current best effort** — a set of practical trade-offs that favor the common logging hot path. It is not claimed to be optimal. The project is open to suggestions for further improvements, whether that means new allocation-avoidance techniques, different serialization strategies, or better benchmark methodology.

For a detailed, curated history of these optimizations — the step-by-step changes, the benchmark methodology lessons, and the historical gains — see [Benchmark Optimization History](doc/benchmark-optimization-history.md).

## Cookbook

Practical guides for common logging patterns:

- [**Error-Only Log File**](cookbook/additional.error-only.log.md) — Separate ERROR logs into their own file with a `ThresholdFilter` and rolling policy.
- [**Stack When Trace**](cookbook/stackWhenTrace.md) — Conditional call-stack visibility using `stackWhenTraceEnabled()` and the current JSON field layout.
- [**Missing Keys Warning**](cookbook/missing-keys-warn.md) — Notes the current state of missing-key detection in this checkout.

## Additional Documentation

- [Stack Trace Sanitizer and Derivatives](doc/java-stack-trace-sanitizer-and-derivatives.md) — Canonical sanitizer behavior and the relationship between `JavaStackTraceWriter`, `JavaStackSanitizerLogback`, and `JavaStackWriterLogback`.

## Example Logback Configuration

The current logback module is configured with standard Logback appenders and the JSON writer that ships with the library. A simple example looks like this:

```xml
<configuration scan="true">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
            <pattern>%msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON" />
    </root>
</configuration>
```

If you want to use the library's custom JSON writer directly, the repository also contains `JsonAppender` and `JsonAppenderRolling`, which write JSON events using `JsonLogWriter`.

## XZ Compression of Rotated Logs

Logback 1.5.18+ has **native XZ compression support** for rotated log files. When a
rolling policy's `fileNamePattern` ends with `.xz`, Logback automatically compresses
the rotated file using its built-in `XZCompressionStrategy` (which uses the
`org.tukaani.xz` library).

The `dia-log-logback` module already bundles the `org.tukaani:xz` dependency, so no
extra dependency is needed when using this module. If you use Logback directly
(without `dia-log-logback`), add the dependency yourself:

```xml
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.12</version>
</dependency>
```

### Example

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
    <file>logs/app.jsonl</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.jsonl.xz</fileNamePattern>
        <maxFileSize>10MB</maxFileSize>
        <maxHistory>30</maxHistory>
        <totalSizeCap>2GB</totalSizeCap>
    </rollingPolicy>
</appender>
```

No `<encoder>` is needed — the appenders write JSON directly and install a no-op encoder
themselves when none is configured.

### Notes

- The active `<file>` should **not** end in `.xz` — it is the uncompressed, currently
  written log file. Only the archived `fileNamePattern` uses the `.xz` suffix.
- If the `org.tukaani:xz` library is missing from the classpath, Logback logs a
  warning and falls back to GZIP compression (replacing the `.xz` suffix with `.gz`).
- XZ offers better compression ratios than GZIP but is slower to compress. Consider
  asynchronous logging if compression latency matters.

## Reading Logs with `jlx`

Dia-Log writes JSON Lines, so you can read, filter, and analyze the output with [`jlx`](https://github.com/hrgdavor/zig-jlx) — a fast command-line utility for structured JSON logs.

`jlx` is configured via an INI-style config file, so you can define an output format (and named profiles) tailored to Dia-Log's field layout (`ts`, `level`, `logger`, `thread`, `msg`, plus your key/value pairs and `errClass`/`errMessage`/`stack`/`errHash`).

### Example config (`dia-log.conf`)

```ini
; jlx config tailored to Dia-Log JSON output
[folders]
paths     = /path/to/your/logs
timestamp = ts
level     = level
message   = msg
thread    = thread
logger    = logger
trace     = stack
output    = {timestamp:datetime} [{level:6}] {logger} | {message}
; Expand {name} placeholders in msg using the line's key/value pairs
message_expand = curly

[profile.verbose]
output = {timestamp:datetime} [{level:6}] {logger} {thread} | {message}
; Show only ERROR/WARN lines and anything with an exception hash
include = level:ERROR, level:WARN, errHash

[profile.errors]
output = {timestamp:datetime} [{level:6}] {message}
include = level:ERROR
exclude = healthcheck
```

### Usage

```bash
# Basic formatted output
jlx -c dia-log.conf app.log

# Follow a live log as lines are appended
jlx -c dia-log.conf -f app.log

# Use the "errors" profile to focus on errors
jlx -c dia-log.conf -p errors app.log

# Filter to a time window (e.g. morning)
jlx -c dia-log.conf -r "08:00..09:30" app.log

# Show only lines with a given key/value pair
jlx -c dia-log.conf -i "component:order-service" app.log

# List all unique levels / error hashes
jlx -c dia-log.conf -v level app.log
jlx -c dia-log.conf -v errHash app.log

# Pipe from another source (e.g. kubectl logs)
kubectl logs my-pod | jlx -c dia-log.conf

# Start the interactive web workbench
jlx -c dia-log.conf --serve app.log
```

Because `jlx` treats the first `{` on a line as the start of JSON, it works even if your appenders prefix lines with application text. See the [`zig-jlx` README](https://github.com/hrgdavor/zig-jlx) for the full option and config reference.

### `{key}` placeholders in the message

Dia-Log lets you reference a value from a key/value pair or the MDC inside the message with `{key}` syntax, so the value is logged both **structurally** (as its own top-level JSON field) and **in the message**:

```java
log.atInfo()
    .kv("method", "GET")
    .kv("path", "/api/users")
    .kv("statusCode", 200)
    .log("Request {method} {path} -> {statusCode}");

// MDC entries work the same way:
MDC.put("userId", "alice");
log.atInfo().log("User {userId} logged in");
```

The `{key}` tokens are written into the JSON `msg` field **as-is** (not interpolated at log time), so the stored event stays compact and queryable:

```json
{"ts":1748765696789,"level":"INFO","msg":"Request {method} {path} -> {statusCode}","method":"GET","path":"/api/users","statusCode":200}
```

To see the expanded message when tailing/displaying with `jlx`, set `message_expand = curly` (as in the config above). `jlx` then interpolates the `{key}` tokens from the other JSON fields on the same line — without modifying the raw log file:

```
2025-06-01 12:34:56   INFO | Request GET /api/users -> 200
```

Placeholders that reference a key absent from a given line are left intact, so no data is lost. A runnable demo of this pattern (plus a ready-made `jlx.conf`) lives in the [`example`](example/) module.

## Build

```bash
mvn clean install
```

## Publishing

`dia-log-core` and `dia-log-logback` are published to Maven Central via the
Central Portal. The `dia-log-example` module is not published. See
[`PUBLISHING.md`](PUBLISHING.md) for the full instructions and a GitHub Actions
workflow for automated releases.

```bash
# One-time release (requires GPG key + Central Portal token in ~/.m2/settings.xml)
mvn clean deploy -DskipTests
```

## Requirements

- Java 25+
- SLF4J 2.0.18
- Logback 1.5.38 (for the logback module)