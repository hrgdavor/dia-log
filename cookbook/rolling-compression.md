# Rolling-Policy Compression — Choosing the Right Codec

`JsonAppenderRolling` delegates compressed archive creation to Logback's built-in
rolling policies (`TimeBasedRollingPolicy`, `SizeAndTimeBasedRollingPolicy`). The
compression codec is selected entirely by the `fileNamePattern` suffix on your
`<rollingPolicy>`.

## Available Codecs

Logback recognizes the following suffixes out of the box:

| Suffix  | Strategy                 | Notes                                    |
|---------|--------------------------|------------------------------------------|
| `.gz`   | `GZCompressionStrategy`  | Default for most rollover patterns       |
| `.zip`  | `ZipCompressionStrategy`  | Each archive is a separate ZIP entry     |
| `.xz`   | `XZCompressionStrategy`  | Best compression ratio, slower throughput|

## XZ — The Compression-Ratio Champion

**XZ** uses LZMA2 and delivers the **best compression ratio** of the three built-in
options. For JSON logs, which are highly repetitive (repeated field names, timestamps
with similar structure, repeated key strings), XZ typically achieves 2–4× smaller
archives than GZIP at the default compression level.

The tradeoff is CPU: XZ is slower to compress and decompress. For rolling policies,
compression happens once at rollover on a background thread, so the latency cost is
usually acceptable unless you roll extremely frequently (sub-second intervals).

## Example — XZ with Time-Based Rotation

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
    <file>logs/app.jsonl</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.jsonl.xz</fileNamePattern>
        <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder>
        <pattern>%msg%n</pattern>
    </encoder>
</appender>
```

## Example — XZ with Size-and-Time Rotation

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
    <file>logs/app.jsonl</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.jsonl.xz</fileNamePattern>
        <maxFileSize>100MB</maxFileSize>
        <maxHistory>30</maxHistory>
        <totalSizeCap>2GB</totalSizeCap>
    </rollingPolicy>
    <encoder>
        <pattern>%msg%n</pattern>
    </encoder>
</appender>
```

## Dependency

Logback's `XZCompressionStrategy` requires `org.tukaani:xz` on the classpath. The
`dia-log-logback` module bundles this dependency transitively — no extra POM entry
is needed when using `JsonAppenderRolling`.

If you use Logback directly, add it explicitly:

```xml
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.12</version>
</dependency>
```

## Important Notes

- The active `<file>` must **not** end in `.xz` — it is the live, uncompressed
  stream. Only the `fileNamePattern` carries the compression suffix.
- If the `xz` library is missing from the classpath, Logback logs a warning and
  silently falls back to GZIP (swapping `.xz` for `.gz`).
- XZ compression level defaults to 6 (the library default). You cannot tune it via
  Logback's XML configuration; the built-in strategy uses `XZOutputStream` with
  default settings.
- When storage or egress cost dominates and compression latency is secondary, XZ is
  the recommended choice for JSON logs. For very high-throughput scenarios where
  rollover happens under load, GZIP remains a safe alternative.
