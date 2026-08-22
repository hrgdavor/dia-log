# Performance Documentation Agent Instructions

## Two doc folders

- **`doc/perf/`** — the *consolidated performance advice*: teaching-oriented,
  basics-first documents that explain the performance model and the techniques
  (numbered `NN-topic.md`, starting from fundamentals and expanding). Geared
  towards explaining *why*, not recording what was done.
- **`doc/perf-exploration/`** — the *as-we-go records*: the `t{N}-{slug}.md`
  technique records, implementation plans, the Fory analysis, benchmark results
  and benchmark artifacts.

## Mandatory Learning Materials for Performance Work

Any change that touches a hot path — serialization, hashing, string access,
buffer management, stack-trace sanitization — must be accompanied by a learning
material document in `doc/perf-exploration/`.

### File format

Create a single markdown file named `t{N}-{slug}.md` where `{N}` is the next
available technique number and `{slug}` is a concise lowercase-hyphenated
description (e.g. `t9-bufferless-varhandle-number-writing.md`). The consolidated
explanation of the technique must also be folded into the relevant
`doc/perf/NN-topic.md` (add a section or extend the existing one).

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

Update `doc/perf-exploration/fory-perf-benchmark-results.md` when a new
technique produces measurable numbers. Add the new leg to the relevant tables,
quote the artifact file names, and note any JIT or environment caveats.

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

**Published diagnostic appenders stay in `src/main`.** `JsonAppenderDev` and
`JsonAppenderRollingDev` — and `JsonLogWriterDev`, which they instantiate — are
deliberate, *published* diagnostic tools (missing-key reporting during development)
and are the explicit exception to the "move non-production code to `src/test`"
cleanup rule: they must remain under `src/main/java`. `JsonLogWriterClassic` is a
pure benchmark comparison baseline and lives under `src/test/java`.

### Implementation plans

When a technique requires multi-file implementation, add
`doc/perf-exploration/t{N}-{slug}-implementation-plan.md` (exploration material
lives in `doc/perf-exploration/`) with concrete phases, file paths, acceptance
criteria, and a success metric.

### Reviewing changes

Before submitting a performance change, verify:
- [ ] Learning material exists in `doc/perf-exploration/`
- [ ] The consolidated advice in `doc/perf/` is updated (explain the technique there)
- [ ] Benchmarks are updated or added
- [ ] Zero-allocation property holds on the hot path (gc alloc rate ≈ 0 B/op)
- [ ] No dev variant was retrofitted with hot-path machinery
- [ ] Auto-generated derivatives were not edited directly
