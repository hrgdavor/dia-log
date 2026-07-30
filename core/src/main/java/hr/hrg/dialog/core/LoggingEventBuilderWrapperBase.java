package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A delegating wrapper around {@link LoggingEventBuilder} that provides
 * convenience methods like {@link #kv(String, Object)} and
 * {@link #stackWhenTraceEnabled()}.
 * <p>
 * MDC handling is left entirely to SLF4J — we do not manage MDC keys here.
 * Key-value pairs added via {@link #addKeyValue(String, Object)} are delegated
 * directly to the underlying SLF4J builder, which handles MDC propagation
 * as configured by the logging implementation (e.g. logback).
 * <p>
 * The wrapper also implements {@link AutoCloseable} so it can be used with
 * try-with-resources for explicit scope control:
 * <pre>{@code
 * try (var log = new LoggingEventBuilderWrapper(logger.atDebug())) {
 *     log.addKeyValue("userId", id).log("Processing user");
 * }
 * }</pre>
 * <p>
 * Additionally, {@link #stackWhenTraceEnabled()} optionally attaches the caller's
 * stack trace as a throwable when TRACE is enabled. This produces a single
 * log line — no duplicates — but adds call-stack visibility when trace
 * logging is turned on:
 * <pre>{@code
 * log.atDebug().stackWhenTraceEnabled()
 *     .kv("state", state)
 *     .log("Change state to {state}");
 * }</pre>
 */
public class LoggingEventBuilderWrapperBase implements LoggingEventBuilder {

    protected final LoggingEventBuilder delegate;
    protected final Logger logger; // nullable — used for stackWhenTraceEnabled isTraceEnabled check
    protected boolean stackWhenTraceEnabled;

    /**
     * Creates a new wrapper with a Logger reference (needed for {@link #stackWhenTraceEnabled()}).
     *
     * @param delegate the builder to delegate to; must not be null
     * @param logger   the underlying Logger — used to check isTraceEnabled()
     */
    protected LoggingEventBuilderWrapperBase(LoggingEventBuilder delegate, Logger logger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.logger = logger;
    }

    // ---- Fluent configuration ----

    /**
     * When set, if TRACE is enabled on the underlying logger, a {@link Throwable}
     * (call stack) is attached as the cause of the log event. This produces a
     * <b>single</b> log line — no duplicate at trace level — but adds call-stack
     * visibility when tracing is turned on.
     * <pre>{@code
     * log.atDebug().stackWhenTraceEnabled()
     *     .kv("state", state)
     *     .log("Change state to {state}");
     * }</pre>
     */
    public LoggingEventBuilderWrapperBase stackWhenTraceEnabled() {
        this.stackWhenTraceEnabled = true;
        return this;
    }

    /** Shorthand for {@link #addKeyValue(String, Object)}. */
    public LoggingEventBuilderWrapperBase kv(String key, Object value) {
        return addKeyValue(key, value);
    }

    // ---- LoggingEventBuilder delegation ----

    public LoggingEventBuilderWrapperBase with(LogFiller filler) {
        filler.fill(delegate);
        return this;
    }
    public LoggingEventBuilderWrapperBase with(LogFiller filler1, LogFiller filler2) {
        filler1.fill(delegate);
        filler2.fill(delegate);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase setCause(Throwable t) {
        delegate.setCause(t);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase addMarker(Marker marker) {
        delegate.addMarker(marker);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase addKeyValue(String key, Object value) {
        delegate.addKeyValue(key, value);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase addKeyValue(String key, Supplier<Object> valueSupplier) {
        delegate.addKeyValue(key, valueSupplier);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase addArgument(Object arg) {
        delegate.addArgument(arg);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase addArgument(Supplier<?> argSupplier) {
        delegate.addArgument(argSupplier);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase setMessage(String message) {
        delegate.setMessage(message);
        return this;
    }

    @Override
    public LoggingEventBuilderWrapperBase setMessage(Supplier<String> messageSupplier) {
        delegate.setMessage(messageSupplier);
        return this;
    }

    // ---- log() overloads with optional trace cause ----

    @Override
    public void log() {
        maybeAttachTraceCause();
        delegate.log();
    }

    @Override
    public void log(String msg) {
        maybeAttachTraceCause();
        delegate.log(msg);
    }

    @Override
    public void log(String format, Object arg) {
        maybeAttachTraceCause();
        delegate.log(format, arg);
    }

    @Override
    public void log(String format, Object arg1, Object arg2) {
        maybeAttachTraceCause();
        delegate.log(format, arg1, arg2);
    }

    @Override
    public void log(String format, Object... args) {
        maybeAttachTraceCause();
        delegate.log(format, args);
    }

    @Override
    public void log(Supplier<String> messageSupplier) {
        maybeAttachTraceCause();
        delegate.log(messageSupplier);
    }

    // ---- internal ----

    /**
     * If {@link #stackWhenTraceEnabled()} was called and TRACE is enabled, attach a
     * {@link Throwable} as the cause so the output shows the call stack that
     * triggered this log. Only attaches if no cause was already set.
     */
    private void maybeAttachTraceCause() {
        if (stackWhenTraceEnabled && logger != null && logger.isTraceEnabled()) {
            delegate.setCause(new Throwable("stackWhenTraceEnabled"));
        }
    }
}