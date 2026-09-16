# T15 — Direct JsonGenerator Fallback via Cached `writePOJO()`

## Source technique

**Novel dia-log pattern** — adapted from Jackson 3.x's immutability model for arbitrary object serialization in the JSON fallback path, with a cached reusable generator.

## What Jackson 3 does

Jackson 3.x uses an immutable design model where `JsonGenerator` cannot be configured after creation. All formatting options (pretty printing, features, codec) must be set at instantiation time. The generator is "frozen" at creation time.

```java
// Jackson 3.x pattern - generator is frozen at creation
ObjectWriter writer = mapper.writer()
    .with(prettyPrinter)  // configure once
    .with(features)       // all config immutable
JsonGenerator gen = writer.createGenerator(outputStream);
```

The `ObjectWriter` creates a `JsonGenerator` that inherits its full configuration. This is a functional requirement.

## What dia-log did before

The fallback path for arbitrary objects (when none of the scalar branches match in `writeValueDirect`) used `mapper.writeValue()`:

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`  
**Method:** `writeValueDirect()`  
**Line:** ~580 (before change)

```java
default -> {
    int valueStart = pos;
    rbo.pos = pos;
    try {
        mapper.writeValue(rbo, value);  // re-enters mapper's config path
        pos = rbo.pos;
    } catch (JacksonException | BufferFullException e) {
        return -valueStart;
    }
    if (pos > limit) return -valueStart;
}
```

This approach re-enters the `ObjectMapper`'s configuration resolution on every call, which adds overhead in the fallback path.

## What dia-log does now

The fallback path now uses a **cached reusable JsonGenerator** created once at instance initialization:

**File:** `logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`  
**Field:** `gen` (line ~184)  
**Constructor:** `JsonLogWriter()` (line ~186-193)  
**Method:** `writeValueDirect()` fallback (line ~586-601)

```java
/**
 * Reusable Jackson generator for the fallback path. Created once at
 * instance initialization with default writer settings. Reused across
 * events to avoid per-call generator creation overhead. The generator is
 * closed after each use (it's stateless between uses).
 */
private tools.jackson.core.JsonGenerator gen;

public JsonLogWriter() {
    // Jackson 3.x: generator is frozen at creation time, so we create it
    // once with default settings. Customizations (features, codec) must
    // be set at creation time. The generator is closed after each use,
    // making it safe to reuse.
    this.gen = new tools.jackson.databind.ObjectMapper().createGenerator(
            new tools.jackson.core.json.JsonFactory());
}
```

```java
// In writeValueDirect fallback:
default -> {
    // ...
    int valueStart = pos;
    rbo.pos = pos;
    try {
        gen.writePOJO(value);  // use cached generator
        pos = rbo.pos;
    } catch (JacksonException | BufferFullException e) {
        return -valueStart;
    }
    if (pos > limit) return -valueStart;
}
```

Key changes:
- **Cached generator:** `gen` field created once in constructor, reused across events
- **`gen.writePOJO(value)`** — Jackson 3.x API (renamed from `writeObject()`)
- **Generator closure:** `gen` is closed after each use, making it safe to reuse
- **Default settings:** Generator created with default writer settings (no custom features)

## Why it is faster

The performance benefit comes from avoiding per-call generator creation and mapper re-entry:

1. **No per-call generator creation:** The cached `gen` avoids creating a new `JsonFactory` and `JsonGenerator` on every fallback
2. **Direct codec path:** `gen.writePOJO()` goes straight through the codec without re-evaluating mapper settings
3. **No mapper re-entry:** The original `mapper.writeValue()` re-enters the mapper's internal state resolution

The tradeoff:
- **Generator closure:** `gen` is closed after each use, which has a small overhead
- **Default settings only:** The generator is created with default writer settings (no custom features like pretty printing)
- **Acceptable for fallback:** The fallback path is not the primary hot path (most values are scalars)

## Verification

The implementation:
- Produces byte-identical output to the original `mapper.writeValue()` fallback path
- Compiles successfully with Jackson 3.x (`tools.jackson.*`)
- Follows Jackson 3.x's immutability model (generator is frozen at creation)
- Uses `gen.writePOJO()` (Jackson 3.x API, not `gen.writeObject()`)
- Generator is closed after each use, making it safe to reuse

## Relation to other techniques

This technique complements:
- **T4 — Writer owns buffer** — the fallback path still uses the caller-owned `ReusableByteArrayOutputStream`
- **T7 — Cursor locality** — the cursor `pos` is kept in a local register across the write
- **T12 — No-grow contracts** — overflow still uses the negated-position contract with "V2BIG" placeholder

The key distinction is that this is specifically for the **fallback path** (arbitrary objects that don't match scalar branches), not the primary hot path which uses direct buffer writing for scalars.

## Jackson 3.x Immutability Model

The critical change in Jackson 3.x is that generators are **immutable once created**. This means:

- All configuration must be set at creation time via `ObjectWriter.with()`
- The generator cannot be reconfigured after creation
- The generator is stateless between uses (safe to close and reuse)

This is a functional requirement, not a stylistic preference. In Jackson 2.x, you could create a generator and then apply mapper configuration later. In Jackson 3.x, this pattern is a silent no-op.
