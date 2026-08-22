# 01 — Fundamentals: the three costs and the core disciplines

Before any technique makes sense, understand **what actually costs time** on a
serialization hot path and **what this project refuses to do** about it.

## The three costs

### 1. Allocation (the GC tax)

Every allocated object must eventually be collected. On a per-log-line hot
path, a handful of per-event allocations (a `String` from `valueOf`, a boxed
`Long`, a scratch `byte[]`, a per-call `StringBuilder`) is measurable — the GC
runs more often, and allocation *itself* is not free (TLAB bump + eventual
scan). The cost is non-linear under load: it is not 50 ns per allocation, it is
*whatever the collector does when the rate climbs*.

The rule here: **the hot path allocates nothing.** Reusable, caller-owned
objects (buffers, hashers, number scratch) are created once and reused; numbers
are formatted directly into the destination; strings are read with zero-copy
`String` access (via `--add-opens` VarHandle) instead of `getBytes()`.

### 2. Virtual dispatch (the call tax)

`OutputStream.write(int)` per byte, `write(byte[])` per field, a per-value
polymorphic call — every virtual call is an indirect branch that C2 cannot
inline, so `buf`/`pos`/`limit` cannot stay in registers across it. The JIT's
best inlining (the "why it's fast" of almost everything in this guide) only
works on *static*, *small*, *frequently-called* methods.

The rule: **on the hot path, the writer owns the buffer and the calls are
static.** `buf[pos++] = ...` is the ideal; a `static` helper that the JIT
inlines is acceptable; a virtual call into an `OutputStream` is not.

### 3. Memory traffic (the copy tax)

`System.arraycopy` of a 13-digit number from a scratch buffer, `arraycopy` of a
packed key's bytes, `String.getBytes()` producing a whole new array, per-field
`write(byte[])` that copies again — each copy moves bytes through the memory
hierarchy for no output benefit. Copies also break C2's view of the data (the
buffer contents are opaque to it).

The rule: **write once, directly.** Every byte of output should be stored into
the destination buffer exactly one time, at the place it belongs.

## The core disciplines

These are the project-wide rules that every technique below implements:

- **Caller-owned reuse, not `ThreadLocal`.** Scratch state (digit buffers,
  hashers, the event buffer) is passed as a parameter and owned by the caller.
  `ThreadLocal` is hidden state: per-thread memory, invisible at the call site,
  and corruptible by re-entrancy. A plain field on a `@NotThreadSafe` writer is
  the zero-cost default.
- **Capacity is checked inline, once, before the stores.** The classic pattern
  is `if (pos + need > limit) grow();` — the cold grow is inside the `if`, and
  the hot path is straight-line stores. Never return an array or an object from
  a capacity check (that defeats register residency).
- **Static data is packed at build time.** Field prefixes, `null`/`true`/
  `false`, digit tables — anything fixed is precomputed (constants, generated
  literals) so the hot path does arithmetic and stores, not lookups and copies.
- **Fast paths are byte-identical to the plain path.** Every optimization is
  validated against the stream fallback; there is exactly one byte output shape.
- **Dev variants stay simple.** `JsonLogWriterDev`, `JsonLogWriterClassic`, and
  benchmark fixtures are *tools*, not hot paths — they are deliberately not
  micro-optimized (an allocation there is often cheaper than the guard scan
  that avoids it).

## Where this leaves the hot path

The production event path is: one reusable buffer, one cursor, static helpers,
packed constants, direct stores — and zero allocation. The rest of this guide
builds each piece. The detailed `t{N}` records are in
[`doc/perf-exploration/`](../perf-exploration/).
