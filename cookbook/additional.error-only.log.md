# Separating Errors Into Their Own Log File

This is a very common and highly recommended pattern for production environments—it gives you a detailed "everything" log for debugging while maintaining a clean, high-priority "errors-only" log for quick troubleshooting and alerting. To achieve this in Logback, you define two separate `RollingFileAppender` beans and attach them both to your `<root>` logger. Crucially, you use a `ThresholdFilter` on the error appender to block everything below the `ERROR` level, so only events at `ERROR` or `FATAL` reach that file. The main log receives all levels (`INFO`, `WARN`, `ERROR`) and serves as your full-detail archive, while the error log stays small, focused, and can be retained longer (e.g. 60 days vs 30) because it grows much slower. GZIP compression via `.gz` in the `fileNamePattern` applies **only to rolled/archived files** — the active file configured in `<file>` (e.g. `logs/errors.log`) is always uncompressed and written to in real time. On rollover, Logback renames the active file to match the `fileNamePattern` and compresses it to `.gz` before starting a fresh active file. This means you can `tail -f logs/errors.log` to watch new errors appear live, while old rotated files sit compressed in the archive directory, dramatically reducing disk usage.

```xml
<configuration>

    <appender name="MAIN_LOG" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/archived/application-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="ERROR_LOG" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/errors.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>ERROR</level>
        </filter>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/archived/errors-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>60</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="MAIN_LOG" />
        <appender-ref ref="ERROR_LOG" />
    </root>

</configuration>
```

Since compression applies only to the archived files (not the active one), you can search them directly on Linux without decompressing first using `zgrep`. For the text-based log, finding all ERROR entries across all archived files is as simple as `zgrep "ERROR" logs/archived/errors-*.log.gz`. For the JSON log, you can search within specific fields using `zgrep` combined with JSON-aware tools: `zgrep '"level":"ERROR"' logs/archived/errors-*.json.gz` finds all error-level JSON lines across the full archive, or pipe through `jq` for more targeted queries like `zcat logs/archived/errors-2026-05-31.0.json.gz | jq 'select(.kv.orderId != null) | {msg, kv}'` to extract only messages that contain a specific structured key. This makes it practical to keep months of compressed history online and still answer operational questions in seconds.

The same pattern works with `RollingFileAppenderJson` from this library, giving you structured JSON in both the main log and the error-only file. Instead of a text encoder, you configure the JSON writer properties (`includeMDC`, `includeKeys`, etc.) on both appenders, and the error log still uses a `ThresholdFilter` to accept only `ERROR` and above. This way your full-detail JSON log and your error-only JSON log share the identical schema—every field (`ts`, `level`, `logger`, `msg`, `kv`, `ctx`, `err`) is present—so you can feed the error log into Elasticsearch, Loki, or any log aggregator with the same parsing rules, while the main log retains all lower-severity events for deeper forensic analysis.

```xml
<configuration>

    <appender name="MAIN_LOG" class="hr.hrg.dialog.logback.RollingFileAppenderJson">
        <file>logs/application.json</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/archived/application-%d{yyyy-MM-dd}.%i.json.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
        <includeMDC>true</includeMDC>
        <includeKeys>true</includeKeys>
    </appender>

    <appender name="ERROR_LOG" class="hr.hrg.dialog.logback.RollingFileAppenderJson">
        <file>logs/errors.json</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>ERROR</level>
        </filter>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/archived/errors-%d{yyyy-MM-dd}.%i.json.gz</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>60</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
        <includeMDC>true</includeMDC>
        <includeKeys>true</includeKeys>
        <customFields>{"logType":"error-only"}</customFields>
    </appender>

    <root level="INFO">
        <appender-ref ref="MAIN_LOG" />
        <appender-ref ref="ERROR_LOG" />
    </root>

</configuration>