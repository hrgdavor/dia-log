package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

public class DiaLogger extends DiaLoggerBase<LoggingEventBuilderWrapperBase>{

    public DiaLogger(Logger delegate) {
        super(delegate);
    }

    @Override
    protected void contextStart(LoggingEventBuilderWrapperBase builder) {}

    @Override
    protected LoggingEventBuilderWrapperBase initBuilder(LoggingEventBuilder builder) {
        return new LoggingEventBuilderWrapper(builder, delegate);
    }

    @Override
    protected LoggingEventBuilderWrapperBase noOpWrapper() {
        return LoggingEventBuilderWrapperNoop.singleton();
    }
}