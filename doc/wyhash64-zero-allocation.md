# Wyhash64 — Zero-Allocation Design

**Scope:** every zero-allocation effort inside `Wyhash64` (hashing) and its `Streaming`
class, with a deep dive into how `String` inputs are hashed without copying.

> **Status note:** this document reflects the current implementation at
> `core/src/main/java/hr/hrg/dialog/core/Wyhash64.java`. An earlier draft describing a
> `MemorySegment`/FFM approach for `char[]` and a scratch allocation in `finalHash()` was
> never shipped (the actual code uses manual byte packing and `finalHash()` allocates
> nothing) and has been removed. This document is the authoritative description.

---

## 1. The problem

Hashing a `String` the naive way allocates:

```
str.getBytes()        → byte[] allocation (copy of the characters)
  └─ hash(byte[])     → temporary bytes GC'd afterwards
```

For a stack trace with 50 frames averaging 60 characters, that is ~3 000 bytes allocated
and collected *per fingerprint*. Wyhash64 avoids this by reading the JVM's internal
`String` bytes directly and hashing them in place.

### Compact strings

Since JDK 9, a `String` is backed by a `byte[]` plus a `coder` byte:

| coder | encoding | bytes per char |
|-------|----------|----------------|
| `0`   | LATIN-1  | 1              |
| `1`   | UTF-16   | 2              |

The fast path reads `String.value` / `String.coder` via `VarHandle` from
`MethodHandles.privateLookupIn(String.class, …)` — **no defensive copy**.

---

## 2. Strategy selection — once, at class init

The hasher and the streaming updater are chosen **once** at class initialization, so the
per-call hot path never re-probes the JVM:

| field | type | chosen at |
|-------|------|-----------|
| `STRING_HASHER` | `StringHasher` | `static {}` |
| `STREAMING_STRING_UPDATER` | `StreamingStringUpdater` | `static {}` |

- If the `privateLookupIn` succeeds (i.e. `--add-opens java.base/java.lang=ALL-UNNAMED` is
  active), the **`Direct*`** strategies are installed.
- Otherwise (no `--add-opens`; on JDK 25+ `java.lang` is fully encapsulated), the
  **`Fallback*`** strategies are installed and `String` hashing goes through
  `toCharArray()` — correct, but allocating.

> **Requirement:** the zero-allocation `String` path needs
> `--add-opens java.base/java.lang=ALL-UNNAMED`. The project's surefire configuration and
> CI pass it; the README documents it for users.

---

## 3. Whole-String hashing — `hash(seed, String)`

`DirectStringHasher.hash(seed, str)` branches on the string's own `coder`:

| case | path | allocation |
|------|------|------------|
| `coder == 0` (LATIN-1) | `hash(byte[], 0, len)` — the internal bytes are already 1 byte/char | 0 |
| `coder == 1` (UTF-16), **LE storage** | `hashUtf16LeBytes(seed, value, 0, len)` — hashes the raw LE bytes | 0 |
| `coder == 1`, **BE storage** | `toCharArray()` + `hash(char[])` | 1 × `char[len]` |

### The UTF-16 byte order is a platform property — probe it, never assume

The internal UTF-16 byte order of a compact string is **not** fixed by the Java version:
recent JDKs store it in the **platform's native byte order** (little-endian on x86/ARM,
big-endian on big-endian CPUs). The code therefore probes the *actual* bytes once, at
class-init:

```java
byte[] probe = (byte[]) valueHandle.get("\u20ac");  // U+20AC, guaranteed coder=1
utf16Le = probe.length >= 2 && (probe[0] & 0xFF) == 0xAC;  // "AC 20" = LE, "20 AC" = BE
```

