package hr.hrg.dialog.logback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;

/**
 * Logback {@link RollingFileAppender} that writes log events as JSON lines to a rolling file.
 * <p>
 * Delegates JSON serialization to {@link JsonLogWriter}, sharing the same configuration
 * and output format as {@link ConsoleAppenderJson}.
 * </p>
 *
 * <pre>{@code
 * <appender name="FILE" class="hr.hrg.dialog.logback.RollingFileAppenderJson">
 *     <file>logs/app.log</file>
 *
 *     <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
 *         <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
 *         <maxHistory>30</maxHistory>
 *     </rollingPolicy>
 *
 *     <includeMDC>true</includeMDC>
 *     <includeKeys>true</includeKeys>
 *     <includeSource>false</includeSource>
 *     <prettyPrint>false</prettyPrint>
 *     <customFields>{"env":"prod","version":"1.0"}</customFields>
 * </appender>
 * }</pre>
 */
public class RollingFileAppenderJson<E> extends RollingFileAppender<E> {

    /** Shared JSON writer that does the actual serialization. */
    private final JsonLogWriter jsonWriter = new JsonLogWriter();

    public RollingFileAppenderJson() {
    }

    // ========== Delegated configuration properties ==========

    public void setIncludeMDC(boolean includeMDC) {
        jsonWriter.setIncludeMDC(includeMDC);
    }

    public boolean isIncludeMDC() {
        return jsonWriter.isIncludeMDC();
    }

    public void setIncludeKeys(boolean includeKeys) {
        jsonWriter.setIncludeKeys(includeKeys);
    }

    public boolean isIncludeKeys() {
        return jsonWriter.isIncludeKeys();
    }

    public void setIncludeSource(boolean includeSource) {
        jsonWriter.setIncludeSource(includeSource);
    }

    public boolean isIncludeSource() {
        return jsonWriter.isIncludeSource();
    }

    public void setPrettyPrint(boolean prettyPrint) {
        jsonWriter.setPrettyPrint(prettyPrint);
    }

    public boolean isPrettyPrint() {
        return jsonWriter.isPrettyPrint();
    }

    /**
     * Set custom static JSON fields to include in every event.
     * Expected format: a JSON object string like {@code {"env":"prod","version":"1.0"}}.
     */
    public void setCustomFields(String customFieldsJson) {
        jsonWriter.setCustomFields(customFieldsJson);
    }

    public String getCustomFields() {
        return jsonWriter.getCustomFields();
    }

    // ========== Lifecycle ==========

    @Override
    public void start() {
        jsonWriter.setContext(getContext());
        jsonWriter.start();
        super.start();
    }

    @Override
    public void stop() {
        jsonWriter.stop();
        super.stop();
    }

    // ========== Output ==========

    @Override
    protected void writeOut(E event) throws IOException {
        if (event instanceof ILoggingEvent le) {
            writeEvent(le);
        } else {
            // Non-Logback event: fall back to default behavior
            OutputStream out = getOutputStream();
            out.write(event.getClass().getName().getBytes(StandardCharsets.UTF_8));
            out.write(JsonLogWriter.NL);
            super.writeOut(event);
        }
    }

    private void writeEvent(ILoggingEvent event) throws IOException {
        streamWriteLock.lock();
        try {
            OutputStream out = getOutputStream();
            jsonWriter.writeJsonEvent(event, out);
            out.write(JsonLogWriter.NL);
            out.flush();
        } finally {
            streamWriteLock.unlock();
        }
    }
}