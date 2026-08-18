# Missing-Key Detection

Named placeholders like `{ip}` in a message refer to statement key/value pairs or MDC
entries. When the value is not provided, Dia-Log keeps the placeholder **literal** in the
message (it is never interpolated at log time) and, depending on the writer, can tell you
the key was missing.

## What the Production Writer Does

The production `JsonLogWriter` (used by `JsonAppender` / `JsonAppenderRolling`) writes the
message as-is and serializes key/value pairs as ordinary top-level fields. A missing
`{key}` simply stays literal — no extra field, no allocation, no behavior change:

```java
log.atInfo().kv("user", username).log("User {user} logged from {ip}");
```

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main","msg":"User alice logged from {ip}","user":"alice"}
```

`{ip}` is missing here and remains literal in `msg`. A `null` value counts as **present**
("missing, null is ok") — only truly absent keys are reported.

## Dev Variant — `JsonLogWriterDev`

For development, the writer overload `JsonLogWriterDev` (wired via `JsonAppenderDev` /
`JsonAppenderRollingDev`) **always** reports missing keys with an additive
`"missingKeys"` field — no boolean configuration, the class *is* the switch:

```xml
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppenderDev">
    <!-- Dummy encoder: the appender's writeOut() bypasses the encoder entirely
         (no per-event allocation); it is only required so
         OutputStreamAppender.start() succeeds. -->
    <encoder><pattern>%msg%n</pattern></encoder>
</appender>
```

```json
{"ts":1748765696789,"level":"INFO","logger":"com.example.MyClass","thread":"main",
 "msg":"User alice logged from {ip}","user":"alice","missingKeys":["ip"]}
```

Behavior:

- Named placeholders `{name}` are scanned in the message; a key is missing when it is
  neither a statement KV key nor an MDC key.
- `{}` SLF4J positional placeholders and escaped braces `{{name}}` are **not** named keys.
- `null` values count as present.
- The `msg` field is untouched (jlx-compatible); only the additive `missingKeys` field is
  emitted.
- Production output is **unaffected** — `JsonLogWriter`/`JsonAppender` emit no such field.

Use the dev appenders in dev/CI environments and the plain ones in production; nothing
else changes.
