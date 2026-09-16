< 10000`) — for short values those 1–4 well-predicted branches are cheaper than
any branchless alternative, and short values (line numbers, levels, durations)
are exactly the case that must be fast.

Result (isolated writer benchmark, ns/op): int `medium` 6.21 → 2.75, long
`timestamp` 7.89 → 4.40 vs `JsonNumberWriter`, and `jeaiiiQuad` is fastest on
every distribution for both int and long. Details:
[`t10-jeaiii-fast-writer.md`](../perf-exploration/t10-jeaiii-fast-writer.md).

See the dedicated guides for detailed comparisons:

- [07 — Int/Long Number Writing](./07-int-long-writing.md) — Integer number writing: direct buffer, VarHandle stores, multiple Java idioms (JDK `toString`, `JsonNumberWriter`, `JeaiiiFastWriter`, `JeaiiiPairsWriter`, scratch + arraycopy), performance and allocation comparison
- [08 — Float/Double Number Writing](./08-float-double-writing.md) — Floating-point number writing: Ryu bufferless writer, multiple Java idioms (JDK `toString`, `RyuFloat`, `RyuDouble`, scratch + arraycopy, per-byte `write(int)`), performance and allocation comparison

## Related techniques

- [t5-packed-digit-tables.md](t5-packed-digit-tables.md) — `DIGIT_QUADS`/`DIGIT_TRIPLES`
- [t6-packed-field-prefixes.md](t6-packed-field-prefixes.md) — packed `long` field prefixes
- [t7-cursor-locality-buffer-writer.md](t7-cursor-locality-buffer-writer.md) — whole-event `buf`/`pos`/`limit` cursor
- [t8-packed-word-varhandle-stores.md](t8-packed-word-varhandle-stores.md) — direct VarHandle stores + full-store/partial-advance tails
- [t9-bufferless-varhandle-number-writing.md](t9-bufferless-varhandle-number-writing.md) — bufferless offset number writing, `LE_INT` digit stores, no `arraycopy`
- [t10-jeaiii-fast-writer.md](t10-jeaiii-fast-writer.md) — division-free jeaiii int/long writer: `multiplyHigh` reciprocals, trailing-zero quads
- [t11-int-long-writer-comparison.md](t11-int-long-writer-comparison.md) — int/long writer comparison: multiple Java idioms (JDK `toString`, `JsonNumberWriter`, `JeaiiiFastWriter`, `JeaiiiPairsWriter`, scratch), scratch buffer vs direct, allocation analysis
- [t12-float-double-writer-comparison.md](t12-float-double-writer-comparison.md) — float/double writer comparison: multiple Java idioms (JDK `toString`, `RyuFloat`, `RyuDouble`, scratch, per-byte `write(int)`), scratch buffer vs direct, allocation analysis

</content>
</function>
</tool_call>
<function=update_goal>
<parameter=action>
complete