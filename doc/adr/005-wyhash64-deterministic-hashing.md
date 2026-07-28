# 005: Wyhash64 for deterministic hashing

* **Status:** Accepted
* **Date:** 2026-07-26

## Context

The project requires a fast, deterministic hash function for:
- Fingerprinting stack traces for deduplication in log aggregation systems (Elasticsearch, Loki)
- Generating unique identifiers for error grouping

Requirements for the hash function:
- **Deterministic**: Same input must always produce the same output, even across different JVM versions and builds
- **Fast**: Must not add significant overhead to log event processing
- **Good distribution**: Minimize collisions for similar but distinct stack traces
- **No external dependencies**: Should not require additional libraries beyond the JDK

Java's built-in `hashCode()` is not suitable because:
- It is not guaranteed to be consistent across JVM implementations
- It has poor distribution for certain input patterns
- It is not designed for cryptographic or deduplication purposes

## Options Considered

1. **Java built-in hashCode():** No external dependency, but not deterministic across JVM versions and has poor distribution.
2. **MurmurHash3:** Fast and well-distributed, but requires external library or custom implementation.
3. **Wyhash64:** Fast, deterministic, good distribution, and can be implemented as a standalone Java class with no external dependencies.

## Decision

Implement a standalone port of Wyhash64, a fast non-cryptographic hash function originally written in Zig. The implementation in [`Wyhash64`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java) includes:

- **Single-shot hashing**: `Wyhash64.hash(seed, byte[])` and `Wyhash64.hash(seed, ByteBuffer)` for fixed-size inputs
- **Streaming hashing**: `Wyhash64.Streaming` class for incrementally hashing variable-length inputs (used for stack trace fingerprinting)
- **Little-endian reads**: Uses `VarHandle` with `ByteOrder.LITTLE_ENDIAN` for efficient byte-to-long conversion
- **Default secret constants**: Matches Zig 0.15 `std.hash.Wyhash` constants for cross-language compatibility

The hash is used in [`JavaStackSanitizer.fingerprint()`](../core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java#L36) to produce a 16-character hex string (`err.hash`) for each exception.

## Consequences

* **Positive:** Very fast: Wyhash64 is designed for high throughput with minimal CPU instructions; deterministic across platforms: Same algorithm, same constants, same output; streaming support enables efficient hashing of long stack traces without buffering the entire trace; no external dependencies: Pure Java implementation using only `java.lang.invoke` and `java.nio`; cross-language compatibility: Matches Zig's Wyhash, enabling consistent hashing across polyglot systems.
* **Negative:** Not cryptographically secure (not needed for this use case, but worth documenting); the `Streaming` class has a 48-byte internal buffer, adding slight memory overhead; `VarHandle` usage requires Java 9+ (project already requires Java 21).

## References

- [`Wyhash64.java`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java)
- [`JavaStackSanitizer.fingerprint()`](../core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java#L36)
- [Zig std.hash.Wyhash](https://github.com/ziglang/zig/blob/master/lib/std/hash/wyhash.zig)
