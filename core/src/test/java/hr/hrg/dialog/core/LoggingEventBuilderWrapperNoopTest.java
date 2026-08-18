package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;
import org.slf4j.MarkerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the no-op wrapper contract: every fluent method returns the singleton
 * (covariantly) and every {@code log(...)} overload does nothing.
 */
class LoggingEventBuilderWrapperNoopTest {

    private final LoggingEventBuilderWrapperNoop noop = LoggingEventBuilderWrapperNoop.singleton();

    @Test
    void fluentMethods_returnSingleton() {
        assertSame(noop, noop.stackWhenTraceEnabled());
        assertSame(noop, noop.kv("key", "value"));
        assertSame(noop, noop.with(b -> b.addKeyValue("k", "v")));
        assertSame(noop, noop.with(b -> {}, b -> {}));
        assertSame(noop, noop.setCause(new RuntimeException("x")));
        assertSame(noop, noop.addMarker(MarkerFactory.getMarker("m")));
        assertSame(noop, noop.addKeyValue("k", "v"));
        assertSame(noop, noop.addKeyValue("k", () -> "v"));
        assertSame(noop, noop.addArgument("a"));
        assertSame(noop, noop.addArgument(() -> "a"));
        assertSame(noop, noop.setMessage("m"));
        assertSame(noop, noop.setMessage(() -> "m"));
    }

    @Test
    void logOverloads_doNothing() {
        assertDoesNotThrow(() -> {
            noop.log();
            noop.log("plain");
            noop.log("fmt {}", 1);
            noop.log("fmt {}", 1, 2);
            noop.log("fmt {}", 1, 2, 3);
            noop.log(() -> "supplied");
        });
    }
}
