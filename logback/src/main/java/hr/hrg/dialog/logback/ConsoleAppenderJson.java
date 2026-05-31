package hr.hrg.dialog.logback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

/**
 * Logback {@link ConsoleAppender} that writes log events as JSON lines to the console.
 * <p>
 * Delegates JSON serialization to {@link JsonLogWriter} so the same output format
 * can be used by other appenders ({@link RollingFileAppenderJson}, etc.).
 * </p>
 * <p>
 * Supports runtime configuration via {@code logback.xml} — changes to properties
 * are picked up when Logback scans for config changes ({@code <configuration scan="true">}).
 * </p>
 *
 * <pre>{@code
 * <appender name="JSON" class="hr.hrg.dialog.logback.ConsoleAppenderJson">
 *     <includeMDC>true</includeMDC>
 *     <includeSource>false</includeSource>
 *     <includeKeys>true</includeKeys>
 *     <prettyPrint>false</prettyPrint>
 *     <customFields>{"env":"prod","version":"1.0"}</customFields>
 * </appender>
 * }</pre>
 */
public class ConsoleAppenderJson<E> extends ConsoleAppender<E> {

    /** Shared JSON writer that does the actual serialization. */
    private final JsonLogWriter jsonWriter = new JsonLogWriter();

    public ConsoleAppenderJson() {
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