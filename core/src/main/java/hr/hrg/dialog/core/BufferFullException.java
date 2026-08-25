package hr.hrg.dialog.core;

/**
 * Unchecked exception thrown when a no-grow {@link ReusableByteArrayOutputStream}
 * would need to exceed its fixed capacity.
 * <p>
 * Unchecked so the hot-path internal buffer writers stay free of {@code throws}
 * clauses while remaining catchable at the {@code JsonLogWriter} boundary. A
 * forgotten catch propagates to the appender's {@code writeOut}, which logback
 * will error-handle.
 */
public class BufferFullException extends RuntimeException {

    public BufferFullException() {
        super();
    }

    public BufferFullException(String message) {
        super(message);
    }
}
