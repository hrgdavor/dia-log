package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.OutputStreamAppender;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonGenerator;

import java.io.OutputStream;

public class ZeroCopyDirectAppender extends OutputStreamAppender<ILoggingEvent> {

    private final JsonFactory jsonFactory = new JsonFactory();
    private OutputStream targetOutputStream; // e.g. System.out or FileOutputStream
    private final ObjectWriteContext writeCtxt = ObjectWriteContext.empty();
    private JsonGenerator g;

    public void setOutputStream(OutputStream os) {
        if(g != null) g.close();
        this.targetOutputStream = os;
        this.g = jsonFactory.createGenerator(writeCtxt,os);
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