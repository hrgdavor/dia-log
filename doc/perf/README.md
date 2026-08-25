# Dia-Log Performance Guide

This folder is the **consolidated performance advice** for the dia-log hot
path: teaching-oriented, basics-first documents that explain *why* the code is
shaped the way it is. Read them in order — each builds on the previous.

The raw per-technique records (the `t{N}` notes, implementation plans, the Fory
commit analysis, benchmark results and artifacts) live in
[`doc/perf-exploration/`](../perf-exploration/) — that folder was written "as we
go"; this one explains.

## Reading order

1. [01-fundamentals.md](01-fundamentals.md) — the three costs and the core disciplines
2. [02-cursor-locality.md](02-cursor-locality.md) — the writer-owns-buffer pattern
3. [03-packed-word-stores.md](03-packed-word-stores.md) — static data packed into words, VarHandle stores
4. [04-number-writing.md](04-number-writing.md) — bufferless, VarHandle digit writing
5. [05-string-escaping.md](05-string-escaping.md) — SWAR word scans and length bands
6. [06-benchmarking.md](06-benchmarking.md) — measuring, comparing, and verifying

## The core principles

1. **The hot path is zero-allocation.** No `String`, no boxed number, no scratch
   `byte[]`, no per-call object — the only allocation on the hot path is the
   reusable event buffer itself, allocated once at a fixed capacity and reused
   across events (it never reallocates).
2. **Write straight into a caller-owned `byte[]` cursor.** `buf`/`pos`/`limit`
   live in registers across the whole event; `OutputStream` virtual dispatch and
   `arraycopy` are bypassed.
3. **Pack statically-known data into words and store with VarHandle.** Field
   names, JSON literals, digit groups — anything fixed is precomputed into
   `long`/`int` values and stored with one wide store.
4. **Keep the cursor visible to C2.** Hot-path helpers are tiny, `static`, and
   inline; no heap cursor objects, no `ThreadLocal`, no virtual calls between
   the cursor and the stores.
5. **Measure, and prove byte-identical output.** Every fast path must produce
   exactly the bytes the plain-stream path produces; JMH numbers and
   allocation profiles back every claim.

## Relation to `doc/perf-exploration/`

The exploration folder holds the history — the `t{N}` records (T1..T9), the
Fory commit analysis, the benchmark artifacts. When a new technique lands, its
`t{N}` record goes there, and its explanation is folded into the relevant
numbered topic here. If a topic here is ever in conflict with a `t{N}` record,
the topic is the current explanation and the record is the historical note.
