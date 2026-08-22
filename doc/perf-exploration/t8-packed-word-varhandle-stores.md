# T8 — Packed Word VarHandle Stores (partial-word overwrite)

**Source technique:** **Novel dia-log pattern** — a refinement of the packed
prefix stores from Apache Fory commit
[585eb16fda3b5729f6d6ca6d1be88fc42cd424d6](https://github.com/apache/fory/commit/585eb16fda3b5729f6d6ca6d1be88fc42cd424d6)
(*"feat(java): optimize json perf"*, PR #3871), extending it from "two words"
to "one VarHandle store per 8-byte window, with a full-store/partial-advance
tail".

## What Fory does

Fory's `Utf8WriterCodegen` precomputes field prefixes as little-endian packed
`long` constants and writes whole 8-byte windows with one
`LittleEndian.putInt64` store each. A prefix whose length is not a multiple of
8 still only stores the meaningful bytes of the last window — the tail is
written with a length-limited store rather than a second full 8-byte store.

## What dia-log did before

`JsonLogWriter` precomputed each fixed prefix into the `@CB.StrPacker`
constants (`KEY_X_W0..W3`, `KEY_X_LEN`) but wrote them through a length-dispatch
helper:

```java
private static int writePackedKey(byte[] buf, int pos, long w0, long w1, long w2, long w3, int len) {
    pos = WriteOps.writePackedLE(buf, pos, w0, Math.min(8, len));
    if (len > 8)  pos = WriteOps.writePackedLE(buf, pos, w1, Math.min(8, len - 8));
    if (len > 16) pos = WriteOps.writePackedLE(buf, pos, w2, Math.min(8, len - 16));
    if (len > 24) pos = WriteOps.writePackedLE(buf, pos, w3, len - 24);
    return pos;
}
```

`WriteOps.writePackedLE(buf, pos, value, n)` used the `byte[]` VarHandle view
(`LE_LONG.set`) for `n == 8` but fell back to a 1..7-byte store switch for the
partial tail, and `writePackedField` added the `','`. The call sites passed
`0L` for unused words.

## What dia-log does now

Every window — full word or partial tail — is a single little-endian VarHandle
store; the cursor advances by the actual byte length, so the tail's high
"overwrite" bytes are never emitted (flush respects `pos`):

```java
buf[pos++] = ',';                                  // prefix comma (inline)
WriteOps.LE_LONG.set(buf, pos, KEY_X_W0);          // full 8-byte window (VarHandle)
pos += 8;
WriteOps.LE_LONG.set(buf, pos, KEY_X_W1);          // tail: full 8-byte store
pos += 1;                                          // advance by the 1-byte tail
```

`WriteOps.LE_LONG` is the public `byte[]` VarHandle view; the main writer calls
`LE_LONG.set(buf, pos, word)` directly. `writePackedKey` and `writePackedField`
are gone; the capacity checks use the generated `KEY_X_LEN_BUF` constant (the
length rounded up to whole 8-byte word slots) so the overwrite store always has
a full slot reserved.

## Why it is faster

A partial tail previously cost 1..7 byte stores (`writePackedLE` switch) or an
`arraycopy` call. The overwrite trick is exactly **one wide store regardless of
tail length** — no per-length branch, no linear byte-store cost — and the
`byte[]` VarHandle view is **alignment-insensitive** (measured offset 0 vs 7:
no meaningful difference). The overwritten high bytes are harmless because the
next contiguous store overwrites them and the flush boundary is `pos`, not
`buf.length`.

## Benchmark (JMH)

`core/src/test/java/hr/hrg/dialog/core/PackedWordWriteBenchmark.java` writes an
8-byte word plus a partial tail (1..7 bytes) into a `byte[]` at aligned
(`offset=0`) and misaligned (`offset=7`) positions, comparing `arraycopy`,
generic `writePackedLE`, specialized `writePackedLE1..7`, and the full-store/
partial-advance trick. Artifact: `bench-packed-word-tails.txt`
(JDK 25, `-f 1 -wi 3 -i 5 -r 1s`).

`ns/op` (Average time, lower is better; `tailLen` = partial tail bytes):

| approach                      | tail=1 | tail=2 | tail=3 | tail=5 | tail=7 |
|-------------------------------|--------|--------|--------|--------|--------|
| fullWord (baseline, 8B store) | 0.91   | 0.94   | 0.91   | 0.87   | 0.89   |
| tailFull8AdvancePartial       | 1.12   | 1.12   | 1.14   | 1.11   | 1.24   |
| tailSpecialized (1..7 stores) | 1.08   | 1.07   | 1.50   | 1.81   | 2.86   |
| tailGeneric (runtime n)       | 1.19   | 1.84   | 2.22   | 2.42   | 2.81   |
| tailArraycopy (byte[])        | 5.31   | 5.33   | 5.27   | 5.32   | 5.36   |

The full-store/partial-advance shape is **flat (~1.1 ns) across tail lengths**,
while byte-store shapes grow linearly (specialized: 1.08 → 2.86 ns). `byte[]`
arraycopy is 4-5× slower everywhere. The real key tails (1, 2, 3, 5, 6 bytes)
are all within ~0.04 ns of the full-word baseline using the overwrite trick.

## Verification

- `core/src/test/java/hr/hrg/dialog/core/WriteOpsPackedTest.java` — correctness
  of `LE_LONG`, `writePackedLE1..7`, and the full-store/partial-advance
  semantics at **every byte offset 0..7** (alignment), asserting exact
  little-endian bytes, return positions, and untouched neighbor bytes.
- `logback/.../JsonLogWriterDirectBufferTest` — byte-identical output between
  the direct-buffer path (VarHandle stores) and the plain-stream fallback for
  plain, all-type, throwable, long-value and null events, plus two tiny-buffer
  (8-byte) cases that force every inline capacity check's grow branch.
- `logback/.../JsonLogWriterStrPackerTest` — compiled `KEY_*_W0/W1/LEN`
  constants still equal `JsonLogWriter.packWord(KEY_*, off)` / `KEY_*.length`.
