package hr.hrg.dialog.logback;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.Writer;

class SegmentedJsonStringWriter extends Writer {
    private final JsonGenerator gen;
    private final char[] buffer = new char[1024]; // Reusable segment buffer
    private int bufferPos = 0;
    private boolean initialized = false;

    public SegmentedJsonStringWriter(JsonGenerator gen) {
        this.gen = gen;
    }

    private void initString() throws IOException {
        if (!initialized) {
            gen.writeRaw(":"); 
            gen.writeRaw("\"");
            initialized = true;
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        initString();
        
        // Cache fields locally to enable aggressive JVM register optimization
        final JsonGenerator generator = this.gen;
        final char[] localBuffer = this.buffer;
        int pos = this.bufferPos;
        final int limit = localBuffer.length;

        for (int i = 0; i < len; i++) {
            char c = cbuf[off + i];
            
            switch (c) {
                case '\\':
                case '"':
                case '/':
                    if (pos > 0) { generator.writeRaw(localBuffer, 0, pos); pos = 0; }
                    generator.writeRaw('\\');
                    generator.writeRaw(c);
                    break;
                case '\n':
                    if (pos > 0) { generator.writeRaw(localBuffer, 0, pos); pos = 0; }
                    generator.writeRaw('\\');
                    generator.writeRaw('n');
                    break;
                case '\r':
                    if (pos > 0) { generator.writeRaw(localBuffer, 0, pos); pos = 0; }
                    generator.writeRaw('\\');
                    generator.writeRaw('r');
                    break;
                case '\t':
                    if (pos > 0) { generator.writeRaw(localBuffer, 0, pos); pos = 0; }
                    generator.writeRaw('\\');
                    generator.writeRaw('t');
                    break;
                case '\b':
                    if (pos > 0) { generator.writeRaw(localBuffer, 0, pos); pos = 0; }
                    generator.writeRaw('\\');
                    generator.writeRaw('b');
                    break;
                case '\f':
                    if (pos > 0) { generator.writeRaw(localBuffer, 0, pos); pos = 0; }
                    generator.writeRaw('\\');
                    generator.writeRaw('f');
                    break;
                default:
                    localBuffer[pos++] = c;
                    if (pos == limit) {
                        generator.writeRaw(localBuffer, 0, limit);
                        pos = 0;
                    }
                    break;
            }
        }
        this.bufferPos = pos; // Sync local state back to instance variable
    }

    @Override
    public void flush() throws IOException {
        if (bufferPos > 0) {
            gen.writeRaw(buffer, 0, bufferPos);
            bufferPos = 0;
        }
    }

    @Override
    public void close() throws IOException {
        flush();
        if (initialized) {
            gen.writeRaw("\"");
        }
    }
}