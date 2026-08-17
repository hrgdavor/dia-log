package hr.hrg.dialog.core;

import javax.annotation.concurrent.ThreadSafe;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

@ThreadSafe
public class DiaLogger extends DiaLoggerBase<LoggingEventBuilderWrapperBase>{

    public DiaLogger(Logger delegate) {
        super(delegate);
    }

    @Override
    protected LoggingEventBuilderWrapperBase initBuilder(LoggingEventBuilder builder) {
        return new LoggingEventBuilderWrapper(builder, delegate);
    }

    @Override
    protected LoggingEventBuilderWrapperBase noOpWrapper() {
        return LoggingEventBuilderWrapperNoop.singleton();
    }
}