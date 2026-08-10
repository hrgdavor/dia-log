# Zero-Allocation String Access

This document explains how zero-allocation writing of `String` data is achieved in Dia-Log when writing to an `OutputStream`.

## Key idea

The project avoids allocating a temporary UTF-8 `byte[]` for ASCII/LATIN-1 strings by reading the internal `String` bytes directly.

This is implemented in `core/src/main/java/hr/hrg/dialog/core/StringByteExtractor.java`.

## Why this aligns with JVM string interning

The JVM heavily caches (interns) the `String` objects that represent class names, method names, and file names.

When you call `ste.getClassName()` or `ste.getMethodName()` on a `StackTraceElement`, the JVM is not allocating a new `String` object. It is returning a reference to an already-existing string that was cached when the class was originally loaded.

Here is exactly how the JVM handles these strings under the hood:

- Class Loading & The Constant Pool: When the JVM loads a `.class` file, it reads the internal symbols (class names, method names, field names) into Metaspace. It then converts these C++ `Symbol*` structures into standard `java.lang.String` objects and places them in the JVM String Table (the intern pool).
- Exception Generation: When an exception is thrown and `fillInStackTrace()` is called, the JVM captures an array of native memory addresses (instruction pointers).
- StackTraceElement Construction: When those native pointers are translated into `StackTraceElement` objects, the JVM simply wires the `className`, `methodName`, and `fileName` fields to point directly to those pre-existing, interned `String` objects in the String Table.
- Class.getName() Caching: If you query `MyClass.class.getName()`, the `java.lang.Class` object actually holds a private transient `String name` field. It computes the string exactly once, caches it on the class instance, and returns that same object forever.

This means the `String` inputs used by Dia-Log for stack traces are already shared and stable, making zero-allocation direct byte access a very good fit.

## Implementation summary

`StringByteExtractor` exposes a single runtime strategy:

- `StringByteExtractor.getStrategy()` returns a `ByteWriter`
- The selected strategy is fixed once at class initialization
- If direct internal access is available, the strategy writes bytes from `String.value` directly
- Otherwise it falls back to `String.getBytes(StandardCharsets.UTF_8)`

## Strategy selection

Inside `StringByteExtractor.StrategyHolder`:

- It attempts a `MethodHandles.privateLookupIn(String.class, MethodHandles.lookup())`
- It tries to obtain these VarHandles:
  - `String.value` as `byte[]`
  - `String.coder` as `byte`
- It tests the handles on a dummy string for safety
- If successful, it binds the strategy to `writeVarHandle`
- If any exception occurs, it leaves the strategy as `writeClassic`

This means there is no runtime branch on every write call; the decision is made once during class load.

## Zero-allocation write path

The zero-allocation path is:

- `StringByteExtractor.writeAsciiDirect(out, s)` calls the active strategy
- `writeVarHandle(out, s, valueHandle, coderHandle)` executes
- It reads `coder` from the string
- If `coder == 0` (LATIN-1 / ASCII):
  - it reads the internal `byte[] value` directly
  - it writes that byte array to `out` with `OutputStream.write(byte[], 0, length)`
- No new `byte[]` is allocated for the string contents
- The only heap object used is the original `String` itself

This is safe only for LATIN-1 content, because the internal `String` representation is compact and already one byte per character.

## Fallback path

If the direct internal field access is unavailable, or if the string is not LATIN-1, the fallback is:

- `writeClassic(out, s)`
- `s.getBytes(StandardCharsets.UTF_8)`
- `out.write(bytes, 0, bytes.length)`

This path does allocate a temporary `byte[]` and is not zero-allocation.

## When direct access is available

Direct internal access depends on runtime Java access rules:

- On JDK 17+ with `--add-opens java.base/java.lang=ALL-UNNAMED`, the VarHandle lookup works and zero-allocation is enabled
- On JDK 25+ without module opens, the reflective VarHandle access is blocked and the fallback is used

So the zero-allocation path is best-effort, not guaranteed on every JVM configuration.

## Usage in Dia-Log

The zero-allocation writer is used by stack trace output code such as:

- `core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java`
- `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java`

Both classes obtain the writer with:

```java
public static final StringByteExtractor.ByteWriter stringWriteStrategy = StringByteExtractor.getStrategy();
```

and then write string fragments with:

```java
stringWriteStrategy.write(out, className.substring(0, classEnd));
stringWriteStrategy.write(out, methodName);
```

This allows the hot path of serializing stack frame data to avoid `String.getBytes()` allocations when the runtime supports it.

## Practical effect

For typical logging/tracing use:

- ASCII/LATIN-1 string writes can be zero-allocation
- The code still works safely on all JVMs via fallback
- The approach is optimized for hot paths where repeated string output would otherwise create many temporary byte arrays

## Important note

This file documents the current `StringByteExtractor` behavior. It does not imply that all string writes in Dia-Log are zero-allocation; only the `StringByteExtractor` fast path avoids the temporary UTF-8 byte[] allocation.
