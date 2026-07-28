# Zero-Allocation Wyhash64

## IMPORTANT Requirement

This uses `MethodHandles.privateLookupIn` which requires the module system to open `java.base/java.lang`. When running outside module path (classpath), add:

```
--add-opens java.base/java.lang=ALL-UNNAMED
```

or put the hasher in some module and use `java --add-opens java.base/java.lang=com.example.hasher`.


## Problem: Hidden Allocations in String Hashing

Hashing a `String` with a typical hash function involves:

1. `str.getBytes()` — allocates a new `byte[]` copy of the string's characters.
2. Passing that `byte[]` into the hash function.
3. The `byte[]` is then garbage-collected.

For every string you hash, you allocate `N` bytes (for a string of length `N`). In a hot path like hashing stack trace elements during logging, this allocation pressure is significant and visible in GC logs.

```
String → getBytes() → byte[] allocation → hash(byte[]) → GC
```

Additionally, for `CharSequence` inputs that are not `String`, you must iterate character-by-character, and for `char[]` you need either a copy or manual endian-aware packing.

## Solution: Bypass the Copy

The Java 9+ `String` internals store characters in a `byte[]` (compact strings), with a `byte coder` field indicating the encoding:

| Coder | Encoding | Bytes per char |
|-------|----------|---------------|
| `0`   | LATIN-1  | 1             |
| `1`   | UTF-16   | 2             |

By accessing these internal fields directly via `VarHandle`, we can read the raw bytes *without copying* them. The hash function processes them in-place.

```
String.value (byte[]) ──→ hash(byte[], off, len)   // Latin-1: 1 byte/char
                       ──→ hashUtf16(byte[], ...)   // UTF-16:  2 bytes/char
```

### How It Works

```java
// In Wyhash64 static initializer:
private static final VarHandle STRING_VALUE_HANDLE;
private static final VarHandle STRING_CODER_HANDLE;

static {
    var lookup = MethodHandles.privateLookupIn(String.class, MethodHandles.lookup());
    STRING_VALUE_HANDLE = lookup.findVarHandle(String.class, "value", byte[].class);
    STRING_CODER_HANDLE = lookup.findVarHandle(String.class, "coder", byte.class);
}

// Usage:
public static long hash(long seed, String str) {
    byte[] value = (byte[]) STRING_VALUE_HANDLE.get(str);
    byte coder   = (byte)   STRING_CODER_HANDLE.get(str);
    int len = str.length();

    if (coder == LATIN1) {
        return hash(seed, value, 0, len);       // 1 byte per char — direct hash
    } else {
        return hashUtf16(seed, value, 0, len);  // 2 bytes per char
    }
}
```

**No `byte[]` allocation. No `getBytes()` call. No GC pressure.**


## Zero-Allocation `char[]` via MemorySegment

For `char[]` arrays (which have no internal byte[] to read directly), we use the **Foreign Function & Memory (FFM) API**:

```java
MemorySegment seg = MemorySegment.ofArray(chars);  // zero copy

// Read multi-byte primitives directly:
long word = seg.get(ValueLayout.JAVA_LONG_UNALIGNED
    .withOrder(ByteOrder.LITTLE_ENDIAN), byteOffset);
int  word = seg.get(ValueLayout.JAVA_INT_UNALIGNED
    .withOrder(ByteOrder.LITTLE_ENDIAN), byteOffset);
```

`MemorySegment.ofArray(char[])` creates a **heap segment** that wraps the existing array — no copying, no allocation. The `JAVA_LONG_UNALIGNED` layout compiles to a single unaligned vector load on x86-64 (`movdqu`) and ARM64 (`ldr`), which is exactly what `sun.misc.Unsafe.getLong` did, but within the supported FFM API.

## Streaming: Incremental Zero-Allocation Hashing

The `Wyhash64.Streaming` class builds on these same techniques to allow incremental hashing across multiple data sources without intermediate allocations:

```java
Wyhash64.Streaming stream = new Wyhash64.Streaming(0);

// No allocation for any of these:
stream.update(className);     // String → internal byte[] directly
stream.update(methodBytes);   // byte[] → zero-copy
stream.update(methodName);    // String → internal byte[] directly
stream.update(fileChars);     // char[] → MemorySegment zero-copy

long hash = stream.finalHash();
```

