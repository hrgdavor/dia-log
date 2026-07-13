


configure encoder
```xml
<configuration scan="true">
    <!-- 1. Console Appender -->
    <appender name="CONSOLE_JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="hr.hrg.dialog.logback.CustomJsonEncoder">
            <includeMDC>true</includeMDC>
            <includeKeys>true</includeKeys>
            <includeSource>false</includeSource>
            <prettyPrint>false</prettyPrint>
            <customFields>{"env":"prod","version":"1.0"}</customFields>
        </encoder>
    </appender>

    <!-- 2. File Appender -->
    <appender name="FILE_JSON" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.jsonl</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/archive/application.%d{yyyy-MM-dd}.%i.jsonl.gz</fileNamePattern>
            <maxFileSize>50MB</maxFileSize>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder class="hr.hrg.dialog.logback.CustomJsonEncoder">
            <includeMDC>true</includeMDC>
            <includeKeys>true</includeKeys>
            <customFields>{"env":"prod","version":"1.0"}</customFields>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE_JSON" />
        <appender-ref ref="FILE_JSON" />
    </root>
</configuration>
```
