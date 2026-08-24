package hr.hrg.dialog.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import hr.hrg.dialog.core.EscapedJsonStringWriter;
import org.slf4j.event.KeyValuePair;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev/diagnostic variant of {@link JsonLogWriter} that always reports missing
 * named placeholders. When the message contains {@code {name}} and the key is
 * neither a statement KV key nor an MDC key, a {@code "missingKeys"} field is
 * appended to the event, e.g.
 * <pre>{@code
 * log.atInfo().kv("user", username).log("User {user} logged from {ip}");
 * // {"...","msg":"User alice logged from {ip}","user":"alice","missingKeys":["ip"]}
 * }</pre>
 * <p>
 * There is no boolean configuration — this class <b>is</b> the switch: use it
 * directly or via {@link JsonAppenderDev} / {@link JsonAppenderRollingDev} in
 * dev environments, and the plain writer/appenders in production.
 * <p>
 * {@code null} values count as present; SLF4J positional {@code {}} and escaped
 * braces {@code {{name}}} are not treated as named keys. The {@code msg} field
 * itself is untouched (missing keys stay literal, jlx-compatible) — only the
 * additive {@code missingKeys} field is emitted.
 */
public class JsonLogWriterDev extends JsonLogWriter {

    /** Named placeholder {@code {name}} — skips {@code {}} and escaped braces. */
    private static final Pattern NAMED_PLACEHOLDER = Pattern.compile("(?<!\\{)\\{([^{}]+)\\}(?!\\})");
    public static final List<String> EMPTY_LIST_STRING = List.of();

    @Override
    protected void writeExtraFields(ILoggingEvent event, OutputStream out, List<KeyValuePair> pairs, Map<String, String> mdcMap) throws IOException {
        List<String> missing = findMissingKeys(event, pairs, mdcMap);
        if (missing.isEmpty()) {
            return;
        }

        out.write(',');
        EscapedJsonStringWriter.writeJsonStringOrNull(out, "missingKeys");
        out.write(':');
        out.write('[');
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            EscapedJsonStringWriter.writeJsonStringOrNull(out, missing.get(i));
        }
        out.write(']');
    }

    /**
     * Returns the named placeholders ({@code {name}}) present in the event's
     * message that are missing from the statement KV keys ∪ MDC keys, in
     * first-occurrence order, deduplicated.
     * <p>
     * This is a dev/diagnostic path: no zero-allocation effort is applied here.
     */
    static List<String> findMissingKeys(ILoggingEvent event, List<KeyValuePair> pairs, Map<String, String> mdcMap) {
        if (event == null || event.getFormattedMessage() == null) {
            return EMPTY_LIST_STRING;
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = NAMED_PLACEHOLDER.matcher(event.getFormattedMessage());
        while (m.find()) {
            String name = m.group(1);
            if (hasKey(pairs, name)) {
                continue;
            }
            if (mdcMap != null && mdcMap.containsKey(name)) {
                continue;
            }
            missing.add(name);
        }
        return List.copyOf(missing);
    }

    private static boolean hasKey(List<KeyValuePair> pairs, String name) {
        if (pairs == null) {
            return false;
        }
        for (KeyValuePair p : pairs) {
            if (p.key != null && p.key.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
