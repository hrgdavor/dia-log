# Implementation Plan: Missing-Key Detection in CustomJsonEncoder

## Status
Proposed — not yet implemented.

## Problem Alignment with Current Architecture

The cookbook doc `cookbook/missing-keys-warn.md` documents a feature that was implemented
in the now-removed `ConsoleAppenderDev` (see ADR-009). The current architecture uses
standard Logback appenders (`ConsoleAppender`, `RollingFileAppender`) configured with
`CustomJsonEncoder`, which delegates to `JsonLogWriter` for JSON serialization.

The original feature had two parts:
1. **Placeholder expansion** — replace `{name}` with actual values in human-readable output
2. **Missing-key detection** — warn when a `{name}` placeholder has no matching kv pair

Placeholder expansion was intentionally dropped in the encoder-based design: `{name}`
placeholders stay literal in the `msg` field, and structured values are emitted as
top-level JSON fields. This is by design (see `JsonLogWriter.writeJsonEvent()` at
`logback/.../JsonLogWriter.java:91`).

Missing-key detection, however, can be cleanly implemented in the encoder. The encoder
already tracks all kv keys in the `allKeys` set during serialization, and the raw message
template is available via `event.getMessage()`.

## Implementation Steps

### Step 1: Add `warnOnMissingKeys` config to JsonLogWriter

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`

- Add field `private boolean warnOnMissingKeys = false;` (default off — zero overhead)
- Add setter `setWarnOnMissingKeys(boolean)` and getter `isWarnOnMissingKeys()`
- Add a static helper `findMissingKeys(String messageTemplate, Set<String> presentKeys)`
  that scans for `{name}` placeholders:
  - Uses `event.getMessage()` to get the raw template (before formatting)
  - Skips empty `{}` and numeric `{0}`, `{1}` (positional args from SLF4J)
  - Returns a `List<String>` of placeholder names not found in `presentKeys`
- In `writeJsonEvent()`, after writing kv pairs (which populates `allKeys`), if
  `warnOnMissingKeys` is true and `findMissingKeys()` returns non-empty results,
  write a `"missingKeys"` array field to the JSON output

### Step 2: Expose config on CustomJsonEncoder

**File:** `logback/src/main/java/hr/hrg/dialog/logback/CustomJsonEncoder.java`

- Add `setWarnOnMissingKeys(boolean)` that delegates to `jsonWriter.setWarnOnMissingKeys()`
- This follows the same delegation pattern as all other config setters on this class

### Step 3: Add placeholder scanning logic

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`

- Scan `event.getMessage()` character by character for `{...}` patterns
- Extract the name between braces
- Skip if: empty, purely numeric (digits), or already present in `allKeys`
- Null-valued kv pairs are NOT missing — `allKeys` is only populated from
  `event.getKeyValuePairs()`, which only includes pairs that were actually added
  via `kv()` / `addKeyValue()`. A key with a null value still appears in the
  `KeyValuePair` list, so it counts as present.

### Step 4: Emit `missingKeys` field in JSON output

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`

- When missing keys are detected and `warnOnMissingKeys=true`, emit:
  ```json
  "missingKeys":["ip"]
  ```
  as a JSON array field, placed after the kv/MDC sections (before `err`/`source`)

- Rationale for structured field vs. Throwable: the original appender-based approach
  attached a `Throwable` and wrote a stack trace to the console. In a JSON encoder,
  a structured `missingKeys` array is the idiomatic representation — it can be
  filtered, counted, and alerted on by downstream log aggregators (Elasticsearch,
  Loki, etc.) without requiring stack-trace parsing.

### Step 5: Add tests

**File:** `logback/src/test/java/hr/hrg/dialog/logback/CustomJsonEncoderTest.java` (new file)
**File:** `logback/src/test/java/hr/hrg/dialog/logback/MissingKeysTest.java` (new file)

Test cases:
- All placeholders have matching keys → no `missingKeys` field
- Some placeholders missing → `missingKeys` array lists the missing ones
- Null-valued kv pair is NOT flagged as missing
- Numeric `{0}` and empty `{}` placeholders are ignored by the scanner
- `warnOnMissingKeys=false` (default) → never emits `missingKeys` field
- Multiple missing keys are all listed

### Step 6: Update example logback.xml

**File:** `example/src/main/resources/logback.xml`

- Add `<warnOnMissingKeys>true</warnOnMissingKeys>` to the dev/root appender
  configuration (optional — demonstrates the feature)

## Key Design Decisions

1. **Structured JSON field, not Throwable** — The original feature attached a
   `Throwable` with a stack trace. In JSON output, a `missingKeys` array is
   cleaner, queryable by log aggregators, and avoids the overhead of constructing
   a `Throwable` object on every log line with a missing key.

2. **Opt-in only** — `warnOnMissingKeys` defaults to `false`. When off, there is
   zero overhead (the placeholder scan is skipped entirely).

3. **Scans raw template, not formatted message** — Uses `event.getMessage()` (the
   raw string with `{name}` placeholders) rather than `event.getFormattedMessage()`.
   This is the template as written by the developer, before SLF4J/ Logback
   processes it.

4. **Only checks kv pairs, not MDC** — The `allKeys` set only contains kv-pair keys.
   MDC entries are written separately as top-level fields and are not expected to
   match message placeholders. This mirrors the original design where only `kv()`
   pairs were associated with `{name}` placeholders.

## Out of Scope

- Placeholder expansion in the JSON encoder (intentionally not implemented — values
  are emitted as structured fields, not substituted into the message)
- Text/ human-readable console output (no `ConsoleAppenderDev` replacement; users who
  want readable output use standard Logback `PatternLayoutAppender` with the encoder
  for JSON)
- Logback config file hot-reload behavior (already handled by `scan="true"` in
  `logback.xml`)
