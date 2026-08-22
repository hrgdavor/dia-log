package hr.hrg.dialog.logback;

/**
 * Dev/diagnostic console appender backed by {@link JsonLogWriterDev}: every
 * event gets a {@code "missingKeys"} field when the message contains a named
 * placeholder without a matching key/value or MDC entry. Dev-only — use
 * {@link JsonAppender} in production.
 */
public class JsonAppenderDev extends JsonAppender {

    @Override
    protected JsonLogWriter createJsonLogWriter() {
        return new JsonLogWriterDev();
    }
}
 