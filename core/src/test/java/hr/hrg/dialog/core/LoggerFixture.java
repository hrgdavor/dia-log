package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only {@link Logger} implementation that captures the last logged
 * message and returns a simple {@link LoggingEventBuilder} for verification.
 */
final class LoggerFixture {

    private LoggerFixture() {
    }

    static class TestLogger implements Logger {

        private final String name;
        String lastMessage;
        final List<String> allMessages = new ArrayList<>();

        TestLogger(String name) {
            this.name = name;
        }

        String lastMessage() {
            return lastMessage;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public LoggingEventBuilder atTrace() {
            return builder();
        }

        @Override
        public LoggingEventBuilder atDebug() {
            return builder();
        }

        @Override
        public LoggingEventBuilder atInfo() {
            return builder();
        }

        @Override
        public LoggingEventBuilder atWarn() {
            return builder();
        }

        @Override
        public LoggingEventBuilder atError() {
            return builder();
        }

        private LoggingEventBuilder builder() {
            return new LoggingEventBuilder() {
                @Override
                public LoggingEventBuilder setCause(Throwable t) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addMarker(Marker marker) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addKeyValue(String key, Object value) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addKeyValue(String key, java.util.function.Supplier<Object> valueSupplier) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addArgument(Object arg) {
                    return this;
                }

                @Override
                public LoggingEventBuilder addArgument(java.util.function.Supplier<?> argSupplier) {
                    return this;
                }

                @Override
                public LoggingEventBuilder setMessage(String message) {
                    return this;
                }

                @Override
                public LoggingEventBuilder setMessage(java.util.function.Supplier<String> messageSupplier) {
                    return this;
                }

                @Override
                public void log() {
                    lastMessage = "";
                    allMessages.add("");
                }

                @Override
                public void log(String msg) {
                    lastMessage = msg;
                    allMessages.add(msg);
                }

                @Override
                public void log(String format, Object arg) {
                    String msg = format.replace("{}", String.valueOf(arg));
                    lastMessage = msg;
                    allMessages.add(msg);
                }

                @Override
                public void log(String format, Object arg1, Object arg2) {
                    String msg = format
                            .replaceFirst("\\{}", String.valueOf(arg1))
                            .replaceFirst("\\{}", String.valueOf(arg2));
                    lastMessage = msg;
                    allMessages.add(msg);
                }

                @Override
                public void log(String format, Object... args) {
                    String msg = format;
                    for (Object arg : args) {
                        msg = msg.replaceFirst("\\{}", String.valueOf(arg));
                    }
                    lastMessage = msg;
                    allMessages.add(msg);
                }

                @Override
                public void log(java.util.function.Supplier<String> messageSupplier) {
                    String msg = messageSupplier.get();
                    lastMessage = msg;
                    allMessages.add(msg);
                }
            };
        }

        // ---- unsupported stubs ----

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public void trace(String msg) {
        }

        @Override
        public void trace(String format, Object arg) {
        }

        @Override
        public void trace(String format, Object arg1, Object arg2) {
        }

        @Override
        public void trace(String format, Object... arguments) {
        }

        @Override
        public void trace(String msg, Throwable t) {
        }

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return true;
        }

        @Override
        public void trace(Marker marker, String msg) {
        }

        @Override
        public void trace(Marker marker, String format, Object arg) {
        }

        @Override
        public void trace(Marker marker, String format, Object arg1, Object arg2) {
        }

        @Override
        public void trace(Marker marker, String format, Object... argArray) {
        }

        @Override
        public void trace(Marker marker, String msg, Throwable t) {
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public void debug(String msg) {
        }

        @Override
        public void debug(String format, Object arg) {
        }

        @Override
        public void debug(String format, Object arg1, Object arg2) {
        }

        @Override
        public void debug(String format, Object... arguments) {
        }

        @Override
        public void debug(String msg, Throwable t) {
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return true;
        }

        @Override
        public void debug(Marker marker, String msg) {
        }

        @Override
        public void debug(Marker marker, String format, Object arg) {
        }

        @Override
        public void debug(Marker marker, String format, Object arg1, Object arg2) {
        }

        @Override
        public void debug(Marker marker, String format, Object... arguments) {
        }

        @Override
        public void debug(Marker marker, String msg, Throwable t) {
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void info(String format, Object arg) {
        }

        @Override
        public void info(String format, Object arg1, Object arg2) {
        }

        @Override
        public void info(String format, Object... arguments) {
        }

        @Override
        public void info(String msg, Throwable t) {
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return true;
        }

        @Override
        public void info(Marker marker, String msg) {
        }

        @Override
        public void info(Marker marker, String format, Object arg) {
        }

        @Override
        public void info(Marker marker, String format, Object arg1, Object arg2) {
        }

        @Override
        public void info(Marker marker, String format, Object... arguments) {
        }

        @Override
        public void info(Marker marker, String msg, Throwable t) {
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void warn(String format, Object arg) {
        }

        @Override
        public void warn(String format, Object arg1, Object arg2) {
        }

        @Override
        public void warn(String format, Object... arguments) {
        }

        @Override
        public void warn(String msg, Throwable t) {
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return true;
        }

        @Override
        public void warn(Marker marker, String msg) {
        }

        @Override
        public void warn(Marker marker, String format, Object arg) {
        }

        @Override
        public void warn(Marker marker, String format, Object arg1, Object arg2) {
        }

        @Override
        public void warn(Marker marker, String format, Object... arguments) {
        }

        @Override
        public void warn(Marker marker, String msg, Throwable t) {
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public void error(String msg) {
        }

        @Override
        public void error(String format, Object arg) {
        }

        @Override
        public void error(String format, Object arg1, Object arg2) {
        }

        @Override
        public void error(String format, Object... arguments) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return true;
        }

        @Override
        public void error(Marker marker, String msg) {
        }

        @Override
        public void error(Marker marker, String format, Object arg) {
        }

        @Override
        public void error(Marker marker, String format, Object arg1, Object arg2) {
        }

        @Override
        public void error(Marker marker, String format, Object... arguments) {
        }

        @Override
        public void error(Marker marker, String msg, Throwable t) {
        }
    }
}
