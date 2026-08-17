package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Default concrete {@link LoggingEventBuilderWrapperBase} implementation used by
 * {@link DiaLogger}. Fluent methods are inherited from the base class; subclass
 * only when you need extra fluent methods with covariant return types.
 */
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