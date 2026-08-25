# 05 — String escaping and extraction: SWAR word scans

Strings (messages, logger names, stack-trace frames, KV keys) dominate real log
content. The naive per-byte scan/escape loop is the biggest single cost on the
string path. The fast path processes **8 bytes at a time** (SWAR) and writes
clean runs in bulk.

## The scan

Instead of classifying each byte with a branch, load 8 bytes as a word and test
whether any byte needs attention with ~5 integer operations and one branch
(the "SWAR" technique, T1):

```java
// any byte < 0x20 or == '"' or == '\\'? one or a few word tests per 8 bytes
long w = LE_LONG.get(buf, i);            // load 8 bytes
if ((w & MASK1) == 0 && (w & MASK2) == 0) { bulkStore(w); continue; }
// else fall to the per-byte escape emitter for this dirty block
```

A clean run of N bytes costs ~N/8 word tests + **one bulk store**, not N
per-byte branches and N `write(int)` calls.

## The length bands

Short strings pay a different tax: the scan dispatch itself. Writers split by
length band so the common cases (≤ some bytes, all-Latin-1, clean) take the
cheapest path, and only dirty/UTF-16 content falls through to the general
emitter. `StringByteExtractor` reads `String` bytes **zero-copy** via the
`--add-opens java.base/java.lang=ALL-UNNAMED` VarHandle fast path — no
`getBytes()` allocation — and `EscapedJsonStringWriter`/`DirectJsonStringWriter`
emit the escaped JSON.

## Two shapes

- The **stream shape** writes through `OutputStream` (an
  `ReusableByteArrayOutputStream` target now goes through its no-grow write
  methods, which throw `BufferFullException` on overflow). Used by the
  stack-trace path and the dev/classic variants. Clean runs must be
  batched into bulk `write(byte[], off, len)` calls — per-byte `write(int)` is
  measured ≈51× slower and was the dominant stack-trace-write cost.
- The **cursor shape** (`writeEscapedJsonString(byte[] buf, int pos, String)`)
  stores straight into the caller's buffer at `pos` and returns the advanced
  position, keeping the cursor in registers. The no-grow event assembly uses
  the limit-aware variant `writeEscapedJsonStringNoGrow(buf, pos, limit, s)`,
  which returns `-pos` on overflow.

## JIT lesson (worth repeating)

The escape hot path must stay in a **small, stable compilation unit**. Adding
cold sibling methods to `EscapedJsonStringWriter` silently changed C2's inline
decisions and measurably regressed the per-string path 4-6× (byte-identical
code!). The cursor-form machinery lives in its own class
(`DirectJsonStringWriter`) for exactly this reason. When a class sits on a hot
per-call path, keep it small.

## What it buys, measured

Clean strings: `latin1` ~2× faster than classic; dirty input still wins on the
direct path; 200-char clean strings escape at ~0.4 ns/char in direct mode.
Details in [`t1-swar-word-scan.md`](../perf-exploration/t1-swar-word-scan.md)
and [`t2-length-specialized-writers.md`](../perf-exploration/t2-length-specialized-writers.md).
