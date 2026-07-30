package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

public class LoggingEventBuilderWrapper extends LoggingEventBuilderWrapperBase{

    /**
     * Creates a new wrapper around the given {@link LoggingEventBuilder}.
     *
     * @param delegate the builder to delegate to; must not be null
     * @param logger   the underlying Logger — used to check isTraceEnabled()
     */
    public LoggingEventBuilderWrapper(LoggingEventBuilder delegate, Logger logger) {
        super(delegate, logger);
    }

}