package hr.hrg.dialog.logback;

/**
 * Dev/diagnostic rolling-file appender backed by {@link JsonLogWriterDev}:
 * every event gets a {@code "missingKeys"} field when the message contains a
 * named placeholder without a matching key/value or MDC entry. Dev-only — use
 * {@link JsonAppenderRolling} in production.
 */
public class JsonAppenderRollingDev extends JsonAppenderRolling {

    @Override
    protected JsonLogWriter createJsonLogWriter() {
        return new JsonLogWriterDev();
    }
}
