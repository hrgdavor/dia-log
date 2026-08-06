package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.OutputStream;

public class StreamDirectJacksonEncoder {

    private final JsonFactory jsonFactory = new JsonFactory();

    /**
     * Encodes directly to Logback's underlying output stream without returning 
     * or creating intermediate byte[] arrays for the event payload.
     */
    public void encodeToStream(ILoggingEvent event, OutputStream os) throws IOException {
        // Construct the Jackson 3 generator directly wrapping Logback's OutputStream.
        // JsonGenerator manages its own internal char/byte buffers.
        try (JsonGenerator g = jsonFactory.createGenerator(os)) {
            g.writeStartObject();

            g.writeNumberProperty("timestamp", event.getTimeStamp());
            g.writeStringProperty("level", event.getLevel().toString());
            g.writeStringProperty("message", event.getFormattedMessage());

            g.writeEndObject();
            g.flush();
            
            // Append line separator
            os.write('\n');
        }
    }
}