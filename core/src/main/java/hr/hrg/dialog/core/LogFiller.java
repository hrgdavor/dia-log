package hr.hrg.dialog.core;

import org.slf4j.spi.LoggingEventBuilder;

/**
 * Functional callback that applies extra configuration to a
 * {@link LoggingEventBuilder} before the event is logged.
 * <p>
 * Example: {@code log.atInfo(filler).log("message")} where the filler adds
 * a key/value pair to every event produced through that call site.
 */
@FunctionalInterface
public interface LogFiller{
    void fill(LoggingEventBuilder b);
}
