package hr.hrg.dialog.logback;

import javax.annotation.concurrent.NotThreadSafe;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.OutputStreamAppender;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonGenerator;

import java.io.OutputStream;

/**
 * Experimental logback appender that writes events directly to a Jackson
 * {@link JsonGenerator} over the target {@link OutputStream}, bypassing the
 * encoder pipeline entirely.
 * <p>
 * Kept as an experiment/reference — it is not wired into any configuration and
 * emits a minimal fixed schema (timestamp/level/message) without key-value or
 * MDC support. Not thread-safe: {@link #setOutputStream} reassigns the generator.
 */
@NotThreadSafe
public class ZeroCopyDirectAppender extends OutputStreamAppender<ILoggingEvent> {

    private final JsonFactory jsonFactory = new JsonFactory();
    private OutputStream targetOutputStream; // e.g. System.out or FileOutputStream
    private final ObjectWriteContext writeCtxt = ObjectWriteContext.empty();
    private JsonGenerator g;

    public void setOutputStream(OutputStream os) {
        if(g != null) g.close();
        this.targetOutputStream = os;
        this.g = jsonFactory.createGenerator(writeCtxt,os);
        // must also update the base field, otherwise start() fails with
        // "No output stream set" and the appender never starts
        super.setOutputStream(os);
    }

    @Override
    protected void subAppend(ILoggingEvent event) {
        if (!isStarted() || targetOutputStream == null) return;
        try {
            g.writeStartObject();
            g.writeNumberProperty("timestamp", event.getTimeStamp());
            g.writeStringProperty("level", event.getLevel().toString());
            g.writeStringProperty("message", event.getFormattedMessage());
            g.writeEndObject();
            
            g.flush();
            targetOutputStream.write('\n');
        } catch (Exception e) {
            addError("Failed to flush log directly to target stream", e);
        }
    }
}