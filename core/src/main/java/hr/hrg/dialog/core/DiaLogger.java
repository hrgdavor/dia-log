package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

public class DiaLogger extends DiaLoggerBase<LoggingEventBuilderWrapper>{

    public DiaLogger(Logger delegate) {
        super(delegate);
    }

    @Override
    protected void contextStart(LoggingEventBuilderWrapper builder) {}

    @Override
    protected void contextEnd() {}

    @Override
    protected LoggingEventBuilderWrapper initBuilder(LoggingEventBuilder builder) {
        return new LoggingEventBuilderWrapper(builder, this::contextEnd);
    }

    @Override
    protected LoggingEventBuilderWrapper noOpWrapper() {
        return null;
    }
}
