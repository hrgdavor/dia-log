# Performance Documentation Agent Instructions

## Mandatory Learning Materials for Performance Work

Any change that touches a hot path — serialization, hashing, string access,
buffer management, stack-trace sanitization — must be accompanied by a learning
material document in `doc/perf/`.

### File format

Create a single markdown file named `t{N}-{slug}.md` where `{N}` is the next
available technique number and `{slug}` is a concise lowercase-hyphenated
description (e.g. `t7-cursor-locality-buffer-writer.md`).

### Required sections

1. **Title** — `# T{N} — {Short name}`
2. **Source technique** — Link to the upstream commit/PR that inspired the
   change, or state `**Novel dia-log pattern**` if it originated here.
3. **What Fory does** (or upstream source) — the original shape, with a code
   snippet.
4. **What dia-log did before** — the pre-change implementation, with file paths
   and line references.
5. **What dia-log does now** — the post-change implementation, with file paths
   and line references.
6. **Why it is faster** — the mechanism (register residency, reduced branches,
   bulk IO, etc.), not just a speedup number.
7. **Verification** — the tests and benchmarks that prove byte-identical output
   and no allocation regression.

### Benchmark results

Update `doc/perf/fory-perf-benchmark-results.md` when a new technique produces
measurable numbers. Add the new leg to the relevant tables, quote the artifact
file names, and note any JIT or environment caveats.

### Anti-patterns to never introduce

- Returning arrays or heap objects from capacity checks (`int[]`, `CursorBuffer`
  as a method parameter that mutates state and returns a cursor object).
- Hiding `buffer`/`position`/`limit` inside a `ThreadLocal` on the hot path.
- Adding `synchronized` or `volatile` to hot-path fields.
- Introducing virtual dispatch or helper-method calls between the local cursor
  and the byte stores in steady state.
- Adding micro-optimizations to dev/diagnostic variants (`JsonLogWriterDev`,
  `JsonLogWriterClassic`, benchmark fixtures). Keep those straightforward and
  correct.

### Implementation plans

When a technique requires multi-file implementation, add
`doc/perf/t{N}-{slug}-implementation-plan.md` with concrete phases, file paths,
acceptance criteria, and a success metric.

### Reviewing changes

Before submitting a performance change, verify:
- [ ] Learning material exists in `doc/perf/`
- [ ] Benchmarks are updated or added
- [ ] Zero-allocation property holds on the hot path (gc alloc rate ≈ 0 B/op)
- [ ] No dev variant was retrofitted with hot-path machinery
- [ ] Auto-generated derivatives were not edited directly
