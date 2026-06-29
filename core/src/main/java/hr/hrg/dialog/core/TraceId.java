package hr.hrg.dialog.core;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Class representing both byte[] and string for a specific traceid.
 *
 * Has static methods needed by  io.opentelemetry.sdk.trace.IdGenerator.
 * TraceId created has 8 bytes of millisecond timestamp and 8 bytes of random.
 * Create a class that implements it and point to these static methods if integrating with OpenTelemetry.
 *
 */
public class TraceId {

    private final byte[] bytes;
    private final String string;
    public static final HexFormat HEX_LOWERCASE = HexFormat.of().withLowerCase();

    public TraceId(){
        bytes = generateTraceIdBytes();
        string = HEX_LOWERCASE.formatHex(bytes);
    }

    public static byte[] generateTraceIdBytes() {
        long timestampMs = System.currentTimeMillis();
        long randomPart = ThreadLocalRandom.current().nextLong();

        // Allocate 16 bytes and write both longs in big-endian order
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(timestampMs);  // First 8 bytes
        buffer.putLong(randomPart);   // Last 8 bytes
        return buffer.array();
    }

    public static String generateTraceId() {
        long timestampMs = System.currentTimeMillis();
        long randomPart = ThreadLocalRandom.current().nextLong();
        return String.format("%016x%016x", timestampMs, randomPart);
    }

    public static String generateSpanId() {
        // Span IDs don't need a timestamp - just 8 random bytes (16 hex chars)
        long random = ThreadLocalRandom.current().nextLong();
        // Ensure it's not zero (reserved invalid value)
        while (random == 0) {
            random = ThreadLocalRandom.current().nextLong();
        }
        return String.format("%016x", random);
    }

    public byte[] getBytes() {
        return bytes;
    }

    public String getString() {
        return string;
    }

    @Override
    public String toString() {
        return string;
    }
}
