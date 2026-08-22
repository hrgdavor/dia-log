# Dia-Log Agent Guidelines

## Documentation Discipline — Early Phase (No External Users)

**This project is still in early phase. There are no external users, and no published contracts require change notifications.** When writing up inconsistencies, regressions, or proposed changes: do not generate documentation noise about "previous user impact" or "breaking for existing consumers." If a change *could* affect a hypothetical consumer, state the actual code behavior factually (e.g., "`errHash` is emitted as a flat top-level JSON key") and move on. Reserve impact language for genuine multi-user scenarios — not an internal logging library with no published API consumers.

## Change Discipline — In-Place Edits, No Backward-Compat Shims

**There is no code that uses this project yet.** Code and documentation changes
are therefore made **in place**: replace or delete the old shape directly —
rename, remove, or rewrite the call sites — rather than leaving a deprecated
alias, a wrapping delegator, or a dual old/new path. Do not keep anything
"unclean" solely for backward compatibility (no other callers exist to break).

Historical record is still welcome and expected: comments, Javadoc, code
examples, and the `## What dia-log did before` sections under
`doc/perf-exploration/` may describe the prior shape as a record of what was
used before. That is documentation of history, not a compatibility shim — keep
those accounts accurate, and update or remove them when they go stale (a
"before" description must never be presented as the current behavior).

## Performance-Critical Code Patterns

### Code Duplication is Intentional Micro-Optimization

The stack trace sanitization classes exhibit massive code duplication as a **deliberate performance optimization**:

- `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java` - **Canonical source** - modify this file ONLY
- `core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java` - Core, no-filter derivative
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java` - Filter-enabled, logback input
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackWriterLogback.java` - No-filter, logback input

**WORKFLOW**: Modify `JavaStackSanitizer.java` (canonical source), then run the generator to sync all derivatives:

```bash
mvn -pl project-automation compile exec:java \
    -Dexec.mainClass=hr.hrg.dialog.tools.StackSanitizerDerivativeGenerator
```

**DO NOT EDIT DERIVATIVE FILES DIRECTLY** - They are auto-generated and will be overwritten.

**DO NOT REFACTOR INTO A COMMON BASE CLASS** - This would:
- Introduce virtual dispatch overhead in hot paths
- Prevent JIT inlining optimizations  
- Potentially degrade logging performance

**Key locations to check when modifying**:
- Lines 15-300 in `JavaStackSanitizer.java` (main normalization logic)
- Lambda handling at `// LAMBDA_SUFFIX_FOR_CLASS` and `// LAMBDA_PREFIX_FOR_METHOD` markers
- Filter logic at `filter.test(className)` checks
- Fallback logic blocks marked with `// @sanitizer:begin` and `// @sanitizer:end`

### Hotspot Allocation Patterns to Avoid

When adding new features, check these allocation hotspots:

- `StringByteExtractor.writeClassic()` - allocates a `byte[]` per string (fallback path only;
  the VarHandle fast path is allocation-free)

**Dev/diagnostic variants are excluded from zero-allocation efforts.** Classes such as
`JsonLogWriterDev` (missing-key reporting), `JsonLogWriterClassic`, and benchmark fixtures
are tools, not hot paths — do **not** add micro-optimizations (guard scans, strided reads,
buffer reuse) to them; keep them straightforward and correct. Allocating in a dev variant
to avoid a scan that costs more than the allocation is the wrong trade.

**Published diagnostic appenders stay in `src/main`.** `JsonAppenderDev` and
`JsonAppenderRollingDev` — and `JsonLogWriterDev`, which they instantiate — are
deliberate, *published* diagnostic tools: overloads shipped with the library so users can
enable missing-key reporting during development. They are the explicit exception to the
"move non-production code to `src/test`" cleanup rule and must remain under
`src/main/java`. `JsonLogWriterClassic`, by contrast, is a pure benchmark comparison
baseline and lives under `src/test/java` (it was moved there during that cleanup).

Previously flagged and **already resolved — do not reintroduce**:

- `Wyhash64.Streaming.finalHash()` - no longer allocates a scratch `byte[16]`; the final
  16-byte window is read directly from `buf`
- `JsonLogWriter.writeJsonEventDirect()` - `allKeys` is lazily allocated, only when KV pairs exist
  **and** MDC is present (dedup against MDC is the only use)
- `Float.toString()` / `Double.toString()` in `JsonNumberWriter` - replaced by Ryu
  (`RyuFloat` / `RyuDouble`); also note `String.value` UTF-16 byte order is platform-native,
  never assume it (Wyhash64 probes it once at class init)
- `StringByteExtractor.writeLatin1()` - must batch contiguous ASCII runs into bulk
  `write(byte[], off, len)` calls; per-byte `write(int)` is measured ≈51× slower and was
  the dominant stack-trace-path cost (see `doc/logback-writer-comparison-benchmark-results.md`)
- `JsonAppender` / `JsonAppenderRolling` - events are assembled in a reusable
  `ReusableByteArrayOutputStream` (1 MiB, grows only to the longest event) and flushed to
  the real stream with one bulk write per event; do not reintroduce per-event buffers or
  direct per-byte writes to file/network streams

### Prefer Reusable Objects as Parameters over ThreadLocal

Scratch/reusable state on hot paths (hashers, number buffers, `byte[]` scratch) is
**passed as an explicit method parameter and owned by the caller** — do not hide it in a
`ThreadLocal`. Examples already in the codebase:

- The fingerprint entry points (`JavaStackSanitizer.fingerprint(...)`,
  `addFromTraceToOutputStream*AndFingerprint(...)`, and their derivatives) take a
  caller-supplied `Wyhash64.Streaming` and reset it internally (seed 0). There are
  deliberately **no** no-stream convenience overloads that would allocate a fresh hasher
  per call, and no hidden per-thread hasher.
