package hr.hrg.dialog.logback;

import javax.annotation.concurrent.ThreadSafe;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.EncoderBase;
import hr.hrg.dialog.core.ReusableByteArrayOutputStream;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.Predicate;

@ThreadSafe
public class JsonAppender extends OutputStreamAppender<ILoggingEvent> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutputStream activeStream;
    private final JsonLogWriter jsonLogWriter;
    /**
     * Event buffer: the event JSON is assembled here (reusing the array across
     * events, growing only to the longest event) and flushed to the real stream
     * with one bulk write per event. Safe to share because logback 1.5.x
     * {@code AppenderBase.doAppend} is {@code synchronized}.
     */
    private final ReusableByteArrayOutputStream eventBuffer = new ReusableByteArrayOutputStream();
    /** Per-event JSON snapshot hook; defaults to {@link NoopEventSnapshotHandler#INSTANCE}. */
    private EventSnapshotHandler eventSnapshotHandler = NoopEventSnapshotHandler.INSTANCE;

    public JsonAppender() {
        this.jsonLogWriter = createJsonLogWriter();
    }

    /**
     * Factory hook so diagnostic variants (e.g. {@link JsonAppenderDev}) can
     * swap in a writer that adds dev-only fields. Production behavior is
     * unaffected — the base returns the plain {@link JsonLogWriter}.
     */
    protected JsonLogWriter createJsonLogWriter() {
        return new JsonLogWriter();
    }

    /**
     * Starts the appender. When no encoder is configured (logback.xml without
     * {@code <encoder>}), installs a no-op encoder first: {@link #writeOut}
     * writes JSON directly and never invokes the encoder, so one is only needed
     * to satisfy {@code OutputStreamAppender.start()}. The no-op encoder's
     * {@code headerBytes()} returns an empty array, so startup cost is zero.
     */
    @Override
    public void start() {
        if (getEncoder() == null) {
            setEncoder(NoOpEncoder.INSTANCE);
        }
        super.start();
    }

    /** No-op {@link Encoder} used when logback.xml omits {@code <encoder>}. */
    static final class NoOpEncoder extends EncoderBase<ILoggingEvent> {
        static final NoOpEncoder INSTANCE = new NoOpEncoder();
        private static final byte[] EMPTY = new byte[0];

        @Override
        public byte[] headerBytes() {
            return EMPTY;
        }

        @Override
        public byte[] footerBytes() {
            return EMPTY;
        }

        @Override
        public byte[] encode(ILoggingEvent event) {
            return EMPTY;
        }
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
     * <appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
     *     <stackTraceFilter>com.example.MyFrameFilter</stackTraceFilter>
     * </appender>
     * }</pre>
     *
     * @param filterClassName fully-qualified class name of a {@code Predicate<String>} implementation
     */
    public void setStackTraceFilter(String filterClassName) {
        jsonLogWriter.setStackTraceFilter(instantiateStackTraceFilter(filterClassName));
    }

    /**
     * Configures a handler that receives an owned copy of each serialized JSON
     * event (see {@link EventSnapshotHandler}) — e.g. to forward events to an HTTP
     * endpoint or a log-tracking UI. Pass {@link NoopEventSnapshotHandler#INSTANCE}
     * to disable the hook.
     * <p>
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
     * {@link EventSnapshotHandler} implementation, for logback.xml:
     * <pre>{@code
     * <appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
     *     <eventSnapshotHandler>com.example.MySnapshotCollector</eventSnapshotHandler>
     * </appender>
     * }</pre>
     * A {@code null} or blank value disables the hook (resets to
     * {@link NoopEventSnapshotHandler#INSTANCE}).
     */
    public void setEventSnapshotHandler(String handlerClassName) {
        this.eventSnapshotHandler = instantiateEventHandler(handlerClassName);
    }

    /**
     * Instantiates a {@code Predicate<String>} from a fully-qualified class name.
     * A {@code null} or blank value resets to the default accept-all predicate.
     */
    static Predicate<String> instantiateStackTraceFilter(String filterClassName) {
        if (filterClassName == null || filterClassName.isBlank()) {
            return cls -> true;
        }
        try {
            Class<?> clazz = Class.forName(filterClassName);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Predicate<?> predicate)) {
                throw new IllegalArgumentException(
                        "Class " + filterClassName + " does not implement java.util.function.Predicate<String>");
            }
            @SuppressWarnings("unchecked")
            Predicate<String> typed = (Predicate<String>) predicate;
            return typed;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "Cannot instantiate stackTraceFilter class " + filterClassName, e);
        }
    }

    /**
     * Instantiates an {@link EventSnapshotHandler} from a fully-qualified class name.
     * A {@code null} or blank value disables the hook (returns {@link NoopEventSnapshotHandler#INSTANCE}).
     */
    static EventSnapshotHandler instantiateEventHandler(String handlerClassName) {
        if (handlerClassName == null || handlerClassName.isBlank()) {
            return NoopEventSnapshotHandler.INSTANCE;
        }
        try {
            Class<?> clazz = Class.forName(handlerClassName);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof EventSnapshotHandler handler)) {
                throw new IllegalArgumentException(
                        "Class " + handlerClassName + " does not implement "
                                + EventSnapshotHandler.class.getName());
            }
            return handler;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                    "Cannot instantiate eventSnapshotHandler class " + handlerClassName, e);
        }
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
        jsonLogWriter.writeJsonEventDirect(objectMapper, event, eventBuffer);

        EventSnapshotHandler handler = eventSnapshotHandler;
        if (handler.isEnabled()) {
            handler.onEvent(Arrays.copyOf(eventBuffer.buffer(), eventBuffer.size()));
        }

        eventBuffer.write(JsonLogWriter.NL);
        // One bulk write of the whole event (buffer reuses its array across events).
        eventBuffer.writeTo(activeStreamLoc);
    }

}
