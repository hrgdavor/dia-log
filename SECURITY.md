# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | ✅ Latest release  |
| < 1.0   | ❌ Not supported   |

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

To report a vulnerability privately:

- Open a **private security advisory** at
  <https://github.com/hrgdavor/dia-log/security/advisories/new>, or
- Email the maintainer directly at **hrgdavor@gmail.com**.

Please include:

- The affected version(s)
- A minimal reproduction (code or logback configuration)
- Impact description (e.g., malformed JSON injection, denial of service via
  log flooding, information disclosure)

You can expect an acknowledgement within 3 business days and a fix/release
timeline in the reply. Security fixes are released as patch versions and noted
in `CHANGELOG.md`.

## Known Security Notes

- `JsonLogWriter` escapes all user-supplied keys and values (quotes, backslash,
  control characters) — do not regress this when modifying the writer
  (`AGENTS.md` → JSON Escape Discipline).
- The `stackTraceFilter` predicate and appender streams are configured at
  startup; treat logback.xml as a trusted configuration file.