### How Streaming Avoids Allocation

| Input type | Approach | Allocations |
|-----------|----------|-------------|
| `byte[]` | Direct read via VarHandle, buffer on stack | 0 |
| `String` (Latin-1) | Internal `byte[]` accessed via VarHandle | 0 |
| `String` (UTF-16) | Internal `byte[]` accessed via VarHandle | 0 |
| `char[]` | `MemorySegment.ofArray(char[])` + segment reads | 0 |
| `CharSequence` (non-String) | `charAt()` packed into stack buffer | 0 |

The only heap allocations are:
- The `Streaming` object itself (one time)
- A 48-byte `byte[]` internal buffer in the `Streaming` instance (one time, reused)
- In `finalHash()`, a 16-byte `byte[]` scratch buffer when the buffered tail is less than 16 bytes (rare; only when `totalLen > 16` and `bufLen < 16`)

## Performance Impact

### Before (allocation-heavy)
```java
// Each hash call: byte[] allocation + copy + GC
long h1 = Wyhash64.hash(0, className.getBytes());
long h2 = Wyhash64.hash(0, methodName.getBytes());
```

### After (zero-allocation)
```java
// Zero allocation
long h1 = Wyhash64.hash(0, className);
long h2 = Wyhash64.hash(0, methodName);
```

For a typical stack trace with 50 frames averaging 60 characters each:
- **Before**: 50 × 60 = 3000 bytes allocated and GC'd per hash
- **After**: 0 bytes allocated

### CPU-Level Optimizations

1. **`Math.unsignedMultiplyHigh(a, b)`** — The wyhash `mix` function requires a 128-bit multiply. This intrinsic compiles to a single `mulx` (x86-64) or `umulh` (ARM64) instruction — no Java-level shift-and-mask emulation.

2. **Unaligned vector loads** — `ValueLayout.JAVA_LONG_UNALIGNED` with `MemorySegment.get()` compiles to direct unaligned memory loads, avoiding shift-and-or loops for reading multi-byte words.

3. **VarHandle direct field access** — `MethodHandles.byteArrayViewVarHandle(long[].class, LE).get(array, offset)` generates the same assembly as `Unsafe.getLong(array, offset + base)` — no bounds check overhead beyond what the JIT can hoist.

## Usage Examples

### Hashing a stack trace element
```java
long hash = Wyhash64.hash(0, ste.getClassName() + "." + ste.getMethodName());
```
This still allocates the concatenated String. Better:

```java
Wyhash64.Streaming s = new Wyhash64.Streaming(0);
s.update(ste.getClassName());
s.update(".");
s.update(ste.getMethodName());
long hash = s.finalHash();  // zero allocation
```

### Hashing a log message key
```java
// Before: allocates byte[]
long hash = Wyhash64.hash(0, logKey, 0, logKey.length());

// After: zero allocation via String overload
long hash = Wyhash64.hash(0, logKey);
```

### Hashing a reusable char buffer
```java
char[] buffer = new char[256];
int len = fillFromSource(buffer, 0);

// Before: requires wrapping or copying
long hash = Wyhash64.hash(0, new String(buffer, 0, len));  // allocates String + byte[]

// After: zero allocation
long hash = Wyhash64.hash(0, buffer, 0, len);  // MemorySegment zero-copy
```

## Summary

| Technique | API | Allocation |
|-----------|-----|-----------|
| String internal byte[] access | `MethodHandles.privateLookupIn` + VarHandle | 0 |
| char[] multi-byte reads | `MemorySegment.ofArray(char[])` + ValueLayout | 0 |
| Compact string coder check | VarHandle on `String.coder` | 0 |
| 128-bit multiply | `Math.unsignedMultiplyHigh` (intrinsic) | 0 |
| Streaming accumulation | `Streaming` internal 48-byte buffer | 0 (amortized) |

The result: **production-safe, zero-allocation wyhash64 hashing** using only standard Java APIs, suitable for hot paths in logging, tracing, and telemetry systems.