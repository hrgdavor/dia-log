package hr.hrg.dialog.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonNumberWriterTest {

    private ByteArrayOutputStream out;

    private byte[] intBuf;
    private byte[] longBuf;
    private byte[] floatBuf;
    private byte[] doubleBuf;

    @BeforeEach
    void setUp() {
        out = new ByteArrayOutputStream();
        intBuf = JsonNumberWriter.makeIntBuffer();
        longBuf = JsonNumberWriter.makeLongBuffer();
        floatBuf = JsonNumberWriter.makeFloatBuffer();
        doubleBuf = JsonNumberWriter.makeDoubleBuffer();
    }

    private String getWrittenString() {
        return out.toString(StandardCharsets.UTF_8);
    }

    // ==========================================
    // FLOAT TESTS
    // ==========================================

    @Nested
    @DisplayName("Float JSON Serialization")
    class FloatTests {

        @Test
        void testZeroes() throws IOException {
            JsonNumberWriter.writeFloat(out, floatBuf, 0.0f);
            assertEquals("0.0", getWrittenString());

            out.reset();
            JsonNumberWriter.writeFloat(out, floatBuf, -0.0f);
            assertEquals("-0.0", getWrittenString());
        }

        @ParameterizedTest
        @ValueSource(floats = {1.0f, -1.0f, 0.5f, 123.456f, -9876.54f, 1e-5f, 1e20f})
        void testStandardFloats(float val) throws IOException {
            JsonNumberWriter.writeFloat(out, floatBuf, val);
            assertEquals(val, Float.parseFloat(getWrittenString()), 1e-6f);
        }

        @Test
        void testFloatBoundaries() throws IOException {
            JsonNumberWriter.writeFloat(out, floatBuf, Float.MAX_VALUE);
            assertEquals(Float.MAX_VALUE, Float.parseFloat(getWrittenString()));

            out.reset();
            JsonNumberWriter.writeFloat(out, floatBuf, Float.MIN_VALUE);
            assertEquals(Float.MIN_VALUE, Float.parseFloat(getWrittenString()));
        }

        @Test
        void testNonFiniteFloatsProduceJsonNull() throws IOException {
            JsonNumberWriter.writeFloat(out, floatBuf, Float.NaN);
            assertEquals("null", getWrittenString());

            out.reset();
            JsonNumberWriter.writeFloat(out, floatBuf, Float.POSITIVE_INFINITY);
            assertEquals("null", getWrittenString());

            out.reset();
            JsonNumberWriter.writeFloat(out, floatBuf, Float.NEGATIVE_INFINITY);
            assertEquals("null", getWrittenString());
        }
    }

    // ==========================================
    // DOUBLE TESTS
    // ==========================================

    @Nested
    @DisplayName("Double JSON Serialization")
    class DoubleTests {

        @Test
        void testZeroes() throws IOException {
            JsonNumberWriter.writeDouble(out, doubleBuf, 0.0);
            assertEquals("0.0", getWrittenString());

            out.reset();
            JsonNumberWriter.writeDouble(out, doubleBuf, -0.0);
            assertEquals("-0.0", getWrittenString());
        }

        @ParameterizedTest
        @ValueSource(doubles = {1.0, -1.0, 3.141592653589793, -0.00000000012345, 1.23456789e100, -9.87654321e-200})
        void testStandardDoubles(double val) throws IOException {
            JsonNumberWriter.writeDouble(out, doubleBuf, val);
            assertEquals(val, Double.parseDouble(getWrittenString()), 1e-12);
        }

        @Test
        void testDoubleBoundaries() throws IOException {
            JsonNumberWriter.writeDouble(out, doubleBuf, Double.MAX_VALUE);
            assertEquals(Double.MAX_VALUE, Double.parseDouble(getWrittenString()));

            out.reset();
            JsonNumberWriter.writeDouble(out, doubleBuf, Double.MIN_VALUE);
            assertEquals(Double.MIN_VALUE, Double.parseDouble(getWrittenString()));
        }

        @Test
        void testNonFiniteDoublesProduceJsonNull() throws IOException {
            JsonNumberWriter.writeDouble(out, doubleBuf, Double.NaN);
            assertEquals("null", getWrittenString());

            out.reset();
            JsonNumberWriter.writeDouble(out, doubleBuf, Double.POSITIVE_INFINITY);
            assertEquals("null", getWrittenString());

            out.reset();
            JsonNumberWriter.writeDouble(out, doubleBuf, Double.NEGATIVE_INFINITY);
            assertEquals("null", getWrittenString());
        }
    }

    // ==========================================
    // INTEGER & LONG TESTS
    // ==========================================

    @Nested
    @DisplayName("Integer & Long Serialization")
    class IntAndLongTests {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, -1, 9, 10, 99, 100, 123456, -987654, Integer.MAX_VALUE, Integer.MIN_VALUE})
        void testInts(int val) throws IOException {
            JsonNumberWriter.writeInt(out, intBuf, val);
            assertEquals(String.valueOf(val), getWrittenString());
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, -1L, 99L, 100L, 1234567890123L, -9876543210123L, Long.MAX_VALUE, Long.MIN_VALUE})
        void testLongs(long val) throws IOException {
            JsonNumberWriter.writeLong(out, longBuf, val);
            assertEquals(String.valueOf(val), getWrittenString());
        }
    }

    // ==========================================
    // POLYMORPHIC ROUTER TESTS
    // ==========================================

    @Nested
    @DisplayName("writeNumber Dispatcher")
    class WriteNumberRouterTests {

        @Test
        void testNullNumber() throws IOException {
            JsonNumberWriter.writeNumber(out, intBuf, longBuf, floatBuf, doubleBuf, null);
            assertEquals("null", getWrittenString());
        }

        @Test
        void testBoxedTypes() throws IOException {
            JsonNumberWriter.writeNumber(out, intBuf, longBuf, floatBuf, doubleBuf, Integer.valueOf(42));
            assertEquals("42", getWrittenString());

            out.reset();
            JsonNumberWriter.writeNumber(out, intBuf, longBuf, floatBuf, doubleBuf, Float.valueOf(3.14f));
            assertEquals(3.14f, Float.parseFloat(getWrittenString()), 1e-6f);

            out.reset();
            JsonNumberWriter.writeNumber(out, intBuf, longBuf, floatBuf, doubleBuf, Double.valueOf(2.718281828));
            assertEquals(2.718281828, Double.parseDouble(getWrittenString()), 1e-8);
        }
    }
}