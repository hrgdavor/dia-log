package hr.hrg.dialog.logback;

/**
 * No-op {@link EventSnapshotHandler} that is always disabled.
 * <p>
 * Use as the default field value so callers only need an {@link #isEnabled()}
 * check — no {@code null} test required.
 */
public final class NoopEventSnapshotHandler implements EventSnapshotHandler {

    public static final NoopEventSnapshotHandler INSTANCE = new NoopEventSnapshotHandler();

    private NoopEventSnapshotHandler() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void onEvent(byte[] eventJson) {
    }
}
