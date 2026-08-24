package hr.hrg.dialog.logback;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the compile-time constants emitted by the {@code @CB.StrPacker}
 * generator (project-automation CodeBuddy) against the runtime implementation:
 * every {@code KEY_*_W0}/{@code KEY_*_W1} literal must equal
 * {@code JsonLogWriter.packWord(KEY_*, off)} and every {@code KEY_*_LEN} must
 * equal the UTF-8 byte length of the {@code KEY_*} String. Runs against the
 * compiled class, so it also catches drift between the committed source and
 * what was actually built.
 */
class JsonLogWriterStrPackerTest {

    private static final List<String> KEYS = List.of(
            "TS", "LEVEL", "LOGGER", "THREAD", "MSG",
            "ERR_CLASS", "ERR_MESSAGE", "ERR_HASH", "STACK");

    private static final List<String> VALUES = List.of(
            "JSON_NULL", "JSON_TRUE", "JSON_FALSE");

    @Test
    void packedKeyConstants_matchRuntimePackWord() throws Exception {
        Method packWord = JsonLogWriter.class.getDeclaredMethod("packWord", byte[].class, int.class);
        packWord.setAccessible(true);

        for (String key : KEYS) {
            byte[] bytes = ((String) field("KEY_" + key).get(null)).getBytes(StandardCharsets.UTF_8);
            String prefix = "KEY_" + key;

            assertEquals((long) packWord.invoke(null, bytes, 0),
                    field(prefix + "_W0").getLong(null), prefix + "_W0");
            if (hasField(prefix + "_W1")) {
                assertEquals((long) packWord.invoke(null, bytes, 8),
                        field(prefix + "_W1").getLong(null), prefix + "_W1");
            } else {
                assertTrue(bytes.length <= 8, prefix + " must have no W1 for <= 8 byte key");
            }
            assertEquals(bytes.length, field(prefix + "_LEN").getInt(null), prefix + "_LEN");
        }
    }

    @Test
    void packedValueConstants_matchRuntimePackWord() throws Exception {
        Method packWord = JsonLogWriter.class.getDeclaredMethod("packWord", byte[].class, int.class);
        packWord.setAccessible(true);

        for (String value : VALUES) {
            byte[] bytes = ((String) field(value).get(null)).getBytes(StandardCharsets.UTF_8);
            String prefix = value;

            assertEquals((long) packWord.invoke(null, bytes, 0),
                    field(prefix + "_W0").getLong(null), prefix + "_W0");
            assertEquals(bytes.length, field(prefix + "_LEN").getInt(null), prefix + "_LEN");
        }
    }

    private static Field field(String name) throws Exception {
        Field f = JsonLogWriter.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static boolean hasField(String name) {
        try {
            JsonLogWriter.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
