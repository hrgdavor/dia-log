# 012: Remove key dedup between KV pairs and MDC

* **Status:** Accepted
* **Date:** 2026-08-22
* **Implementation Status:** Implemented

## Context

`JsonLogWriter` previously deduplicated keys between statement key-value (KV) pairs and MDC. When both contained the same key, only the KV value was emitted (KV wins). This was enforced by a reusable `StringHashSet` instance field (`allKeysReusable`) that tracked every KV key per event; the MDC pass then skipped any key already in the set.

`StringHashSet` was a purpose-built, open-addressing hash set — allocation-minimizing (steady-state zero allocation), resettable via `clear()`, and using `String.hashCode()` (cached in the String) to avoid re-hashing. It lived in `core/src/main/java/hr/hrg/dialog/core/StringHashSet.java` with a full test suite.

## Decision

Remove key dedup entirely. When a KV key collides with an MDC key, **both values are emitted** in the JSON output (KV first, then MDC). Delete `StringHashSet` and its test.

### Why

1. **Performance on the hot path.** The dedup set added work to every event that had both KV pairs and MDC: one `add(String)` per KV key (hash + probe + potential growth) and one `contains(String)` per MDC key. While `StringHashSet` was zero-allocation in steady state, it still consumed CPU cycles on hash probing and branch prediction in the serialization hot path — the most latency-sensitive code in the library. Removing it eliminates this work entirely.

2. **Downstream ingestion handles duplicates.** Modern log ingestion systems (Loki, Elasticsearch, Splunk, Datadog, JSONL parsers) accept duplicate object keys without error. Most apply last-wins semantics, some collect into arrays. Duplicate keys are a non-concern at the consumer — they are valid JSON (RFC 8259 does not forbid them) and every major parser handles them. The dedup was solving a problem that does not exist at the consumer.

3. **Dedup is the wrong layer.** Log serialization should be a fast, faithful representation of the event. Semantic decisions about which value "wins" for a duplicate key belong to the ingestion pipeline, which has full context and can apply domain-specific rules. The serializer should not silently drop data.

4. **The collision case is rare in practice.** Intentionally setting the same key in both MDC (thread-global) and KV (statement-local) is uncommon. When it happens, it is almost always intentional — the developer wants both values visible (e.g., a request-scoped `userId` in MDC and a statement-specific `userId` for a different entity). Silently dropping one loses information for no benefit.

## Consequences

* **Positive:** Simpler hot path (no key tracking, no probing, no conditional `contains` per MDC key); `StringHashSet` and its test deleted (less code to maintain); no risk of the dedup set interacting badly with future writer changes; faithful serialization — no data silently dropped.
* **Negative:** Rare duplicate keys may appear in JSON output when the same key is set in both KV and MDC. Operators whose ingestion truly cannot handle duplicates must filter at the consumer (the correct layer for that decision).

## References

- [ADR-004](./004-key-value-pairs-vs-mdc.md) — KV vs MDC design (updated to reflect new duplicate handling)
- `doc/allocation-benchmark-results.md` — prior benchmark of the dedup set allocation profile