- LE platform → zero-allocation hash of the raw bytes.
- BE platform → the `char[]` repack path (correct; allocates — but BE CPUs are outside the
  project's JDK-25 requirement).

`hashUtf16LeBytes` mirrors `hashUtf16(char[])` exactly, including the wyr3 1-char tail, so
`hash(String) == hash(char[]) == hash(byte[] of UTF-16LE)` on every length.

> **Lesson:** never read `String.value` bytes directly for UTF-16 (coder=1) with a fixed
> byte order — detect it from the actual bytes once at class init.

---

## 4. Offset/slice hashing — `hash(seed, String, off, len)`

A slice of a UTF-16 string may itself be all-Latin-1 (its substring would compact to
coder=0 and hash as 1 byte/char), so the slice is hashed in **its own effective encoding**
to guarantee `hash(str, off, len) == hash(str.substring(off, off+len))`:

```java
if (coder == 0) {
    return hash(seed, value, off, len);            // Latin-1 bytes — 0 alloc
} else if (utf16Le) {
    if (sliceHasNonLatin1(value, off, len)) {      // scan high bytes
        return hashUtf16LeBytes(seed, value, off*2, len);   // UTF-16 slice — 0 alloc
    }
    return hashLatin1LeBytes(seed, value, off, len);        // strided low bytes — 0 alloc
} else {
    // big-endian storage — repack
}
```

The all-Latin-1 slice case uses **strided reads**: each char's value is its low byte at
the even index `2*i` of the UTF-16LE array, so the slice's Latin-1 byte sequence is packed
in place (`charsToLongLe` / `charsToIntLe` / `charsToWyr3Le`, and `updateLatin1LeBytes` /
`roundLeBytes` on the streaming side) — identical result to `hashLatin1(char[])`, with no
copy.

The `sliceHasNonLatin1` scan (odd indices of the slice) is O(len/2), allocation-free.

---

## 5. Streaming — `Wyhash64.Streaming`

- A single reusable **48-byte `byte[]` buffer** per `Streaming` instance (allocated once,
  reused across `update`/`reset` calls).
- `update(byte[], off, len)` — zero copy.
- `update(String)` / `update(String, off, len)` — routed through
  `STREAMING_STRING_UPDATER` with the same rules as hashing:
  - Latin-1 → `update(value, off, len)` (1 byte/char)
  - UTF-16LE whole string → `update(value, 0, len*2)` (raw LE bytes)
  - UTF-16 slice with non-Latin-1 → `update(value, off*2, len*2)`
  - all-Latin-1 slice → `updateLatin1LeBytes` (strided low bytes)
- `update(char[], off, len)` — auto-detects Latin-1 vs UTF-16 by scanning the range, then
  packs chars manually (`updateLatin1` / `updateUtf16`) — **no FFM API, no
  `MemorySegment`**.
- `update(CharSequence)` — Latin-1 single-byte packing via `charAt`.
- `updateByte(byte)` — single byte.
- `reset(seed)` — reuse the instance for the next fingerprint.
- **`finalHash()` allocates nothing.** The old byte[16] scratch was removed: the final
  16-byte window is read directly from the two relevant regions of the 48-byte buffer.

---

## 6. `char[]`, `byte[]`, `ByteBuffer`, `CharSequence`

| input | approach | allocation |
|-------|----------|------------|
| `byte[]`, `byte[] + off/len` | contiguous LE reads via `byteArrayViewVarHandle(long/int)` | 0 |
| `ByteBuffer`, `ByteBuffer + off/len` | contiguous LE reads via `byteBufferViewVarHandle` | 0 |
| `char[]`, `char[] + off/len` | manual byte packing; scans the range, Latin-1 (1 B/char) or UTF-16LE (2 B/char) | 0 |
| `CharSequence` (non-`String`) | Latin-1 packing via `charAt` (documented Latin-1-only path) | 0 |
| `CharSequence` that *is* a `String` | delegates to `STRING_HASHER` | 0 (fast path) |

---

## 7. Allocation inventory — the complete picture

**Zero allocation (fast paths, LE platforms, with `--add-opens`):**

- `hash(byte[])`, `hash(byte[], off, len)`, `hash(ByteBuffer)`
- `hash(char[])` and slices, both encodings (manual packing)
- `hash(String)` — Latin-1 and UTF-16
- `hash(String, off, len)` — Latin-1 slices, UTF-16 slices, and all-Latin-1 slices
- `hash(CharSequence)`
- `Streaming` after construction — all `update` overloads above, `finalHash()`

**Allocates (fallback paths only):**

- `String` on **big-endian CPUs** (coder=1): `toCharArray()` repack.
- `String` without `--add-opens` (incl. JDK 25+ encapsulation): `FallbackStringHasher` /
  `FallbackStreamingStringUpdater` use `toCharArray()`.
- The `Streaming` instance itself (one 48-byte buffer, reusable).

**Never allocates in the hot paths:** the sanitizer fingerprinting in
`JavaStackSanitizer` / `JavaStackTraceWriter` / the logback derivatives streams class and
method names through `Streaming` (via `StringByteExtractor` for output), so a fingerprint
of a typical stack trace is allocation-free.

---

## 8. How it is used in Dia-Log

`Wyhash64.Streaming` is the workhorse for stack-trace fingerprints:

```java
Wyhash64.Streaming stream = new Wyhash64.Streaming(0);
stream.update(throwable.getClass().getName());
addFromTrace(trace, filter, stream);      // per frame: className, DOT, methodName
long fingerprint = stream.finalHash();
```

`StringByteExtractor` (see `doc/zero-allocation-string-access.md`) applies the same
VarHandle trick for *writing* string bytes to an `OutputStream`.

---

## 9. Summary

| technique | where | allocation |
|-----------|-------|------------|
| `VarHandle` on `String.value`/`coder` | `DirectStringHasher`, `DirectStreamingStringUpdater`, `StringByteExtractor` | 0 |
| byte-order probe at class init | `utf16StorageIsLittleEndian` | 0 (once) |
| raw UTF-16LE byte hash | `hashUtf16LeBytes` | 0 |
| strided Latin-1 slice hash | `hashLatin1LeBytes`, `updateLatin1LeBytes` | 0 |
| manual `char[]` packing (no FFM) | `hashLatin1`, `hashUtf16`, `updateLatin1`, `updateUtf16` | 0 |
| contiguous LE reads | `byteArrayViewVarHandle`, `byteBufferViewVarHandle` | 0 |
| reusable 48-byte buffer | `Streaming` | 0 (after construction) |
| no-scratch `finalHash()` | reads `buf` regions directly | 0 |
| `toCharArray()` fallback | BE CPUs / no `--add-opens` | allocates |
