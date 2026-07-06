package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;
import org.slf4j.spi.NOPLoggingEventBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class LoggingEventBuilderWrapperNoop extends LoggingEventBuilderWrapperBase {
    private static final LoggingEventBuilderWrapperNoop INSTANCE = new LoggingEventBuilderWrapperNoop();

    private LoggingEventBuilderWrapperNoop() {
        super(NOPLoggingEventBuilder.singleton(), null, null);
    }

    public static LoggingEventBuilderWrapperNoop singleton() {
        return INSTANCE;
    }

    // ---- Fluent configuration ----

    public LoggingEventBuilderWrapperBase stackWhenTraceEnabled() { return INSTANCE;}

    public LoggingEventBuilderWrapperNoop kv(String key, Object value) {
        return INSTANCE;
    }

    // ---- LoggingEventBuilder delegation ----

    public LoggingEventBuilderWrapperNoop with(LogFiller filler) { return INSTANCE;}
    public LoggingEventBuilderWrapperNoop with(LogFiller filler1, LogFiller filler2) { return INSTANCE;}

    @Override
    public LoggingEventBuilderWrapperNoop setCause(Throwable t) { return INSTANCE; }

    @Override
    public LoggingEventBuilderWrapperNoop addMarker(Marker marker) { return INSTANCE; }

    @Override
    public LoggingEventBuilderWrapperNoop addKeyValue(String key, Object value) { return INSTANCE;}

    @Override
    public LoggingEventBuilderWrapperNoop addKeyValue(String key, Supplier<Object> valueSupplier) { return INSTANCE;}

    @Override
    public LoggingEventBuilderWrapperNoop addArgument(Object arg) { return INSTANCE; }

    @Override
    public LoggingEventBuilderWrapperNoop addArgument(Supplier<?> argSupplier) { return INSTANCE; }

    @Override
    public LoggingEventBuilderWrapperNoop setMessage(String message) { return INSTANCE; }

    @Override
    public LoggingEventBuilderWrapperNoop setMessage(Supplier<String> messageSupplier) { return INSTANCE; }

    @Override
    public void log() {}

    @Override
    public void log(String msg) {}

    @Override
    public void log(String format, Object arg) {}

    @Override
    public void log(String format, Object arg1, Object arg2) {}

    @Override
    public void log(String format, Object... args) {}

    @Override
    public void log(Supplier<String> messageSupplier) {}

    // ---- internal ----

    private void maybeAttachTraceCause() {}

    protected void closeContext() {}
}