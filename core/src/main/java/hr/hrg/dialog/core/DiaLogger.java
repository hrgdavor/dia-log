package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

public abstract class DiaLogger implements Logger {

	protected Logger delegate;
	public DiaLogger(Logger delegate) {
		this.delegate = delegate;
	}

	protected abstract void contextStart(LoggingEventBuilder builder);
	protected abstract void contextEnd();
	protected String prefix;

	/**
	 * Wrap the builder in {@link LoggingEventBuilderWrapper} and apply context.
	 * The wrapper ensures {@link LoggingEventBuilderWrapper#closeContext()} is
	 * called automatically after any {@code log()} method, so no manual
	 * {@link #contextEnd()} is needed.
	 */
	protected LoggingEventBuilderWrapper _contextStart(LoggingEventBuilder builder) {
		LoggingEventBuilderWrapper wrapper = new LoggingEventBuilderWrapper(builder, this::contextEnd, delegate);
		if(prefix != null && !prefix.isEmpty()) wrapper.addKeyValue("prefix", prefix);
		contextStart(wrapper);
		return wrapper;
	}

	public synchronized void addPrefix(String prefix){
		if(this.prefix == null)
			this.prefix = prefix;
		else
			this.prefix = prefix+this.prefix;
	}

	public static LoggingEventBuilder addKeyValues(LoggingEventBuilder builder, Object ...keyVal) {
		for(int i=1; i< keyVal.length; i+=2) {
			Object key = keyVal[i-1];
			if(key == null) continue;
			builder.addKeyValue(key.toString(), keyVal[i]);
		}
		return builder;
	}

	// ---- atXxx() returning wrapped builder ----
	// When level is disabled, contextStart/contextEnd are both skipped (no-op wrapper).
	// When level is enabled, _contextStart wraps the builder and the wrapper's
	// log() or close() method triggers contextEnd() via closeContext().

	private static final Runnable NOOP = () -> {};

	/**
	 * When the original level is disabled, we still create a wrapper with the Logger
	 * reference so that {@link LoggingEventBuilderWrapper#stackWhenTrace()} can still
	 * emit a TRACE-level log even when the original level (e.g. DEBUG) is disabled.
	 */
	public LoggingEventBuilderWrapper atDebug() {
		if(!isDebugEnabled()) return new LoggingEventBuilderWrapper(delegate.atDebug(), NOOP, delegate);
		return _contextStart(delegate.atDebug());
	}

	public LoggingEventBuilderWrapper atDebug(Object ...keyVal) {
		return (LoggingEventBuilderWrapper) addKeyValues(atDebug(), keyVal);
	}

	public LoggingEventBuilderWrapper atError() {
		if(!isErrorEnabled()) return new LoggingEventBuilderWrapper(delegate.atError(), NOOP, delegate);
		return _contextStart(delegate.atError());
	}

	public LoggingEventBuilderWrapper atError(Object ...keyVal) {
		return (LoggingEventBuilderWrapper) addKeyValues(atError(), keyVal);
	}

	public LoggingEventBuilderWrapper atInfo() {
		if(!isInfoEnabled()) return new LoggingEventBuilderWrapper(delegate.atInfo(), NOOP, delegate);
		return _contextStart(delegate.atInfo());
	}

	public LoggingEventBuilderWrapper atInfo(Object ...keyVal) {
		return (LoggingEventBuilderWrapper) addKeyValues(atInfo(), keyVal);
	}

	public LoggingEventBuilderWrapper atLevel(Level level) {
		if(!isEnabledForLevel(level)) return new LoggingEventBuilderWrapper(delegate.atLevel(level), NOOP, delegate);
		return _contextStart(delegate.atLevel(level));
	}

	public LoggingEventBuilderWrapper atLevel(Level level, Object ...keyVal) {
		return (LoggingEventBuilderWrapper) addKeyValues(atLevel(level), keyVal);
	}

