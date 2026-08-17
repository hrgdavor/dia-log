package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Predicate;

public class JsonAppender extends OutputStreamAppender<ILoggingEvent> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutputStream activeStream;
    private JsonLogWriter jsonLogWriter = new JsonLogWriter();


    @Override
    public void setOutputStream(OutputStream outputStream) {
        this.activeStream = outputStream;
        super.setOutputStream(outputStream);
    }

    /**
     * Configures the predicate used to filter stack trace frames during fingerprinting.
     * <p>
     * The value must be the fully-qualified name of a class that implements
     * {@link Predicate}{@code <String>} and has a public no-arg constructor. The predicate is
     * fed the fully-qualified class name of each stack frame; frames rejected by it are excluded
     * from both the written {@code stack} field and the {@code errHash} fingerprint.
     * <p>
     * Example logback.xml:
     * <pre>{@code
     * <appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
     *     <stackTraceFilter>com.example.MyFrameFilter</stackTraceFilter>
     * </appender>
     * }</pre>
     *
     * @param filterClassName fully-qualified class name of a {@code Predicate<String>} implementation
     */
    public void setStackTraceFilter(String filterClassName) {
        jsonLogWriter.setStackTraceFilter(instantiateStackTraceFilter(filterClassName));
    }

    /**
     * Instantiates a {@code Predicate<String>} from a fully-qualified class name.
     * A {@code null} or blank value resets to the default accept-all predicate.
     */
    static Predicate<String> instantiateStackTraceFilter(String filterClassName) {
        if (filterClassName == null || filterClassName.isBlank()) {
            return cls -> true;
        }
        try {
            Class<?> clazz = Class.forName(filterClassName);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Predicate<?> predicate)) {
                throw new IllegalArgumentException(
                        "Class " + filterClassName + " does not implement java.util.function.Predicate<String>");
            }
            @SuppressWarnings("unchecked")
            Predicate<String> typed = (Predicate<String>) predicate;
            return typed;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "Cannot instantiate stackTraceFilter class " + filterClassName, e);
        }
    }

    /**
     * Writes the logging event to the currently active output stream.
     * <p>
     * {@code activeStream} is intentionally non-volatile. The field is copied to a local variable
     * at method entry so that the entire log line (event payload + newline) is written to the same
     * stream snapshot. Concurrent changes to {@code activeStream} (e.g. during rollover) do not
     * split a single event across two streams.
     */
    @Override
    protected void writeOut(ILoggingEvent event) throws IOException {
        var activeStreamLoc = activeStream;
        jsonLogWriter.writeJsonEvent(objectMapper, event, activeStreamLoc);
        activeStreamLoc.write(JsonLogWriter.NL);
    }

}
