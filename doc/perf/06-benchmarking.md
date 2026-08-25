# 06 — Benchmarking: measuring, comparing, verifying

Every performance claim in this guide is backed by a JMH benchmark and a
byte-identity test. This page is the "how".

## The rules

1. **Byte-identical output is the contract.** Before any timing matters, the
   fast path must produce exactly the bytes of the plain-stream path for every
   event shape. `JsonLogWriterDirectBufferTest` enforces this (including
   tiny-buffer cases, which now assert the no-grow `BufferFullException`
   contract instead of forcing grow branches).
2. **Zero allocation is a property, not an aspiration.** Measure
   `gc.alloc.rate.norm` with `-prof gc`; the hot path must be ≈ 0 B/op.
   Allocating in a benchmark's *fallback* leg is a documented trade-off, never
   the production leg.
3. **Match the environment when comparing.** Same JDK, same
   `--add-opens java.base/java.lang=ALL-UNNAMED` on the launcher and forks,
   same warmup/measurement/forks. Without `--add-opens`, the string paths fall
   back to allocating and the SWAR scan is never reached.
4. **Sanity-check with an unchanged leg.** When measuring a change, include a
   benchmark that the change cannot affect (e.g. the escaping legs when
   changing numbers); if it moved, the environment moved, not your change.

## Running

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
# build the test classpath once: mvn -q dependency:build-classpath -pl logback -am -Dmdep.includeScope=test -Dmdep.outputFile=cp.txt
java -cp "core/target/test-classes;core/target/classes;logback/target/test-classes;logback/target/classes;$(cat logback/cp.txt)" `
     org.openjdk.jmh.Main hr.hrg.dialog.core.CursorBufferWriterBenchmark -wi 3 -i 5 -f 1 -t 1 `
     -jvmArgsAppend "--add-opens java.base/java.lang=ALL-UNNAMED"
```

The key suites and their recorded parameters:

| Suite | Purpose | Params (recorded) |
| --- | --- | --- |
| `ForyPerfComparisonBenchmark` | micro legs: escaping, latin1, int/long, prefixes | `-wi 4 -i 7 -f 1 -t 1 -prof gc` |
| `ForyPerfEventBenchmark` | whole-event direct vs stream vs classic | `-wi 4 -i 7 -f 1 -t 1 -prof gc` |
| `CursorBufferWriterBenchmark` | cursor-locality mixed workload | `-wi 3 -i 5 -f 1 -t 1` |
| `PackedWordWriteBenchmark` | packed tail-store strategies | `-f 1 -wi 3 -i 5 -r 1s` |
| `AllocationBenchmark` | allocation profile per primitive | `-wi 3 -i 5 -f 1 -t 1` |

## Pitfalls found in this project

- **Uninitialized logback MDC adapter.** A fresh `new LoggerContext()` has no
  MDC adapter, so `event.getMDCPropertyMap()` throws NPE on every call;
  `JsonLogWriter` swallows it, but an uninitialized benchmark measures the NPE
  path (~736 B/op, GC-inflated). Initialize a `LogbackMDCAdapter` in `@Setup`,
  matching production.
- **Comparing runs from different dates.** The deltas in
  [`fory-perf-benchmark-results.md`](../perf-exploration/fory-perf-benchmark-results.md)
  are cumulative over many changes; always re-run the unchanged legs as the
  control.
- **Compiler blackholes / JIT shape.** A benchmark's compilation-unit shape can
  change C2 inlining (see the 05 doc's JIT lesson). Keep benchmark classes
  small and stable, and treat ±20% swings on single-fork runs as noise unless
  confirmed by an unchanged control leg.

## Recording

New numbers go into
[`doc/perf-exploration/fory-perf-benchmark-results.md`](../perf-exploration/fory-perf-benchmark-results.md)
with the artifact file name; the artifact `.txt` files live in
[`doc/perf-exploration/`](../perf-exploration/) next to it.
