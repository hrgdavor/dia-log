# Dia-Log Design Documents

This directory contains design decisions and architectural documentation.

## Index

- [Async Logging Not Supported](async-not-supported.md) — Async appenders are intentionally not provided; zero-allocation design makes async unnecessary and problematic
- [ADR 001-013](../adr/README.md) — Architecture Decision Records

---

## Design Philosophy

Dia-Log prioritizes:
1. **Zero-allocation hot path** — No GC pressure during log generation
2. **Direct buffer writing** — Events written straight to output buffers
3. **Simplicity** — Fewer abstractions, easier to reason about
4. **Performance** — Measurable improvements over Jackson and Logback defaults

These principles guide all design decisions, including the decision not to support async logging.
