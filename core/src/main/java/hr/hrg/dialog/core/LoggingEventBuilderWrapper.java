package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A delegating wrapper around {@link LoggingEventBuilder} that automatically
 * closes (removes from MDC) any context keys added via {@link #addKeyValue}
 * after a {@code log()} method completes.
 * <p>
 * This prevents diagnostic context from leaking across log statements when
 * key-value pairs are added and should only apply to a single event.
 * <p>
 * The wrapper also implements {@link AutoCloseable} so it can be used with
 * try-with-resources for explicit scope control:
 * <pre>{@code
 * try (var log = new LoggingEventBuilderWrapper(logger.atDebug())) {
 *     log.addKeyValue("userId", id).log("Processing user");
 * }
 * }</pre>
 * <p>
 * Additionally, {@link #stackWhenTrace()} optionally attaches the caller's
 * stack trace as a throwable when TRACE is enabled. This produces a single
 * log line — no duplicates — but adds call-stack visibility when trace
 * logging is turned on:
 * <pre>{@code
 * log.atDebug().stackWhenTrace()
 *     .kv("state", state)
 *     .log("Change state to {state}");
 * }</pre>
 *
 * @param <L> the concrete subclass type, enabling fluent methods to return the correct type
 */
public abstract class LoggingEventBuilderWrapper<L extends LoggingEventBuilderWrapper<L>> implements LoggingEventBuilder, AutoCloseable {

    private final LoggingEventBuilder delegate;
    private final Runnable clear;
    private final Logger logger; // nullable — used for stackWhenTrace isTraceEnabled check
    private final List<String> contextKeys = new ArrayList<>();
    private boolean stackWhenTrace;
    private boolean closed;

    /**
     * Returns {@code this} cast to the concrete subclass type.
     * Subclasses must implement this to enable fluent method chaining.
     */
    public abstract L self();

    /**
     * Creates a new wrapper around the given {@link LoggingEventBuilder}.
     *
     * @param delegate the builder to delegate to; must not be null
     * @param clear    optional runnable to execute on context close (e.g. contextEnd)
     */
    public LoggingEventBuilderWrapper(LoggingEventBuilder delegate, Runnable clear) {
        this(delegate, clear, null);
    }

    /**
     * Creates a new wrapper with a Logger reference (needed for {@link #stackWhenTrace()}).
     *
     * @param delegate the builder to delegate to; must not be null
     * @param clear    optional runnable to execute on context close
     * @param logger   the underlying Logger — used to check isTraceEnabled()
     */
    public LoggingEventBuilderWrapper(LoggingEventBuilder delegate, Runnable clear, Logger logger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.clear = clear;
        this.logger = logger;
    }

    // ---- Fluent configuration ----

    /**
     * When set, if TRACE is enabled on the underlying logger, a {@link Throwable}
     * (call stack) is attached as the cause of the log event. This produces a
     * <b>single</b> log line — no duplicate at trace level — but adds call-stack
     * visibility when tracing is turned on.
     * <pre>{@code
     * log.atDebug().stackWhenTrace()
     *     .kv("state", state)
     *     .log("Change state to {state}");
     * }</pre>
     */
    public L stackWhenTrace() {
        this.stackWhenTrace = true;
        return self();
    }

    /** Shorthand for {@link #addKeyValue(String, Object)}. */
    public L kv(String key, Object value) {
        return addKeyValue(key, value);
    }

    // ---- LoggingEventBuilder delegation ----

    @Override
    public L setCause(Throwable t) {
        delegate.setCause(t);
        return self();
    }

    @Override
    public L addMarker(Marker marker) {
        delegate.addMarker(marker);
        return self();
    }

    @Override
    public L addKeyValue(String key, Object value) {
        delegate.addKeyValue(key, value);
        contextKeys.add(key);
        MDC.put(String.valueOf(key), String.valueOf(value));
        return self();
    }

    @Override
    public L addKeyValue(String key, Supplier<Object> valueSupplier) {
        delegate.addKeyValue(key, valueSupplier);
        contextKeys.add(key);
        Object value = valueSupplier.get();
        if (value != null) {
            MDC.put(key, String.valueOf(value));
        }
        return self();
    }

    @Override
    public L addArgument(Object arg) {
        delegate.addArgument(arg);
        return self();
    }

    @Override
    public L addArgument(Supplier<?> argSupplier) {
        delegate.addArgument(argSupplier);
        return self();
    }

    @Override
    public L setMessage(String message) {
        delegate.setMessage(message);
        return self();
    }

    @Override
    public L setMessage(Supplier<String> messageSupplier) {
        delegate.setMessage(messageSupplier);
        return self();
    }

    // ---- log() overloads with automatic context close and optional trace cause ----

    @Override
    public void log() {
        maybeAttachTraceCause();
        delegate.log();
        closeContext();
    }

    @Override
    public void log(String msg) {
        maybeAttachTraceCause();
        delegate.log(msg);
        closeContext();
    }

    @Override
    public void log(String format, Object arg) {
        maybeAttachTraceCause();
        delegate.log(format, arg);
        closeContext();
    }

    @Override
    public void log(String format, Object arg1, Object arg2) {
        maybeAttachTraceCause();
        delegate.log(format, arg1, arg2);
        closeContext();
    }

    @Override
    public void log(String format, Object... args) {
        maybeAttachTraceCause();
        delegate.log(format, args);
        closeContext();
    }

    @Override
    public void log(Supplier<String> messageSupplier) {
        maybeAttachTraceCause();
        delegate.log(messageSupplier);
        closeContext();
    }

    // ---- AutoCloseable ----

    @Override
    public void close() {
        closeContext();
    }

    // ---- internal ----

    /**
     * If {@link #stackWhenTrace()} was called and TRACE is enabled, attach a
     * {@link Throwable} as the cause so the output shows the call stack that
     * triggered this log. Only attaches if no cause was already set.
     */
    private void maybeAttachTraceCause() {
        if (stackWhenTrace && logger != null && logger.isTraceEnabled()) {
            delegate.setCause(new Throwable("stackWhenTrace"));
        }
    }

    protected void closeContext() {
        if (closed) {
            return;
        }
        closed = true;
        try{
            clear.run();
        }catch (Throwable e){
            e.printStackTrace(System.out);
        }
        for (String key : contextKeys) {
            MDC.remove(key);
        }
        contextKeys.clear();
    }
}