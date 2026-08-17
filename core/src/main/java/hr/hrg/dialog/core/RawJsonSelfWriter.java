package hr.hrg.dialog.core;

import java.io.OutputStream;

/**
 * Implemented by values that serialize themselves directly as raw JSON to an
 * {@link OutputStream}, bypassing Jackson. The implementor is responsible for
 * writing valid, complete JSON (including escaping).
 */
public interface RawJsonSelfWriter{
    void writeJson(OutputStream out);
}