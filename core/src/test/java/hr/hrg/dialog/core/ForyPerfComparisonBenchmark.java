package hr.hrg.dialog.core;

import hr.hrg.dialog.core.perf.ClassicEscapedStringWriter;
import hr.hrg.dialog.core.perf.ClassicFieldPrefixes;
import hr.hrg.dialog.core.perf.ClassicJsonNumberWriter;
import hr.hrg.dialog.core.perf.ClassicStringByteExtractor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Old-vs-new comparison benchmarks for the performance techniques ported from
 * Apache Fory commit 585eb16f ("feat(java): optimize json perf", PR #3871).
 *
 * <p>The {@code *Classic*} methods run the pre-optimization implementations
 * kept in {@code hr.hrg.dialog.core.perf}; the {@code *New*} methods run the
 * optimized production paths. {@code *Direct} uses a
 * {@link ReusableByteArrayOutputStream} (T4 direct-buffer mode), {@code *Stream}
 * uses a plain {@link ByteArrayOutputStream}.
 *
 * <p>Suggested run:
 * {@code java -cp <test-classpath> org.openjdk.jmh.Main hr.hrg.dialog.core.ForyPerfComparisonBenchmark.* -wi 3 -i 5 -f 1}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class ForyPerfComparisonBenchmark {

    // Typical log payloads: class/method-name-like ASCII strings, plus
    // escaping-heavy and Latin-1-mixed content.
    private static final String[] SAMPLES = {
        "com.example.service.UserService",
        "handleRequest(HttpServletRequest req)",
        "org.apache.kafka.clients.producer.KafkaProducer",
        "a simple log message with some words in it",
        "level=INFO user=alice status=ok duration=42ms",
        "quote \" and backslash \\ inside",
        "héllo wörld — naïve café",
        "tab\there and newline\nhere",
        "short",
        "x",
        ""
    };

    private byte[][] latin1Bytes;
    private String[] strings;
    private ReusableByteArrayOutputStream directOut;
    private ByteArrayOutputStream streamOut;
    private long[] longValues;
    private int[] intValues;
    private byte[] classicIntBuf;
    private byte[] newIntBuf;
    private byte[] classicLongBuf;
    private byte[] newLongBuf;
    private byte[] prefixLevelBytes;
    private byte[] prefixErrMessageBytes;
    private long prefixLevelWord0;
    private long prefixErrWord0;
    private long prefixErrWord1;

    // Additional scenarios:
    // - escaping-heavy: many bytes that force the SWAR dirty-word path.
    // - accented: many Latin-1 0x80..0xFF bytes (UTF-8 expansion).
    // - long string: exercises the >= 32 byte 16-byte block loop.
    private byte[] heavyBytes;
    private String heavyString;
    private byte[] accentedBytes;
    private String accentedString;
    private byte[] longAsciiBytes;
    private String longAsciiString;
    private long[] smallLongValues;
    private int[] smallIntValues;

    @Setup(Level.Trial)
    public void setup() {
        latin1Bytes = new byte[SAMPLES.length][];
        strings = new String[SAMPLES.length];
        for (int i = 0; i < SAMPLES.length; i++) {
            latin1Bytes[i] = SAMPLES[i].getBytes(StandardCharsets.ISO_8859_1);
            strings[i] = new String(latin1Bytes[i], StandardCharsets.ISO_8859_1);
        }
        directOut = new ReusableByteArrayOutputStream(64 * 1024);
        streamOut = new ByteArrayOutputStream(64 * 1024);

        // Escaping-heavy: quotes, backslashes and control chars every few bytes.
        StringBuilder heavy = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            heavy.append(i % 3 == 0 ? '"' : i % 3 == 1 ? '\\' : (char) (0x01 + i % 0x1E));
        }
        heavyString = heavy.toString();
        heavyBytes = heavyString.getBytes(StandardCharsets.ISO_8859_1);

        // Accented: every third byte is Latin-1 high (0x80..0xFF).
        byte[] acc = new byte[96];
        for (int i = 0; i < acc.length; i++) {
            acc[i] = i % 3 == 0 ? (byte) (0xA0 + (i * 7) % 0x5F) : (byte) 'x';
        }
        accentedBytes = acc;
        accentedString = new String(acc, StandardCharsets.ISO_8859_1);

        // Long ASCII: 200 printable chars (>= 32 byte block loop).
        longAsciiString = "com.example.service.UserService.handleRequest(request, response, context)"
                .repeat(3);
        longAsciiBytes = longAsciiString.getBytes(StandardCharsets.ISO_8859_1);

        int n = 256;
        longValues = new long[n];
        intValues = new int[n];
        smallLongValues = new long[n];
        smallIntValues = new int[n];
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            longValues[i] = rnd.nextLong();
            intValues[i] = rnd.nextInt();
            // Timestamp-sized (13 digits) and tiny values: the T5 fast path.
            smallLongValues[i] = i % 2 == 0 ? rnd.nextLong(1_000_000_000_000L) : rnd.nextInt(10_000);
            smallIntValues[i] = rnd.nextInt(100_000);
        }
        classicIntBuf = ClassicJsonNumberWriter.makeIntBuffer();
        newIntBuf = JsonNumberWriter.makeIntBuffer();
        classicLongBuf = ClassicJsonNumberWriter.makeLongBuffer();
        newLongBuf = JsonNumberWriter.makeLongBuffer();
        prefixLevelBytes = "\"level\":".getBytes(StandardCharsets.UTF_8);
        prefixErrMessageBytes = "\"errMessage\":".getBytes(StandardCharsets.UTF_8);
        prefixLevelWord0 = packLE(prefixLevelBytes, 0);
        prefixErrWord0 = packLE(prefixErrMessageBytes, 0);
        prefixErrWord1 = packLE(prefixErrMessageBytes, 8);
    }

    private static long packLE(byte[] bytes, int off) {
        long v = 0;
        int end = Math.min(off + 8, bytes.length);
        for (int i = off; i < end; i++) {
            v |= (bytes[i] & 0xFFL) << ((i - off) << 3);
        }
        return v;
    }

    private int sampleIndex = 0;

    private byte[] nextLatin1() {
        byte[] b = latin1Bytes[sampleIndex++ % latin1Bytes.length];
        return b;
    }

    private String nextString() {
        String s = strings[sampleIndex++ % strings.length];
        return s;
    }

    // ==========================================
    // T1/T2 — JSON escaping (EscapedJsonStringWriter)
    // ==========================================

    @Benchmark
    public void escapingClassic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicEscapedStringWriter.writeEscapedLatin1(streamOut, nextLatin1());
        bh.consume(streamOut);
    }

    @Benchmark
    public void escapingNewStream(Blackhole bh) throws IOException {
        streamOut.reset();
        EscapedJsonStringWriter.writeJsonStringOrNull(streamOut, nextString());
        bh.consume(streamOut);
    }

    @Benchmark
    public void escapingNewDirect(Blackhole bh) throws IOException {
        directOut.reset();
        EscapedJsonStringWriter.writeJsonStringOrNull(directOut, nextString());
        bh.consume(directOut);
    }

    // ==========================================
    // T1/T2 — Latin-1 to UTF-8 (StringByteExtractor)
    // ==========================================

    @Benchmark
    public void latin1Classic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicStringByteExtractor.writeLatin1(streamOut, nextLatin1());
        bh.consume(streamOut);
    }

    @Benchmark
    public void latin1NewStream(Blackhole bh) throws IOException {
        streamOut.reset();
        StringByteExtractor.writeLatin1(streamOut, nextLatin1());
        bh.consume(streamOut);
    }

    @Benchmark
    public void latin1NewDirect(Blackhole bh) throws IOException {
        directOut.reset();
        StringByteExtractor.writeLatin1(directOut, nextLatin1());
        bh.consume(directOut);
    }

    // ==========================================
    // T1/T2 — JSON escaping, dirty-word scenarios
    // ==========================================

    @Benchmark
    public void escapingHeavyClassic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicEscapedStringWriter.writeEscapedLatin1(streamOut, heavyBytes);
        bh.consume(streamOut);
    }

    @Benchmark
    public void escapingHeavyNewStream(Blackhole bh) throws IOException {
        streamOut.reset();
        EscapedJsonStringWriter.writeJsonStringOrNull(streamOut, heavyString);
        bh.consume(streamOut);
    }

    @Benchmark
    public void escapingHeavyNewDirect(Blackhole bh) throws IOException {
        directOut.reset();
        EscapedJsonStringWriter.writeJsonStringOrNull(directOut, heavyString);
        bh.consume(directOut);
    }

    @Benchmark
    public void escapingLongNewDirect(Blackhole bh) throws IOException {
        directOut.reset();
        EscapedJsonStringWriter.writeJsonStringOrNull(directOut, longAsciiString);
        bh.consume(directOut);
    }

    // ==========================================
    // T1/T2 — Latin-1 to UTF-8, accented scenario
    // ==========================================

    @Benchmark
    public void latin1AccentedClassic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicStringByteExtractor.writeLatin1(streamOut, accentedBytes);
        bh.consume(streamOut);
    }

    @Benchmark
    public void latin1AccentedNewStream(Blackhole bh) throws IOException {
        streamOut.reset();
        StringByteExtractor.writeLatin1(streamOut, accentedBytes);
        bh.consume(streamOut);
    }

    @Benchmark
    public void latin1AccentedNewDirect(Blackhole bh) throws IOException {
        directOut.reset();
        StringByteExtractor.writeLatin1(directOut, accentedBytes);
        bh.consume(directOut);
    }

    @Benchmark
    public void latin1LongNewDirect(Blackhole bh) throws IOException {
        directOut.reset();
        StringByteExtractor.writeLatin1(directOut, longAsciiBytes);
        bh.consume(directOut);
    }

    // ==========================================
    // T5 — packed digit tables (JsonNumberWriter)
    // ==========================================

    @Benchmark
    public void longClassic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicJsonNumberWriter.writeLong(streamOut, classicLongBuf, longValues[sampleIndex++ % longValues.length]);
        bh.consume(streamOut);
    }

    @Benchmark
    public void longNew(Blackhole bh) throws IOException {
        streamOut.reset();
        JsonNumberWriter.writeLong(streamOut, newLongBuf, longValues[sampleIndex++ % longValues.length]);
        bh.consume(streamOut);
    }

    @Benchmark
    public void longSmallClassic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicJsonNumberWriter.writeLong(streamOut, classicLongBuf, smallLongValues[sampleIndex++ % smallLongValues.length]);
        bh.consume(streamOut);
    }

    @Benchmark
    public void longSmallNew(Blackhole bh) throws IOException {
        streamOut.reset();
        JsonNumberWriter.writeLong(streamOut, newLongBuf, smallLongValues[sampleIndex++ % smallLongValues.length]);
        bh.consume(streamOut);
    }

    @Benchmark
    public void intClassic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicJsonNumberWriter.writeInt(streamOut, classicIntBuf, intValues[sampleIndex++ % intValues.length]);
        bh.consume(streamOut);
    }

    @Benchmark
    public void intNew(Blackhole bh) throws IOException {
        streamOut.reset();
        JsonNumberWriter.writeInt(streamOut, newIntBuf, intValues[sampleIndex++ % intValues.length]);
        bh.consume(streamOut);
    }

    @Benchmark
    public void intSmallClassic(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicJsonNumberWriter.writeInt(streamOut, classicIntBuf, smallIntValues[sampleIndex++ % smallIntValues.length]);
        bh.consume(streamOut);
    }

    @Benchmark
    public void intSmallNew(Blackhole bh) throws IOException {
        streamOut.reset();
        JsonNumberWriter.writeInt(streamOut, newIntBuf, smallIntValues[sampleIndex++ % smallIntValues.length]);
        bh.consume(streamOut);
    }

    // ==========================================
    // T3/T4/T6 — field prefixes: stream-mediated vs packed direct
    // ==========================================

    @Benchmark
    public void prefixesStreamMediated(Blackhole bh) throws IOException {
        streamOut.reset();
        streamOut.write('{');
        streamOut.write(prefixLevelBytes);
        streamOut.write(',');
        streamOut.write(prefixErrMessageBytes);
        bh.consume(streamOut);
    }

    @Benchmark
    public void prefixesClassicFixture(Blackhole bh) throws IOException {
        streamOut.reset();
        ClassicFieldPrefixes.writeObjectStartAndField(streamOut, prefixLevelBytes);
        ClassicFieldPrefixes.writeFieldPrefix(streamOut, prefixErrMessageBytes);
        bh.consume(streamOut);
    }

    @Benchmark
    public void prefixesPackedDirect(Blackhole bh) throws IOException {
        directOut.reset();
        directOut.write('{');
        directOut.writeLongPrefixLE(prefixLevelWord0, 7);      // "level":
        directOut.writeLongPrefixLE(prefixErrWord0, 8);        // "errMessa
        directOut.writeLongPrefixLE(prefixErrWord1, 4);        // ge":
        bh.consume(directOut);
    }
}
