package hr.hrg.dialog.example;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the example {@link Main} end-to-end and verifies the console JSON output:
 * every line is valid JSON, all enabled levels appear, structured key/value pairs
 * are present, and exception events carry the {@code errHash} fingerprint.
 * (Planned coverage item from plans/analysis-report.md §11.)
 */
class ExampleIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void main_producesValidJsonLinesWithLevelsAndKv() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
            Main.main(new String[0]);
        } finally {
            System.setOut(originalOut);
        }

        String output = capture.toString(StandardCharsets.UTF_8);
        List<String> jsonLines = output.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("{"))
                .toList();

        assertFalse(jsonLines.isEmpty(), "expected JSON lines on stdout: " + output);

        Set<String> levels = new HashSet<>();
        boolean sawKv = false;
        boolean sawException = false;
        for (String line : jsonLines) {
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (Exception e) {
                fail("Failed to parse JSON line:\n" + line + "\n--- full stdout ---\n" + output, e);
                return;
            }
            assertTrue(node.isObject(), "each JSON line must be an object: " + line);
            assertTrue(node.has("ts"), "missing ts field: " + line);
            assertTrue(node.has("level"), "missing level field: " + line);
            assertTrue(node.has("logger"), "missing logger field: " + line);
            assertTrue(node.has("msg"), "missing msg field: " + line);

            levels.add(node.get("level").asText());
            if (node.has("method") && "GET".equals(node.get("method").asText())) {
                sawKv = true;
            }
            if (node.has("errHash")) {
                sawException = true;
            }
        }

        assertTrue(levels.containsAll(Set.of("DEBUG", "INFO", "WARN", "ERROR")),
                "all enabled levels must appear, got: " + levels);
        assertFalse(levels.contains("TRACE"), "TRACE must be filtered by root DEBUG level");
        assertTrue(sawKv, "structured key/value pairs (e.g. method=GET) must be present");
        assertTrue(sawException, "exception events must include errHash");
    }
}
