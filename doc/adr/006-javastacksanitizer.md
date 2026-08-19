# 006: JavaStackSanitizer for deterministic stack traces

* **Status:** Accepted
* **Date:** 2026-07-26
* **Implementation Status:** Implemented

## Context

Stack traces are essential for debugging, but they present challenges for log aggregation and deduplication:
1. **Line numbers change**: When code is edited, line numbers shift, causing the same logical error to appear as a new, unique stack trace
2. **Synthetic frames clutter**: JVM-generated lambda frames (`$$Lambda$123/0x...`) and `jdk.internal.*` boilerplate frames add noise
3. **Non-deterministic output**: Stack traces include variable elements (line numbers, synthetic IDs) that prevent reliable fingerprinting

For error deduplication in systems like Elasticsearch or Loki, we need a deterministic representation of a stack trace that:
- Ignores line numbers (so minor code edits don't break deduplication)
- Normalizes lambda and synthetic method names
- Filters out framework/JDK boilerplate frames
- Produces the same hash for the same logical error across different builds

## Options Considered

1. **Use raw stack trace for hashing:** Simple, but line number changes break deduplication; lambda frames create noise.
2. **Sanitize stack trace before hashing:** Normalize frames to produce deterministic output, enabling reliable deduplication.

## Decision

Implement [`JavaStackSanitizer`](../core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java) to produce deterministic, hashable frame strings:

### Cleaning rules
1. **Drop JDK/internal frames**: `jdk.internal.*` and `sun.reflect.*` frames are excluded from the fingerprint
2. **Normalize lambda identifiers**: `$$Lambda$123/0x...` → `$$Lambda` (class name truncated at `$$Lambda$`)
3. **Extract original method from lambdas**: `lambda$originalMethod$number` → `originalMethod`
4. **Strip line numbers**: Only class name and method name are included in the fingerprint (line numbers excluded)
5. **Standardize native methods**: Native method calls are normalized

### Fingerprint generation
- Uses [`Wyhash64.Streaming`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java#L161) to hash the sanitized frames
- Includes the exception type name as the first element in the hash
- Falls back to top 3 raw frames if no app frames pass the filter (rare edge case)
- Returns a 16-character hex string suitable for indexing

### Configurable filter
- The `Predicate<String> filter` parameter allows callers to define which packages constitute "application frames"
- In [`JsonLogWriter`](../logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java#L234), the filter is currently `elem -> true` (all frames included), but can be restricted to app packages

## Consequences

* **Positive:** Same logical error produces the same `errHash` (top-level JSON field) across builds and deployments; stack trace deduplication works reliably in Elasticsearch/Loki; lambda and synthetic frame normalization reduces noise; fallback logic ensures a hash is always produced, even for system-level exceptions.
* **Negative:** Line numbers are lost in the fingerprint (they are still available in the raw stack trace if needed); the filter must be carefully configured to avoid including too many framework frames; sanitization adds CPU overhead during exception logging (mitigated by Wyhash64's speed).

## References

- [`JavaStackSanitizer.java`](../core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java)
- [`JavaStackSanitizerLogback.java`](../logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java)
- [`doc/stack.trace.sanitizer.md`](../doc/stack.trace.sanitizer.md)
