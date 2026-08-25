# Step 4: Limit-aware `WriteOps` API — negated-position contract

> Parent overview: [`../no-grow-jsonlogwriter-placeholder.md`](../no-grow-jsonlogwriter-placeholder.md)
> Prereqs: Steps 1–3. Adds the `byte[] buf, int pos, int limit` overloads that the
> no-grow event assembly will call.

## Negated-position convention

All limit-aware methods return the **new position** on success, or the **negated
position** (`-pos`) on overflow. The caller always assigns the return value to its
`pos` local and checks `if (pos < 0)`:

```java
pos = writeEscapedJsonStringNoGrow(buf, pos, limit, value);
if (pos < 0) {
    pos = -pos;                           // restore pre-call position
    pos = writeTooLargeField(buf, pos);   // "V2BIG" — value overflow (Step 5b)
    // (standalone contexts use writeTooLargeAndClose → "}" instead)
}
```

Why negated (not `-1`):
- The caller accepts the result in the **same cursor local** — no separate
  "did it overflow?" boolean, no conditional assign.
- On overflow, negating recovers the exact position before the write, so the
  caller can overwrite with the placeholder from the right offset.
- **Partial writes into the buffer are harmless**: bytes may have been stored
  past the returned position before the margin check fired, but since `pos` is
  caller-owned and reverts to its pre-call value on overflow, that buffer garbage
  is never seen or flushed.

- [x] **4a.** `WriteOps.writeEscapedJsonStringNoGrow(byte[] buf, int pos, int limit, String s)`
  → returns new pos, **or `-pos`** on overflow. The **null branch stays in
  `WriteOps`** (so `DirectJsonStringWriter` needs no packed-null constants);
  non-null delegates to `DirectJsonStringWriter.writeJsonStringNoGrow`:

```java
public static int writeEscapedJsonStringNoGrow(byte[] buf, int pos, int limit, String s) {
    if (s == null) {
        if (pos + JSON_NULL_LEN_BUF > limit) return -pos;
        LE_LONG.set(buf, pos, JSON_NULL_W0);      // full 8-byte store, advance 4
        return pos + JSON_NULL_LEN;
    }
    return DirectJsonStringWriter.writeJsonStringNoGrow(buf, pos, limit, s);
}
```

**Convert `WriteOps.JSON_NULL` from `byte[]` to the `@CB.StrPacker` packed-long
pattern** (currently the only static literal in `WriteOps` still using `byte[]`):

```java
// @CB.StrPacker private static final JSON_NULL = `null`
private static final long JSON_NULL_W0 = 0x000000006c6c756eL;  // "null"
private static final int JSON_NULL_LEN = 4;
private static final int JSON_NULL_LEN_BUF = 8;
```

**Remove** the grow-capable RBO overloads
(`writeEscapedJsonString(ReusableByteArrayOutputStream, String)` and
`writeRaw(ReusableByteArrayOutputStream, …)`) — their only callers
(`JsonLogWriter.writeStringDirect` / `writeEscapedJsonString` /
`writeRawJsonBytes`) are replaced by the no-grow path. In-place deletion, no shim
(AGENTS.md). The pure `byte[] buf, int pos` overloads stay (4c).

- [x] **4b.** `WriteOps.writeRawNoGrow(byte[] buf, int pos, int limit, byte[] src, int off, int len)`
  → returns new pos, **or `-pos`** on overflow. Check against `limit` (not
  `buf.length`). All-or-nothing: check first, then `arraycopy`.
- [x] **4c.** Keep the pure `byte[] buf, int pos` overloads (already no-grow, used by
  benchmarks/callers that pre-size capacity).

## Validation

- Unit test: `writeEscapedJsonStringNoGrow` with a `limit` below the needed length returns
  a negative value whose magnitude equals the input `pos` (pre-call position restored).
- Existing `WriteOps` tests for the pure `byte[], int pos` overloads still pass.
