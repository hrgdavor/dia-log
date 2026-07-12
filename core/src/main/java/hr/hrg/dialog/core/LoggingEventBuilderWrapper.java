package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

public class LoggingEventBuilderWrapper extends LoggingEventBuilderWrapperBase{

    /**
     * Creates a new wrapper around the given {@link LoggingEventBuilder}.
     *
     * @param delegate the builder to delegate to; must not be null
     * @param clear    optional runnable to execute on context close (e.g. contextEnd)
     */
    public LoggingEventBuilderWrapper(LoggingEventBuilder delegate, Runnable clear, Logger logger) {
        super(delegate, clear, null);
    }

}
