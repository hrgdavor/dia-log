# Step 5: `JsonLogWriter.writeJsonEventDirect` — no-grow event assembly

> Parent overview: [`../no-grow-jsonlogwriter-placeholder.md`](../no-grow-jsonlogwriter-placeholder.md)
> Prereqs: Steps 1–4. Wires the limit-aware writers into the main event method and
> adds the `"V2BIG"` placeholder logic.

**Signature change** (in-place, no shim): `writeJsonEventDirect` now **returns
`int`** (the final position) instead of `void`. The caller (`writeOut`) resets the
buffer, sets `eventBuffer.pos = pos`, and writes the newline.

```java
public int writeJsonEventDirect(ObjectMapper mapper, ILoggingEvent event,
        ReusableByteArrayOutputStream rbo) throws IOException
```

- [x] **5a.** `int limit = buf.length - RESERVE;` computed **once** at the top.
  **Remove all** `rbo.grow()` / `rbo.ensure()` calls. **Remove all** `buf = rbo.buf;
  limit = buf.length;` re-reads — `buf` and `limit` are stable. **Remove all**
  `rbo.publish()` / `rbo.resync()` calls. **Remove `rbo.reset()`** — the caller
  (`writeOut`) resets the buffer before calling; the method now assembles starting
  at `pos = 0` and returns the final position.
- [x] **5b.** Helpers take `(byte[] buf, int pos, int limit, …)`; on overflow they
  return `-pos`. Two finalizers are used depending on context:

  **Value overflow** (key prefix already written): restore `pos` to after the key
  prefix, write `"V2BIG"` without closing — the object stays open for subsequent
  fields:

```java
pos = writeEscapedJsonStringNoGrow(buf, pos, limit, value);
if (pos < 0) {
    pos = -pos;                           // restore to after key prefix
    pos = writeTooLargeField(buf, pos);   // "V2BIG" — no '}', stays open
    // continue to next field...
}
```

  **Key doesn't fit** (no room for `',' + key`): just close the object — the field
  is skipped and the already-written fields remain valid JSON:

```java
pos = writeTooLargeAndClose(buf, pos);     // "}" — closes the object
return pos;
```

  (Writing `"V2BIG"}` here would append a bare string after the previous value and
  produce invalid JSON; the `"V2BIG"` marker is only for value overflow.)

  The placeholder `"V2BIG"` (7 bytes) is stored with one packed-long store and the
  overwrite trick (full 8-byte store, advance by 7):

  **`writeTooLargeAndClose`** — writes `}` (1 byte) and closes the object. Used when
  a `','` + key prefix does not fit; the previous field is already complete, so
  closing is valid. `RESERVE` guarantees the byte fits:

```java
private static int writeTooLargeAndClose(byte[] buf, int pos) {
    buf[pos] = '}';
    return pos + 1;
}
```

  **`writeTooLargeField`** — writes `"V2BIG"` (7 bytes) without closing the object.
  Used when a value overflows but the key prefix was already written — subsequent
  fields can continue. The caller guarantees the placeholder fits (the value start
  is `<= limit`, and `RESERVE = 27 > 7`), so no capacity check is needed here:

```java
// @CB.StrPacker private static final PLACEHOLDER = `"V2BIG"`
private static final long PLACEHOLDER_W0 = 0x0022474942325622L; // "V2BIG"
private static final int PLACEHOLDER_LEN = 7;

private static int writeTooLargeField(byte[] buf, int pos) {
    WriteOps.LE_LONG.set(buf, pos, PLACEHOLDER_W0);
    return pos + PLACEHOLDER_LEN;
}
```

- [x] **5c.** **Per-field**: for each field after `ts`, the caller checks
  `pos + 1 + KEY_X_LEN_BUF > limit` (no room for `','` + key prefix) → call
  `writeTooLargeAndClose(buf, pos)` (writes `}`) and return. Otherwise write `','`
  + packed key prefix, then the value. On value overflow (limit-aware helper
  returns `-pos`), restore `pos` to after the key prefix and call
  `writeTooLargeField(buf, pos)` — this writes `"V2BIG"` without closing, so the
  loop continues to the next field.
  The `ts` field has **no preceding `,`** (the `{` is part of the key prefix), so
  its capacity check omits the `+ 1`:

