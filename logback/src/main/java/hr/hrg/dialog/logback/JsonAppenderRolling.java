package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.RollingFileAppender;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;

public class JsonAppenderRolling extends RollingFileAppender<ILoggingEvent> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutputStream activeStream;
    private JsonLogWriter jsonLogWriter = new JsonLogWriter();

    @Override
    public void setOutputStream(OutputStream outputStream) {
        this.activeStream = outputStream;
        super.setOutputStream(outputStream);
    }

    @Override
    protected void writeOut(ILoggingEvent event) throws IOException {
        jsonLogWriter.writeJsonEvent(objectMapper, event, activeStream);
        activeStream.write(JsonLogWriter.NL);
    }
}