	public LoggingEventBuilderWrapper atTrace() {
		if(!isTraceEnabled()) return new LoggingEventBuilderWrapper(delegate.atTrace(), NOOP, delegate);
		return _contextStart(delegate.atTrace());
	}

	public LoggingEventBuilderWrapper atTrace(Object ...keyVal) {
		return (LoggingEventBuilderWrapper) addKeyValues(atTrace(), keyVal);
	}

	public LoggingEventBuilderWrapper atWarn() {
		if(!isWarnEnabled()) return new LoggingEventBuilderWrapper(delegate.atWarn(), NOOP, delegate);
		return _contextStart(delegate.atWarn());
	}

	public LoggingEventBuilderWrapper atWarn(Object ...keyVal) {
		return (LoggingEventBuilderWrapper) addKeyValues(atWarn(), keyVal);
	}

	// ---- void debug() overloads (no manual contextEnd) ----

	public void debug(Marker arg0, String arg1, Object arg2, Object arg3) {
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void debug(Marker arg0, String arg1, Object... arg2) {
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1, arg2);
	}

	public void debug(Marker arg0, String arg1, Object arg2) {
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1, arg2);
	}

	public void debug(Marker arg0, String arg1, Throwable arg2) {
		_contextStart(delegate.atDebug()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void debug(Marker arg0, String arg1) {
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1);
	}

	public void debug(String arg0, Object arg1, Object arg2) {
		_contextStart(delegate.atDebug()).log(arg0, arg1, arg2);
	}

	public void debug(String arg0, Object... arg1) {
		_contextStart(delegate.atDebug()).log(arg0, arg1);
	}

	public void debug(String arg0, Object arg1) {
		_contextStart(delegate.atDebug()).log(arg0, arg1);
	}

	public void debug(String arg0, Throwable arg1) {
		_contextStart(delegate.atDebug()).setCause(arg1).log(arg0);
	}

	public void debug(String arg0) {
		_contextStart(delegate.atDebug()).log(arg0);
	}

	// ---- void error() overloads ----

	public void error(Marker arg0, String arg1, Object arg2, Object arg3) {
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void error(Marker arg0, String arg1, Object... arg2) {
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1, arg2);
	}

	public void error(Marker arg0, String arg1, Object arg2) {
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1, arg2);
	}

	public void error(Marker arg0, String arg1, Throwable arg2) {
		_contextStart(delegate.atError()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void error(Marker arg0, String arg1) {
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1);
	}

	public void error(String arg0, Object arg1, Object arg2) {
		_contextStart(delegate.atError()).log(arg0, arg1, arg2);
	}

	public void error(String arg0, Object... arg1) {
		_contextStart(delegate.atError()).log(arg0, arg1);
	}

	public void error(String arg0, Object arg1) {
		_contextStart(delegate.atError()).log(arg0, arg1);
	}

	public void error(String arg0, Throwable arg1) {
		_contextStart(delegate.atError()).setCause(arg1).log(arg0);
	}

	public void error(String arg0) {
		_contextStart(delegate.atError()).log(arg0);
	}

	public String getName() {
		return delegate.getName();
	}

	// ---- void info() overloads ----

	public void info(Marker arg0, String arg1, Object arg2, Object arg3) {
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void info(Marker arg0, String arg1, Object... arg2) {
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1, arg2);
	}

	public void info(Marker arg0, String arg1, Object arg2) {
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1, arg2);
	}

	public void info(Marker arg0, String arg1, Throwable arg2) {
		_contextStart(delegate.atInfo()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void info(Marker arg0, String arg1) {
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1);
	}

	public void info(String arg0, Object arg1, Object arg2) {
		_contextStart(delegate.atInfo()).log(arg0, arg1, arg2);
	}

	public void info(String arg0, Object... arg1) {
		_contextStart(delegate.atInfo()).log(arg0, arg1);
	}

	public void info(String arg0, Object arg1) {
		_contextStart(delegate.atInfo()).log(arg0, arg1);
	}

	public void info(String arg0, Throwable arg1) {
		_contextStart(delegate.atInfo()).setCause(arg1).log(arg0);
	}

	public void info(String arg0) {
		_contextStart(delegate.atInfo()).log(arg0);
	}

	public boolean isDebugEnabled() {
		return delegate.isDebugEnabled();
	}

	public boolean isDebugEnabled(Marker arg0) {
		return delegate.isDebugEnabled(arg0);
	}

	public boolean isEnabledForLevel(Level level) {
		return delegate.isEnabledForLevel(level);
	}

	public boolean isErrorEnabled() {
		return delegate.isErrorEnabled();
	}

	public boolean isErrorEnabled(Marker arg0) {
		return delegate.isErrorEnabled(arg0);
	}

	public boolean isInfoEnabled() {
		return delegate.isInfoEnabled();
	}

	public boolean isInfoEnabled(Marker arg0) {
		return delegate.isInfoEnabled(arg0);
	}

	public boolean isTraceEnabled() {
		return delegate.isTraceEnabled();
	}

	public boolean isTraceEnabled(Marker arg0) {
		return delegate.isTraceEnabled(arg0);
	}

	public boolean isWarnEnabled() {
		return delegate.isWarnEnabled();
	}

	public boolean isWarnEnabled(Marker arg0) {
		return delegate.isWarnEnabled(arg0);
	}

	public LoggingEventBuilder makeLoggingEventBuilder(Level level) {
		return _contextStart(delegate.makeLoggingEventBuilder(level));
	}

	// ---- void trace() overloads ----

	public void trace(Marker arg0, String arg1, Object arg2, Object arg3) {
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void trace(Marker arg0, String arg1, Object... arg2) {
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1, arg2);
	}

	public void trace(Marker arg0, String arg1, Object arg2) {
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1, arg2);
	}

	public void trace(Marker arg0, String arg1, Throwable arg2) {
		_contextStart(delegate.atTrace()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void trace(Marker arg0, String arg1) {
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1);
	}

	public void trace(String arg0, Object arg1, Object arg2) {
		_contextStart(delegate.atTrace()).log(arg0, arg1, arg2);
	}

	public void trace(String arg0, Object... arg1) {
		_contextStart(delegate.atTrace()).log(arg0, arg1);
	}

	public void trace(String arg0, Object arg1) {
		_contextStart(delegate.atTrace()).log(arg0, arg1);
	}

	public void trace(String arg0, Throwable arg1) {
		_contextStart(delegate.atTrace()).setCause(arg1).log(arg0);
	}

	public void trace(String arg0) {
		_contextStart(delegate.atTrace()).log(arg0);
	}

	// ---- void warn() overloads ----

	public void warn(Marker arg0, String arg1, Object arg2, Object arg3) {
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void warn(Marker arg0, String arg1, Object... arg2) {
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1, arg2);
	}

	public void warn(Marker arg0, String arg1, Object arg2) {
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1, arg2);
	}

	public void warn(Marker arg0, String arg1, Throwable arg2) {
		_contextStart(delegate.atWarn()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void warn(Marker arg0, String arg1) {
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1);
	}

	public void warn(String arg0, Object arg1, Object arg2) {
		_contextStart(delegate.atWarn()).log(arg0, arg1, arg2);
	}

	public void warn(String arg0, Object... arg1) {
		_contextStart(delegate.atWarn()).log(arg0, arg1);
	}

	public void warn(String arg0, Object arg1) {
		_contextStart(delegate.atWarn()).log(arg0, arg1);
	}

	public void warn(String arg0, Throwable arg1) {
		_contextStart(delegate.atWarn()).setCause(arg1).log(arg0);
	}

	public void warn(String arg0) {
		_contextStart(delegate.atWarn()).log(arg0);
	}
}