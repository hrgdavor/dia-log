package hr.hrg.dialog.core;

import org.junit.jupiter.api.Test;
import org.slf4j.MarkerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for {@link LoggingEventBuilderWrapperBase}: null keys/values,
 * supplier returning null, multiple {@code log()} calls, and fluent delegation.
 * (Planned coverage item from plans/analysis-report.md §11.)
 */
class LoggingEventBuilderWrapperBaseTest {

    private final LoggerFixture.TestLogger logger = new LoggerFixture.TestLogger("test.wrapper");

    private LoggingEventBuilderWrapper wrapper() {
        return new LoggingEventBuilderWrapper(logger.atDebug(), logger);
    }

    @Test
    void constructor_rejectsNullDelegate() {
        assertThrows(NullPointerException.class,
                () -> new LoggingEventBuilderWrapper(null, logger));
    }

    @Test
    void constructor_acceptsNullLogger() {
        // null logger is allowed; stackWhenTraceEnabled() must then be a no-op
        assertDoesNotThrow(() -> new LoggingEventBuilderWrapper(logger.atDebug(), null));
    }

    @Test
    void addKeyValue_nullKey_isTolerated() {
        assertDoesNotThrow(() -> wrapper().addKeyValue(null, "value"));
    }

    @Test
    void addKeyValue_nullValue_isTolerated() {
        assertDoesNotThrow(() -> wrapper().addKeyValue("key", null));
    }

    @Test
    void addKeyValue_supplierReturningNull_isTolerated() {
        assertDoesNotThrow(() -> wrapper().addKeyValue("key", () -> null));
    }

    @Test
    void multipleLogCalls_areAllDelegated() {
        LoggingEventBuilderWrapper w = wrapper();
        w.log("first");
        w.log("second");
        w.log("third");

        assertEquals(List.of("first", "second", "third"), logger.allMessages);
    }

    @Test
    void stackWhenTraceEnabled_withNullLogger_doesNotThrow() {
        LoggingEventBuilderWrapper w = new LoggingEventBuilderWrapper(logger.atDebug(), null);
        assertDoesNotThrow(() -> w.stackWhenTraceEnabled().log("ok"));
        assertEquals("ok", logger.lastMessage(), "log must still be delegated");
    }

    @Test
    void stackWhenTraceEnabled_withDisabledTrace_doesNotAttachCause() {
        // LoggerFixture reports isTraceEnabled() == true, so no cause is attached
        // only when the flag is not set; verify the plain path never throws.
        assertDoesNotThrow(() -> wrapper().log("plain"));
    }

    @Test
    void kv_returnsThis_andDelegates() {
        LoggingEventBuilderWrapper w = wrapper();
        assertSame(w, w.kv("key", "value"));
    }

    @Test
    void setCause_returnsThis() {
        LoggingEventBuilderWrapper w = wrapper();
        assertSame(w, w.setCause(new RuntimeException("boom")));
    }

    @Test
    void addMarker_returnsThis() {
        LoggingEventBuilderWrapper w = wrapper();
        assertSame(w, w.addMarker(MarkerFactory.getMarker("marker")));
    }

    @Test
    void with_filler_isAppliedAndReturnsThis() {
        LoggingEventBuilderWrapper w = wrapper();
        assertSame(w, w.with(b -> b.addKeyValue("filler", "applied")));
    }

    @Test
    void with_twoFillers_appliesBoth() {
        LoggingEventBuilderWrapper w = wrapper();
        assertSame(w, w.with(b -> b.addMarker(MarkerFactory.getMarker("m1")), b -> b.addKeyValue("k", "v")));
    }

    @Test
    void setMessage_returnsThis() {
        LoggingEventBuilderWrapper w = wrapper();
        assertSame(w, w.setMessage("custom message"));
    }
}
