# Project Automation

Code generation tools for the dia-log project. Builds as a single fat jar (`-cli.jar`)
with a `Tools` dispatcher so each generator runs without Maven `exec:java`
arguments.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -o package -DskipTests "-Dgpg.skip=true" "-Djacoco.skip=true" -pl project-automation
```

Output: `project-automation/target/dia-log-project-automation-1.0.0-cli.jar`

## Run

All commands below require JDK 25 — the default `java` on PATH is JDK 8 and will
fail with `UnsupportedClassVersionError`. Use the full path:

```powershell
$java = "C:\Program Files\Java\jdk-25\bin\java.exe"
$jar  = "D:\wrk\java\dia-log\project-automation\target\dia-log-project-automation-1.0.0-cli.jar"
```

### Tools

| Tool | Purpose |
| --- | --- |
| `derivative` | Regenerate the 3 `JavaStackSanitizer` derivatives |
| `codebuddy`  | Apply `@CB.*` marker instructions to sources |
| `all`        | Run `derivative`, then `codebuddy` |

Each tool accepts an optional repo-root path as its first argument (defaults to
the current working directory):

```powershell
# Run a single tool
& $java -jar $jar derivative
& $java -jar $jar codebuddy

# Run both
& $java -jar $jar all

# Run against a specific repo root
& $java -jar $jar all D:\wrk\java\dia-log
```

### `derivative`

Regenerates the three sibling classes from the canonical source
`core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java`:

- `core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java`
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java`
- `logback/src/main/java/hr/hrg/dialog/logback/JavaStackWriterLogback.java`

**Never edit derivatives directly** — they are auto-generated and will be
overwritten. Edit `JavaStackSanitizer.java` (the canonical source), then run
`derivative` to sync.

### `codebuddy`

Scans the repository for `// @CB.<instruction> ...` marker comments and
rewrites the block beneath each marker. The marker line is the source of
truth; regeneration is idempotent and safe.

Currently registered instruction:

| Marker | Processor | Purpose |
| --- | --- | --- |
| `@CB.StrPacker` | `StrPacker` | Replace a runtime-initialized static field block with gen-time computed compile-time constants (packed UTF-8 words, length) |

See `doc/codebuddy-strpacker.md` for the `@CB.StrPacker` conventions and
examples.

## Developing

The Maven `exec:java` invocations still work without the package step (slower
startup, no fat jar needed):

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
& "D:\programs\mvn\bin\mvn.cmd" -pl project-automation compile exec:java "-Dexec.mainClass=hr.hrg.dialog.tools.StackSanitizerDerivativeGenerator"
& "D:\programs\mvn\bin\mvn.cmd" -pl project-automation compile exec:java "-Dexec.mainClass=hr.hrg.dialog.tools.CodeBuddy"
```
