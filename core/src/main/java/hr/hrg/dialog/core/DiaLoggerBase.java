package hr.hrg.dialog.core;

import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

public abstract class DiaLoggerBase<L extends LoggingEventBuilderWrapperBase> implements Logger {

	protected Logger delegate;
	public DiaLoggerBase(Logger delegate) {
		this.delegate = delegate;
	}

	/**
	 * 	example: return new LoggingEventBuilderWrapper(builder, delegate);
	 * @param builder
	 * @return
	 */
	protected abstract L initBuilder(LoggingEventBuilder builder);
	protected abstract L noOpWrapper();

	protected volatile String prefix;

	/**
	 * Wrap the builder in {@link LoggingEventBuilderWrapper}.
	 * MDC handling is left entirely to SLF4J — we do not manage MDC keys here.
	 */
	protected L _contextStart(LoggingEventBuilder builder) {
		L wrapper = initBuilder(builder);
		if(prefix != null && !prefix.isEmpty()) wrapper.addKeyValue("prefix", prefix);
		return wrapper;
	}


	public synchronized void prependPrefix(String prefix){
		if(this.prefix == null)
			this.prefix = prefix;
		else
			this.prefix = prefix+this.prefix;
	}

	public static  <L1 extends  LoggingEventBuilderWrapperBase> L1 addKeyValues(L1 builder, Object ...keyVal) {
		for(int i=1; i< keyVal.length; i+=2) {
			Object key = keyVal[i-1];
			if(key == null) continue;
			builder.addKeyValue(key.toString(), keyVal[i]);
		}
		return builder;
	}

	private L fill(L builder, LogFiller filler){
		filler.fill(builder);
		return builder;
	}

	// ---- atXxx() returning wrapped builder ----
	// When level is disabled, no-op wrapper is returned.
	// When level is enabled, _contextStart wraps the builder.

	/**
	 * When the original level is disabled, we still create a wrapper with the Logger
	 * reference so that {@link LoggingEventBuilderWrapperBase#stackWhenTraceEnabled()} can still
	 * emit a TRACE-level log even when the original level (e.g. DEBUG) is disabled.
	 */
	public L atDebug() {
		if(!isDebugEnabled()) return noOpWrapper();
		return _contextStart(delegate.atDebug());
	}

	public L atDebug(LogFiller filler) {
		if(!isDebugEnabled()) return noOpWrapper();
		return fill(atDebug(),filler);
	}

	public L atError() {
		if(!isErrorEnabled()) return noOpWrapper();
		return _contextStart(delegate.atError());
	}

	public L atError(LogFiller filler) {
		if(!isErrorEnabled()) return noOpWrapper();
		return fill(atError(),filler);
	}

	public L atInfo() {
		if(!isInfoEnabled()) return noOpWrapper();
		return _contextStart(delegate.atInfo());
	}

	public L atInfo(LogFiller filler) {
		if(!isInfoEnabled()) return noOpWrapper();
		return fill(atInfo(),filler);
	}

	public L at(Level level) {
		if(!isEnabledForLevel(level)) return noOpWrapper();
		return _contextStart(delegate.atLevel(level));
	}

	public L at(Level level,LogFiller filler) {
		if(!isEnabledForLevel(level)) return noOpWrapper();
		return fill(at(level),filler);
	}

	public L atTrace() {
		if(!isTraceEnabled()) return noOpWrapper();
		return _contextStart(delegate.atTrace());
	}

	public L atTrace(LogFiller filler) {
		if(!isTraceEnabled()) return noOpWrapper();
		return fill(atTrace(),filler);
	}

	public L atWarn() {
		if(!isWarnEnabled()) return noOpWrapper();
		return _contextStart(delegate.atWarn());
	}

	public L atWarn(LogFiller filler) {
		if(!isWarnEnabled()) return noOpWrapper();
		return fill(atWarn(),filler);
	}

	// ---- void debug() overloads ----