```java
// First field: no preceding comma
if (pos + KEY_TS_LEN_BUF + JsonNumberWriter.MAX_LONG_BYTES > limit) {
    // Unreachable at the 64-byte minimum (limit >= 37 > 0 + 8 + 20 = 28);
    // defensive only. If it ever fired at pos == 0, emit a minimal "{}".
    buf[pos] = '{';
    buf[pos + 1] = '}';
    return pos + 2;
}
WriteOps.LE_LONG.set(buf, pos, KEY_TS_W0);
pos += KEY_TS_LEN;
pos = JsonNumberWriter.writeLong(buf, pos, event.getTimeStamp());
```

  Every subsequent field uses the `pos + 1 + KEY_X_LEN_BUF > limit` check (the
  `+ 1` is for the `','` that precedes the key prefix).

- [x] **5d.** **Numbers** (`int/long/float/double`): replace all `rbo.ensure(
  JsonNumberWriter.MAX_X_BYTES)` calls with `if (pos + MAX_X_BYTES > limit)`
  checks. Write directly via `JsonNumberWriter.writeXxx(byte[] buf, int pos, …)`.
  `Boolean` branch: replace `rbo.ensure(JSON_TRUE_LEN_BUF/JSON_FALSE_LEN_BUF)`
  with `if (pos + X_LEN_BUF > limit)` checks.

  The full new `writeValueDirect` signature and dispatch (used in the field loop).
  It now also takes `rbo` (the jackson / self-writer / raw branches stream through
  it), and every stream-mediated branch adds a `pos > limit` post-check — the RBO
  write methods throw only at `buf.length`, so a value that fits the buffer but
  overruns `limit` must still be treated as overflow:

```java
private static int writeValueDirect(byte[] buf, int pos, int limit,
        ReusableByteArrayOutputStream rbo, Object value, ObjectMapper mapper) throws IOException {
    switch (value) {
        case String s -> pos = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, s);
        case CharSequence cs -> pos = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, cs.toString());
        case Character ch -> pos = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, ch.toString());
        case Enum<?> e -> pos = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, e.name());
        case RawValue raw -> pos = writeRawValueDirect(buf, pos, limit, rbo, raw, mapper);
        case Long l -> {
            if (pos + JsonNumberWriter.MAX_LONG_BYTES > limit) return -pos;
            pos = JsonNumberWriter.writeLong(buf, pos, l);
        }
        case Integer i -> {
            if (pos + JsonNumberWriter.MAX_INT_BYTES > limit) return -pos;
            pos = JsonNumberWriter.writeInt(buf, pos, i);
        }
        case Short s -> {
            if (pos + JsonNumberWriter.MAX_INT_BYTES > limit) return -pos;
            pos = JsonNumberWriter.writeInt(buf, pos, s.intValue());
        }
        case Byte b -> {
            if (pos + JsonNumberWriter.MAX_INT_BYTES > limit) return -pos;
            pos = JsonNumberWriter.writeInt(buf, pos, b.intValue());
        }
        case Float f -> {
            if (pos + JsonNumberWriter.MAX_FLOAT_BYTES > limit) return -pos;
            pos = JsonNumberWriter.writeFloat(buf, pos, f);
        }
        case Double d -> {
            if (pos + JsonNumberWriter.MAX_DOUBLE_BYTES > limit) return -pos;
            pos = JsonNumberWriter.writeDouble(buf, pos, d);
        }
        case Number n -> pos = writeNumberDirect(buf, pos, limit, rbo, n);
        case Boolean b -> {
            if (b) {
                if (pos + JSON_TRUE_LEN_BUF > limit) return -pos;
                WriteOps.LE_LONG.set(buf, pos, JSON_TRUE_W0);
                pos += JSON_TRUE_LEN;
            } else {
                if (pos + JSON_FALSE_LEN_BUF > limit) return -pos;
                WriteOps.LE_LONG.set(buf, pos, JSON_FALSE_W0);
                pos += JSON_FALSE_LEN;
            }
        }
        case RawJsonSelfWriter w -> {
            int valueStart = pos;
            rbo.pos = pos;
            try {
                w.writeJson(rbo);
                pos = rbo.pos;
            } catch (BufferFullException e) {
                return -valueStart;
            }
            if (pos > limit) return -valueStart;   // wrote into the reserve: overflow
        }
        case RawJsonBytes rb -> pos = WriteOps.writeRawNoGrow(buf, pos, limit, rb.bytes(), 0, rb.bytes().length);
        default -> {
            int valueStart = pos;
            rbo.pos = pos;
            try {
                mapper.writeValue(rbo, value);
                pos = rbo.pos;
            } catch (BufferFullException e) {
                return -valueStart;
            }
            if (pos > limit) return -valueStart;   // wrote into the reserve: overflow
        }
    }
    return pos;
}
```

  Every branch returns **`-pos`** (or `-valueStart`) on overflow — the same
  negated-position contract as every other limit-aware writer. The **caller**
  decides how to finalize:
  - Field loop (5c): on `-pos`, restore to after the key prefix and call
    `writeTooLargeField(buf, pos)` — the object stays open for the next field.
  - Standalone single-value contexts (e.g. dev `writeExtraFields`): on `-pos`,
    restore and call `writeTooLargeAndClose(buf, pos)` — the object closes with `}`.

  Arbitrary `Number` (BigDecimal/BigInteger/custom — the wrapper types are caught
  above) and the non-String `RawValue` dispatch:

```java
/// Arbitrary Number → stream-mediated through rbo.
private static int writeNumberDirect(byte[] buf, int pos, int limit,
        ReusableByteArrayOutputStream rbo, Number n) throws IOException {
    int valueStart = pos;
    rbo.pos = pos;
    try {
        JsonNumberWriter.writeNumber(rbo, n);
        pos = rbo.pos;
    } catch (BufferFullException e) {
        return -valueStart;
    }
    if (pos > limit) return -valueStart;   // wrote into the reserve: overflow
    return pos;
}

/// Non-null RawValue → stream write through rbo (String backing stays raw via
/// writeRawValue/STRING_STRATEGY, matching current semantics).
private static int writeRawValueDirect(byte[] buf, int pos, int limit,
        ReusableByteArrayOutputStream rbo, RawValue raw, ObjectMapper mapper) throws IOException {
    Object backing = raw.rawValue();
    if (backing == null) {
        if (pos + JSON_NULL_LEN_BUF > limit) return -pos;
        WriteOps.LE_LONG.set(buf, pos, JSON_NULL_W0);
        pos += JSON_NULL_LEN;
        return pos;
    }
    int valueStart = pos;
    rbo.pos = pos;
    try {
        writeRawValue(rbo, raw, mapper);  // existing stream-based method
        pos = rbo.pos;
    } catch (BufferFullException e) {
        return -valueStart;
    }
    if (pos > limit) return -valueStart;  // wrote into the reserve: overflow
    return pos;
}
```

  Null backing → `"null"` literal; non-null → stream write through `rbo` (now
  no-grow) with try/catch for `BufferFullException` plus a `pos > limit` check,
  returning `-valueStart` on overflow so the caller finalizes with
  `writeTooLargeField` (field loop) or `writeTooLargeAndClose` (standalone).

- [x] **5e.** **Unsized values** (jackson `Object`, `RawJsonSelfWriter`, non-String
  `RawValue`) and the **`stack` field**: save `pos` before the try block, restore
  on overflow. Write through `rbo` itself (now no-grow) — it throws
  `BufferFullException` on overflow, and a successful write that lands past
  `limit` is also treated as overflow. These are value-level writes (the key
  prefix is already committed), so on overflow use `writeTooLargeField` (no
  close) — the object stays open for subsequent fields. The stack field is written
  via `JavaStackWriterLogback.addFromTraceToOutputStreamJsonAndFingerprint(arrProxy, rbo, …)`
  passed `rbo` directly; fingerprint (`Wyhash64.Streaming`) is still computed.

