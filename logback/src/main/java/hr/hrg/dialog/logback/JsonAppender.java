package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.io.OutputStream;

public class JsonAppender extends OutputStreamAppender<ILoggingEvent> {
    private final JsonFactory jsonFactory = new JsonFactory();
    private JsonGenerator generator;
    private OutputStream activeStream;
    private final ObjectWriteContext writeCtxt = ObjectWriteContext.empty();
    private JsonLogWriter jsonLogWriter = new JsonLogWriter();


    @Override
    public void setOutputStream(OutputStream outputStream) {
        this.activeStream = outputStream;
        if (outputStream != null) {
            if (this.generator != null) {
                this.generator.flush();
                this.generator.close();
            }
            this.generator = jsonFactory.createGenerator(writeCtxt, outputStream);
        }
        super.setOutputStream(outputStream);
    }

    @Override
    protected void writeOut(ILoggingEvent event) throws IOException {
        jsonLogWriter.writeJsonEvent(generator, event, activeStream);
    }

    @Override
    public void stop() {
        if (this.generator != null) {
            this.generator.flush();
            this.generator.close();
        }
        super.stop();
    }
}
