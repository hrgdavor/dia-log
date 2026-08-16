# 001: Multi-module Maven structure

* **Status:** Accepted
* **Date:** 2026-07-26
* **Implementation Status:** Implemented

## Context

Dia-Log is a diagnostic logging library that needs to serve multiple use cases:
- Core logging abstractions that are framework-agnostic
- Logback-specific appenders for JSON and development output
- A runnable example for users to test the library

The project requires a build system that can:
- Manage dependencies across modules
- Produce separate artifacts for different concerns
- Allow users to include only what they need (core-only or with logback)

## Options Considered

1. **Single-module Maven project:** Simpler build, but forces all dependencies (including Logback) on users who only need the core API.
2. **Multi-module Maven project:** More complex build, but clean separation of concerns and optional dependencies.

## Decision

Adopt a multi-module Maven project structure with three modules:

```
dia-log-root (pom)
├── core/        (dia-log-core)
├── logback/     (dia-log-logback)
└── example/     (dia-log-example)
```

- **core**: Contains `DiaLogger`, `LoggingEventBuilderWrapper`, `JavaStackSanitizer`, `Wyhash64`, and `TraceId`. Has no logging-framework-specific dependencies beyond SLF4J API.
- **logback**: Contains `CustomJsonEncoder` and `JsonLogWriter`. Depends on `core` and Logback.
- **example**: Runnable demo with `logback.xml`. Depends on both `core` and `logback`.

## Consequences

* **Positive:** Users can depend on `dia-log-core` alone if they only need the structured logging API without appenders; clear separation of concerns; example module serves as both documentation and integration test; Maven's dependency management ensures consistent SLF4J version across all modules.
* **Negative:** Slightly more complex build than a single-module project; users must understand which artifact to include based on their needs.

## References

- [`pom.xml`](../pom.xml)
- [`core/pom.xml`](../core/pom.xml)
- [`logback/pom.xml`](../logback/pom.xml)
- [`example/pom.xml`](../example/pom.xml)