	public void debug(Marker arg0, String arg1, Object arg2, Object arg3) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void debug(Marker arg0, String arg1, Object... arg2) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1, arg2);
	}

	public void debug(Marker arg0, String arg1, Object arg2) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1, arg2);
	}

	public void debug(Marker arg0, String arg1, Throwable arg2) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void debug(Marker arg0, String arg1) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).addMarker(arg0).log(arg1);
	}

	public void debug(String arg0, Object arg1, Object arg2) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).log(arg0, arg1, arg2);
	}

	public void debug(String arg0, Object... arg1) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).log(arg0, arg1);
	}

	public void debug(String arg0, Object arg1) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).log(arg0, arg1);
	}

	public void debug(String arg0, Throwable arg1) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).setCause(arg1).log(arg0);
	}

	public void debug(String arg0) {
		if(!isDebugEnabled()) return;
		_contextStart(delegate.atDebug()).log(arg0);
	}

	// ---- void error() overloads ----

	public void error(Marker arg0, String arg1, Object arg2, Object arg3) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void error(Marker arg0, String arg1, Object... arg2) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1, arg2);
	}

	public void error(Marker arg0, String arg1, Object arg2) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1, arg2);
	}

	public void error(Marker arg0, String arg1, Throwable arg2) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void error(Marker arg0, String arg1) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).addMarker(arg0).log(arg1);
	}

	public void error(String arg0, Object arg1, Object arg2) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).log(arg0, arg1, arg2);
	}

	public void error(String arg0, Object... arg1) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).log(arg0, arg1);
	}

	public void error(String arg0, Object arg1) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).log(arg0, arg1);
	}

	public void error(String arg0, Throwable arg1) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).setCause(arg1).log(arg0);
	}

	public void error(String arg0) {
		if(!isErrorEnabled()) return;
		_contextStart(delegate.atError()).log(arg0);
	}

	public String getName() {
		return delegate.getName();
	}

	// ---- void info() overloads ----

	public void info(Marker arg0, String arg1, Object arg2, Object arg3) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void info(Marker arg0, String arg1, Object... arg2) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1, arg2);
	}

	public void info(Marker arg0, String arg1, Object arg2) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1, arg2);
	}

	public void info(Marker arg0, String arg1, Throwable arg2) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void info(Marker arg0, String arg1) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).addMarker(arg0).log(arg1);
	}

	public void info(String arg0, Object arg1, Object arg2) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).log(arg0, arg1, arg2);
	}

	public void info(String arg0, Object... arg1) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).log(arg0, arg1);
	}

	public void info(String arg0, Object arg1) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).log(arg0, arg1);
	}

	public void info(String arg0, Throwable arg1) {
		if(!isInfoEnabled()) return;
		_contextStart(delegate.atInfo()).setCause(arg1).log(arg0);
	}

	public void info(String arg0) {
		if(!isInfoEnabled()) return;
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
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void trace(Marker arg0, String arg1, Object... arg2) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1, arg2);
	}

	public void trace(Marker arg0, String arg1, Object arg2) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1, arg2);
	}

	public void trace(Marker arg0, String arg1, Throwable arg2) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void trace(Marker arg0, String arg1) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).addMarker(arg0).log(arg1);
	}

	public void trace(String arg0, Object arg1, Object arg2) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).log(arg0, arg1, arg2);
	}

	public void trace(String arg0, Object... arg1) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).log(arg0, arg1);
	}

	public void trace(String arg0, Object arg1) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).log(arg0, arg1);
	}

	public void trace(String arg0, Throwable arg1) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).setCause(arg1).log(arg0);
	}

	public void trace(String arg0) {
		if(!isTraceEnabled()) return;
		_contextStart(delegate.atTrace()).log(arg0);
	}

	// ---- void warn() overloads ----

	public void warn(Marker arg0, String arg1, Object arg2, Object arg3) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1, arg2, arg3);
	}

	public void warn(Marker arg0, String arg1, Object... arg2) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1, arg2);
	}

	public void warn(Marker arg0, String arg1, Object arg2) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1, arg2);
	}

	public void warn(Marker arg0, String arg1, Throwable arg2) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).addMarker(arg0).setCause(arg2).log(arg1);
	}

	public void warn(Marker arg0, String arg1) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).addMarker(arg0).log(arg1);
	}

	public void warn(String arg0, Object arg1, Object arg2) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).log(arg0, arg1, arg2);
	}

	public void warn(String arg0, Object... arg1) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).log(arg0, arg1);
	}

	public void warn(String arg0, Object arg1) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).log(arg0, arg1);
	}

	public void warn(String arg0, Throwable arg1) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).setCause(arg1).log(arg0);
	}

	public void warn(String arg0) {
		if(!isWarnEnabled()) return;
		_contextStart(delegate.atWarn()).log(arg0);
	}
}