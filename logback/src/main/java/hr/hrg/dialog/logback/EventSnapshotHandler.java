package hr.hrg.dialog.logback;

/**
 * Hook receiving a snapshot of each serialized JSON event, for forwarding the
 * event elsewhere (e.g. an HTTP endpoint or a UI log-tracking server).
 * <p>
 * The handler is invoked by {@link JsonAppender} / {@link JsonAppenderRolling}
 * right after the event JSON is assembled and <b>before</b> the trailing newline
 * is appended and flushed to the real stream.
 * <p>
 * <b>Ownership:</b> {@code eventJson} is a freshly allocated, exactly-sized copy
 * of the event JSON (no trailing newline). The handler owns it and may retain it,
 * write it to several outputs, or hand it to another thread — snapshots always
 * need their own array anyway, since the appender's internal buffer is reused for
 * the next event.
 * <p>
 * <b>Thread safety / blocking:</b> the callback runs on the logging thread under
 * the appender's guard, serially with the write itself. A slow handler blocks
 * logging; hand the bytes off asynchronously (e.g. a bounded {@code BlockingQueue}
 * drained by a dedicated writer thread) if you cannot afford that.
 * <p>
 * <b>Enabling / disabling:</b> {@link #isEnabled()} controls whether the hook
 * receives events at all. Return {@code false} to skip the per-event array copy
 * and callback entirely.
 */
public interface EventSnapshotHandler {

    /**
     * Returns {@code true} if this handler wants to receive event snapshots.
     * When {@code false}, the appender skips the array copy and the
     * {@link #onEvent(byte[])} call entirely — zero allocation, zero overhead.
     */
    default boolean isEnabled(){return true;};

    /**
     * Called once per logged event with an owned copy of the serialized JSON bytes.
     * Only invoked when {@link #isEnabled()} returns {@code true}.
     *
     * @param eventJson the serialized JSON event (owned by the handler, may be retained)
     */
    void onEvent(byte[] eventJson);
}