- `JsonLogWriter` owns its `fingerprintStream` as a plain instance field — exactly like
  its reusable number buffers — and passes it into the single-pass methods.

Why:

- `ThreadLocal` is hidden state: per-thread memory overhead, invisible at the call site,
  and easy to corrupt through accidental re-entrancy (a nested call resets the same
  hasher and invalidates the outer hash).
- Caller-owned reuse is explicit and testable: the same instance is visibly shared across
  calls, and allocation/reuse is fully controlled where the call happens.
- A plain field is the zero-cost default; if an instance genuinely needs thread
  confinement, document the `@NotThreadSafe` contract instead of reaching for `ThreadLocal`.

The dev/diagnostic variants (`JsonLogWriterDev`, `JsonLogWriterClassic`, benchmark
fixtures) follow the dev-variant policy above and are not held to this rule.

### Thread Safety Requirements

- `DiaLoggerBase.prefix` must remain `volatile` if `prependPrefix()` exists
- `JsonLogWriter.stackTraceFilter` - configured once at startup via `setStackTraceFilter()`; no concurrent mutation, no additional synchronization required
- `JsonAppender.activeStream` - intentionally non-volatile; `writeOut()` snapshots it to a local variable to avoid splitting a single log event across concurrent stream changes
- `JsonAppenderRolling.activeStream` - intentionally non-volatile; `writeOut()` snapshots it to a local variable to avoid splitting a single log event across concurrent stream changes

### JSON Escape Discipline

All string keys passed to `writeFieldPrefixRawKey()` in `JsonLogWriter.java` are **JSON-escaped**
(quotes, backslash, control chars) via `EscapedJsonStringWriter`, because KV/MDC keys are user
input. Raw unescaped bytes are written only where the caller explicitly requests raw JSON
(`RawValue` / `RawJsonBytes` passthrough).

### Java Markdown Comments (`///`)

`///` line comments are an intentional lightweight **"Java markdown" doc style** used for
concise class-level notes in this project (e.g. the `JsonLogWriter` header at
`logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java`). They are **not** a
C#-style inconsistency and must **not** be converted to `/** */` Javadoc. Treat them as the
project's accepted short-form documentation; use standard Javadoc only where
`@param`/`@return`/`{@code ...}` documentation is genuinely needed for API-facing members.

---

## Build Commands (Maven)

### Environment (Windows) — real Maven + JDK 25 required

**Do NOT use `mvn` / `mvnd` from PATH.** `mvn` resolves to `D:\programs\cmd\mvn.bat`,
a shim that runs `mvnd --raw-streams`; the mvnd daemon fails in the agent sandbox with
`AccessDeniedException` on `C:\Users\hrg\.m2\mvnd\registry\...\registry.bin`. Use the
real Maven install instead:

```powershell
& "D:\programs\mvn\bin\mvn.cmd" <args>
```

**The enforcer requires JDK >= 25** (`RequireJavaVersion` allowed range `[25,)`). The
default `JAVA_HOME` is JDK 21, which fails the build. Set it explicitly before every
Maven invocation:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
```

Installed JDKs (all under `C:\Program Files\Java\`): `jdk-8.0.442.06-hotspot`, `jdk-17`,
`jdk-21`, `jdk-24`, `jdk-25`, `graalvm-jdk-25+37.1`.

### Commands

The `gpg` sign-artifacts plugin (`sign-artifacts` execution in the release profile)
can stall on interactive GPG passphrase entry when running `install`/`deploy` (no TTY),
hanging the build indefinitely. **Always pass `-Dgpg.skip=true`** to any `mvn install`
or `mvn deploy` invocation to avoid the stall:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -o -pl core install -DskipTests "-Dgpg.skip=true"
```

For ordinary compile/test validation, prefer the reactor (no install needed) so the
gpg plugin never runs:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -o -pl core,logback test "-Dsurefire.failIfNoSpecifiedTests=false"
```

The derivative generator (after editing `JavaStackSanitizer.java`) uses the same
environment:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -pl project-automation compile exec:java "-Dexec.mainClass=hr.hrg.dialog.tools.StackSanitizerDerivativeGenerator"
```

The CodeBuddy marker generator (`@CB.*` comments, e.g. `@CB.StrPacker` packed-key
blocks in `JsonLogWriter.java`) uses the same environment; see
`doc/codebuddy-strpacker.md` for the marker conventions and examples:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -pl project-automation compile exec:java "-Dexec.mainClass=hr.hrg.dialog.tools.CodeBuddy"
```

PowerShell note: unquoted `-Dkey=value` flags are sometimes mangled by the shell
(dropping the `-D` prefix); quote each one, e.g. `"-Dtest=MyTest"`.

To check test results when `-q` hides the summary:

```powershell
Get-ChildItem core\target\surefire-reports,logback\target\surefire-reports -Filter "*.txt" |
  ForEach-Object { Select-String -Path $_.FullName -Pattern 'Tests run:' | ForEach-Object { $_.Line } }
```

## File Locations Reference

**Core classes**:
- `core/src/main/java/hr/hrg/dialog/core/`

**Logback adapter classes**:
- `logback/src/main/java/hr/hrg/dialog/logback/`

**Test classes**:
- `core/src/test/java/hr/hrg/dialog/core/`
- `logback/src/test/java/hr/hrg/dialog/logback/`

**Benchmarks**:
- `core/src/test/java/hr/hrg/dialog/core/*Benchmark.java`
- `logback/src/test/java/hr/hrg/dialog/logback/*Benchmark.java`