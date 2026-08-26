package hr.hrg.dialog.logback;

import javax.annotation.concurrent.ThreadSafe;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import hr.hrg.dialog.core.ReusableByteArrayOutputStream;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Predicate;

@ThreadSafe
public class JsonAppenderRolling extends RollingFileAppender<ILoggingEvent> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutputStream activeStream;
    private final JsonLogWriter jsonLogWriter;
    /**
     * Event buffer: the event JSON is assembled here and flushed to the real stream
     * with one bulk write per event. Safe to share because logback 1.5.x
     * {@code AppenderBase.doAppend} is {@code synchronized}.
     * <p>
     * Capacity is configurable via {@link #setEventBufferCapacity(int)} (default
     * 16 MiB). The backing array grows only when an event exceeds capacity.
     */
    private ReusableByteArrayOutputStream eventBuffer = new ReusableByteArrayOutputStream(DEFAULT_EVENT_BUFFER_CAPACITY);
    /** Per-event JSON snapshot hook; defaults to {@link NoopEventSnapshotHandler#INSTANCE}. */
    private EventSnapshotHandler eventSnapshotHandler = NoopEventSnapshotHandler.INSTANCE;
    /** Default event buffer capacity: 16 MiB. */
    private static final int DEFAULT_EVENT_BUFFER_CAPACITY = 16 * 1024 * 1024;
    /** Configured event buffer capacity in bytes. */
    private int eventBufferCapacity = DEFAULT_EVENT_BUFFER_CAPACITY;

    public JsonAppenderRolling() {
        this.jsonLogWriter = createJsonLogWriter();
    }

    /**
     * Factory hook so diagnostic variants (e.g. {@link JsonAppenderRollingDev})
     * can swap in a writer that adds dev-only fields. Production behavior is
     * unaffected — the base returns the plain {@link JsonLogWriter}.
     */
    protected JsonLogWriter createJsonLogWriter() {
        return new JsonLogWriter();
    }

    /**
     * Starts the appender, installing the shared no-op encoder when logback.xml
     * omits {@code <encoder>} (see {@link JsonAppender#start()}).
     */
    @Override
    public void start() {
        if (getEncoder() == null) {
            setEncoder(JsonAppender.NoOpEncoder.INSTANCE);
        }
        super.start();
    }

    @Override
    public void setOutputStream(OutputStream outputStream) {
        this.activeStream = outputStream;
        super.setOutputStream(outputStream);
    }

    /**
     * Configures the predicate used to filter stack trace frames during fingerprinting.
     * <p>
     * The value must be the fully-qualified name of a class that implements
     * {@link Predicate}{@code <String>} and has a public no-arg constructor. The predicate is
     * fed the fully-qualified class name of each stack frame; frames rejected by it are excluded
     * from both the written {@code stack} field and the {@code errHash} fingerprint.
     * <p>
     * Example logback.xml:
     * <pre>{@code
     * <appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
     *     <stackTraceFilter>com.example.MyFrameFilter</stackTraceFilter>
     * </appender>
     * }</pre>
     *
     * @param filterClassName fully-qualified class name of a {@code Predicate<String>} implementation
     */
    public void setStackTraceFilter(String filterClassName) {
        jsonLogWriter.setStackTraceFilter(JsonAppender.instantiateStackTraceFilter(filterClassName));
    }

    /**
     * Configures the capacity of the per-event reusable byte buffer. The buffer
     * is allocated once at this size and reused across events; it grows by
     * doubling only when an event exceeds the configured capacity.
     * <p>
     * Default: {@value #DEFAULT_EVENT_BUFFER_CAPACITY} bytes (16 MiB). The
     * practical ceiling is {@code Integer.MAX_VALUE} (≈2 GiB), the maximum
     * {@code byte[]} length.
     * <p>
     * Example logback.xml:
     * <pre>{@code
     * <appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderRolling">
     *     <eventBufferCapacity>262144</eventBufferCapacity>
     * </appender>
     * }</pre>
     *
     * @param bytes buffer capacity in bytes; must be >= 64
     * @throws IllegalArgumentException if {@code bytes < 64}
     */
    public void setEventBufferCapacity(int bytes) {
        if (bytes < 64) {
            throw new IllegalArgumentException("eventBufferCapacity must be >= 64 bytes: " + bytes);
        }
        this.eventBufferCapacity = bytes;
        this.eventBuffer = new ReusableByteArrayOutputStream(bytes);
    }

    /**
     * Configures a handler that receives an owned copy of each serialized JSON
     * event (see {@link EventSnapshotHandler}) — e.g. to forward events to an HTTP
     * endpoint or a log-tracking UI. Pass {@link NoopEventSnapshotHandler#INSTANCE}
     * to disable the hook.
     * The handler runs on the logging thread under the appender's guard; hand the
     * bytes off asynchronously (e.g. a bounded {@code BlockingQueue} drained by a
     * dedicated writer thread) if you cannot afford that.
     */
    public void setEventSnapshotHandler(EventSnapshotHandler handler) {
        this.eventSnapshotHandler = handler;
    }

    /**
     * Same as {@link #setEventSnapshotHandler(EventSnapshotHandler)} but takes the
     * fully-qualified class name of a no-arg-constructible
     * {@link EventSnapshotHandler} implementation, for logback.xml (see
     * {@link JsonAppender#setEventSnapshotHandler(String)} for the XML shape).
     * A {@code null} or blank value disables the hook (resets to
     * {@link NoopEventSnapshotHandler#INSTANCE}).
     */
    public void setEventSnapshotHandler(String handlerClassName) {
        this.eventSnapshotHandler = JsonAppender.instantiateEventHandler(handlerClassName);
    }

    /**
     * Writes the logging event to the currently active output stream.
     * <p>
     * {@code activeStream} is intentionally non-volatile. The field is copied to a local variable
     * at method entry so that the entire log line (event payload + newline) is written to the same
     * stream snapshot. Concurrent changes to {@code activeStream} (e.g. during rollover) do not
     * split a single event across two streams.
     */
    @Override
    protected void writeOut(ILoggingEvent event) throws IOException {
        var activeStreamLoc = activeStream;
        eventBuffer.reset();
        int pos = jsonLogWriter.writeJsonEventDirect(objectMapper, event, eventBuffer);
        eventBuffer.pos = pos;

        EventSnapshotHandler handler = eventSnapshotHandler;
        if (handler.isEnabled()) {
            handler.onEvent(Arrays.copyOf(eventBuffer.buffer(), eventBuffer.size()));
        }

        // The newline always fits: the no-grow assembly stops at
        // buf.length - RESERVE (27), which reserves the newline slot.
        eventBuffer.buf[pos] = JsonLogWriter.NL;
        eventBuffer.setPosition(pos + 1);
        // One bulk write of the whole event (buffer reuses its array across events).
        eventBuffer.writeTo(activeStreamLoc);
    }

}
