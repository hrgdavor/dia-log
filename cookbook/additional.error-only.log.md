# Separating Errors Into Their Own Log File

This is a common production pattern: keep a full-detail log for debugging, while routing only `ERROR` and above to a smaller, high-priority error log. The same rolling-policy pattern works for text logs and for the JSON output emitted by this module.

The core idea is simple:

- a main rolling appender accepts all events
- a second rolling appender uses a `ThresholdFilter` so only `ERROR` and above are written to the error file
- the error file can be retained longer and searched separately from the main log

For the JSON path, the current implementation writes flat top-level fields such as `ts`, `level`, `logger`, `msg`, your key/value pairs, and (for exceptions) `errClass`, `errMessage`, `stack`, and `errHash`. That means the schema is slightly different from the older examples that showed a nested `err` object.

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
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="MAIN_LOG" />
        <appender-ref ref="ERROR_LOG" />
    </root>

</configuration>
```

The same approach works for the JSON output written by this module. The current writer emits the values as plain fields, so queries against the error-only log should use the current field names such as `errClass`, `errMessage`, `stack`, and `errHash` rather than the older nested `err.*` schema.