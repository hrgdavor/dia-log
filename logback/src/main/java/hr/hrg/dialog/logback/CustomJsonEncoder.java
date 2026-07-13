package hr.hrg.dialog.logback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;

/**
 * Custom Logback Encoder that outputs logging events as structured JSON lines.
 * This can be used cleanly inside both ConsoleAppender and RollingFileAppender.
 */
public class CustomJsonEncoder extends EncoderBase<ILoggingEvent> {

    private final JsonLogWriter jsonWriter = new JsonLogWriter();
    private static final byte[] NL = System.lineSeparator().getBytes(StandardCharsets.UTF_8);

    // ========== Delegated configuration properties for logback.xml ==========

    public void setIncludeMDC(boolean includeMDC) {
        jsonWriter.setIncludeMDC(includeMDC);
    }

    public void setIncludeKeys(boolean includeKeys) {
        jsonWriter.setIncludeKeys(includeKeys);
    }

    public void setIncludeSource(boolean includeSource) {
        jsonWriter.setIncludeSource(includeSource);
    }

    public void setPrettyPrint(boolean prettyPrint) {
        jsonWriter.setPrettyPrint(prettyPrint);
    }

    public void setCustomFields(String customFieldsJson) {
        jsonWriter.setCustomFields(customFieldsJson);
    }

    public void setMaxStackFrames(int maxStackFrames) {
        jsonWriter.setMaxStackFrames(maxStackFrames);
    }

    // ========== Encoder Lifecycle ==========

    @Override
    public void start() {
        // Link the Logback context down to the writer for error reporting
        jsonWriter.setContext(getContext());
        jsonWriter.start();
        super.start();
    }

    @Override
    public void stop() {
        jsonWriter.stop();
        super.stop();
    }

    // ========== Core Encoding Logic ==========

    @Override
    public byte[] encode(ILoggingEvent event) {
        // Initialize an initial buffer size to prevent aggressive resizing allocations
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        
        try {
            jsonWriter.writeJsonEvent(event, baos);
            baos.write(NL);
        } catch (IOException e) {
            addError("Failed to serialize log event to JSON for logger: " + event.getLoggerName(), e);
        }
        
        return baos.toByteArray();
    }

    @Override
    public byte[] headerBytes() {
        return null; // JSON lines log format does not use files headers
    }

    @Override
    public byte[] footerBytes() {
        return null; // JSON lines log format does not use file footers
    }
}