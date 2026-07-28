# Architecture Decision Records

This directory contains Architecture Decision Records (ADRs) for the Dia-Log project. Each ADR documents a significant architectural decision, its context, consequences, and current status.

## Index

| ADR                                                | Title                                             | Status   |
| -------------------------------------------------- | ------------------------------------------------- | -------- |
| [001](001-multi-module-maven-structure.md)         | Multi-module Maven structure                      | Accepted |
| [002](002-slf4j-2-loggingeventbuilder-wrapper.md)  | SLF4J 2.0 LoggingEventBuilder wrapper pattern     | Accepted |
| [003](003-automatic-mdc-cleanup.md)                | Automatic MDC cleanup via wrapper                 | Accepted |
| [004](004-key-value-pairs-vs-mdc.md)               | Key-value pairs vs MDC design                     | Accepted |
| [005](005-wyhash64-deterministic-hashing.md)       | Wyhash64 for deterministic hashing                | Accepted |
| [006](006-javastacksanitizer.md)                   | JavaStackSanitizer for deterministic stack traces | Accepted |
| [007](007-stackwhentraceenabled.md)                | stackWhenTraceEnabled() conditional stack trace   | Accepted |
| [008](008-jsonlogwriter-reusable-serialization.md) | JsonLogWriter as reusable serialization component | Accepted |
| [009](009-consoleappenderdev.md)                   | ConsoleAppenderDev for development                | Accepted |
| [010](010-traceid-generation.md)                   | TraceId generation with timestamp                 | Accepted |
| [011](011-noop-wrapper-pattern.md)                 | No-op wrapper pattern for disabled levels         | Accepted |