```java
int stackFieldStart = pos; // position of the stack VALUE (after `,"stack":`)
rbo.pos = pos;
try {
    rbo.write('"');
    if (throwableClassName != null) {
        STRING_STRATEGY.write(rbo, throwableClassName);
    }
    ... stack trace writers ...
    rbo.write('"');
    pos = rbo.pos;
} catch (BufferFullException e) {
    pos = writeTooLargeField(buf, stackFieldStart); // "V2BIG" — no '}', stays open
}
if (pos > limit) {
    pos = writeTooLargeField(buf, stackFieldStart); // wrote into the reserve
}
```

  The overflow path restores to `stackFieldStart` (before the opening `"`), so
  `"V2BIG"` replaces the entire stack value — no `}` written, object stays open
  for subsequent fields. The extra `pos > limit` check is required because the
  stack writer (and `STRING_STRATEGY`) throw only at `rbo.buf.length`, not at the
  event `limit`; without it a stack trace that fits the buffer but overruns
  `limit` would leave `pos` past the reserve.

- [x] **5f.** **Closing `}`**: `buf[pos++] = '}';` (no grow; `RESERVE` guarantees room).
- [x] **5g.** **`RawJsonBytes`**: bounded; `WriteOps.writeRawNoGrow` returns `-pos`
  on overflow (caller finalizes). `CharSequence` / `Enum` → escaped string path
  (limit-aware, quoted). A `RawValue` with `String` backing stays **raw** via
  `writeRawValue` (matching current `STRING_STRATEGY` semantics) under the
  try/catch + `pos > limit` guard in 5d.
- [x] **5h.** **`JsonLogWriterDev.writeExtraFields`**: change signature to
  `(event, byte[] buf, int pos, int limit, pairs, mdcMap)` returning `int`; write
  `missingKeys` via the limit-aware escaped writers. On overflow, return the
  **negated field-start** (`-fieldStart`, the position before the leading comma)
  so the caller restores and closes with `}` — the partial field is garbage that
  is never flushed. (In-place signature change per AGENTS.md — no shim.)

  Base class no-op:

```java
protected int writeExtraFields(ILoggingEvent event, byte[] buf, int pos, int limit,
        List<KeyValuePair> pairs, Map<String, String> mdcMap) {
    return pos;  // no-op
}
```

  Dev implementation:

```java
@Override
protected int writeExtraFields(ILoggingEvent event, byte[] buf, int pos, int limit,
        List<KeyValuePair> pairs, Map<String, String> mdcMap) {
    List<String> missing = findMissingKeys(event, pairs, mdcMap);
    if (missing.isEmpty()) return pos;
    int fieldStart = pos;                       // before the leading comma
    // Single margin check — missingKeys is small; on no-room, skip the field.
    if (pos + LIMIT_MARGIN > limit) return pos;
    pos = WriteOps.writeByte(buf, pos, ',');
    pos = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, "missingKeys");
    if (pos < 0) return -fieldStart;
    pos = WriteOps.writeByte(buf, pos, ':');
    pos = WriteOps.writeByte(buf, pos, '[');
    for (int i = 0; i < missing.size(); i++) {
        if (i > 0) pos = WriteOps.writeByte(buf, pos, ',');
        pos = WriteOps.writeEscapedJsonStringNoGrow(buf, pos, limit, missing.get(i));
        if (pos < 0) return -fieldStart;
    }
    pos = WriteOps.writeByte(buf, pos, ']');
    return pos;
}
```

  The caller (event assembly) treats a negative return by restoring `pos = -pos`
  and closing with `}` — the field is dropped cleanly.

## Validation

- `mvn -o -pl core,logback test` passes; normal-event output stays byte-identical
  (placeholder path is cold).
- New `JsonLogWriter*` tests (logback module):
  - tiny buffer (e.g. 64 B) + long message → output contains `"V2BIG"` and
    is valid JSON ending in `}`.
  - field whose **key** alone doesn't fit is skipped and the object closes with
    `}` (no dangling `,`, no stray `"V2BIG"`).
  - unsized value (jackson `Object`, `RawJsonSelfWriter`, non-String `RawValue`) →
    `"V2BIG"` (value replaced); no exception escapes; the field loop continues.
  - `stack` overflow → `"V2BIG"` for the whole stack field; no exception escapes.
