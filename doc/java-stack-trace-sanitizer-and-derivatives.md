# JavaStackTraceSanitizer and Derivatives

## Naming note

In code, the canonical class is named JavaStackSanitizer.
In this document, JavaStackTraceSanitizer refers to that canonical implementation.

- Canonical source of truth: hr.hrg.dialog.core.JavaStackSanitizer
- Core no-filter derivative: hr.hrg.dialog.core.JavaStackTraceWriter (conceptually JavaStackWriter)
- Logback filter-enabled derivative: hr.hrg.dialog.logback.JavaStackSanitizerLogback
- Logback no-filter derivative: hr.hrg.dialog.logback.JavaStackWriterLogback

## Core design goals

The family of classes implements deterministic stack trace normalization for logging and grouping.

The main goals are:

- stable fingerprinting across line-number changes
- lambda normalization to reduce synthetic noise
- support for both hash and textual output paths
- parity between core Throwable input and logback proxy input

## Normalization rules shared by all variants

Each frame is represented as className.methodName with normalization.

- Class normalization:
  - if class name contains $$Lambda$, keep only the prefix before $$Lambda$
- Method normalization:
  - if class was a lambda class, method becomes lambda
  - otherwise, if method starts with lambda$, extract the original method name between first and second dollar sign when present
- Frame delimiter:
  - newline before each frame in the serialized representation
  - for JSON-string content paths, delimiter is escaped newline bytes (\\n)

## 1) JavaStackTraceSanitizer (JavaStackSanitizer)

Class: hr.hrg.dialog.core.JavaStackSanitizer

Role:

- canonical implementation and source of truth for normalization behavior
- supports filtering and fallback when all frames are filtered out

Methods:

- fingerprint(Throwable rootCause, Predicate<String> filter)
  - hashes exception class name + sanitized stack trace sequence
  - uses Wyhash64.Streaming and returns final long hash

- addFromTrace(StackTraceElement[] trace, Predicate<String> filter, Wyhash64.Streaming stream)
  - streams sanitized frames into hash stream
  - applies filter to frame class names
  - if no frame passes filter, falls back to top 3 raw class/method pairs

- addFromTraceElement(Wyhash64.Streaming stream, String className, String methodName)
  - low-level normalization routine for one frame payload
  - shared as building block by derivatives

- addFromTraceToStringBuffer(StackTraceElement[] trace, Predicate<String> filter, StringBuffer sb)
  - writes textual sanitized representation
  - includes same filter + fallback semantics as addFromTrace

- addFromTraceToOutputStream(StackTraceElement[] trace, Predicate<String> filter, OutputStream out)
  - writes textual sanitized representation to bytes with raw newline separator

- addFromTraceToOutputStreamJson(StackTraceElement[] trace, Predicate<String> filter, OutputStream out)
  - writes textual sanitized representation to bytes with escaped newline separator

- addFromTraceToOutputStreamWithNewline(StackTraceElement[] trace, Predicate<String> filter, OutputStream out, byte[] newlineBytes)
  - generic stream writer used by both output variants
  - includes same filter + fallback semantics

## 2) JavaStackWriter (JavaStackTraceWriter)

Class: hr.hrg.dialog.core.JavaStackTraceWriter

Role:

- no-filter derivative of JavaStackTraceSanitizer for StackTraceElement[]
- keeps normalization rules but removes filtering and fallback from behavior
- useful when caller wants full trace processing without package selection

Methods:

- fingerprint(Throwable rootCause, Predicate<String> filter)
  - filter argument is kept only for API compatibility
  - all frames are included

- addFromTrace(StackTraceElement[] trace, Wyhash64.Streaming stream)
  - streams normalized payload for all frames

- addFromTraceToStringBuffer(StackTraceElement[] trace, StringBuffer sb)
  - string output for all frames

- addFromTraceToOutputStream(StackTraceElement[] trace, OutputStream out)
  - byte output with raw newline separator

- addFromTraceToOutputStreamJson(StackTraceElement[] trace, OutputStream out)
  - byte output with escaped newline separator

- addFromTraceToOutputStreamWithNewline(StackTraceElement[] trace, OutputStream out, byte[] newlineBytes)
  - generic newline-configurable output method

## 3) JavaStackSanitizerLogback

Class: hr.hrg.dialog.logback.JavaStackSanitizerLogback

Role:

- logback-proxy adaptation of JavaStackTraceSanitizer
- keeps filter + fallback semantics from core sanitizer
- input is IThrowableProxy / StackTraceElementProxy[]

Methods:

- fingerprint(IThrowableProxy rootCause, Predicate<String> filter)
  - hashes proxy exception class name + sanitized proxy stack sequence

- addFromTrace(StackTraceElementProxy[] trace, Predicate<String> filter, Wyhash64.Streaming stream)
  - filter-enabled stream hashing with fallback when no frame passes

- addFromTraceToStringBuffer(StackTraceElementProxy[] trace, Predicate<String> filter, StringBuffer sb)
  - string serialization with same filter + fallback behavior

- addFromTraceToOutputStream(StackTraceElementProxy[] trace, Predicate<String> filter, OutputStream out)
  - output stream writer with raw newline separator

- addFromTraceToOutputStreamJson(StackTraceElementProxy[] trace, Predicate<String> filter, OutputStream out)
  - output stream writer with escaped newline separator

- addFromTraceToOutputStreamWithNewline(StackTraceElementProxy[] trace, Predicate<String> filter, OutputStream out, byte[] newlineBytes)
  - generic newline-configurable output writer with fallback

## 4) JavaStackWriterLogback

Class: hr.hrg.dialog.logback.JavaStackWriterLogback

Role:

- logback-proxy no-filter derivative aligned with JavaStackWriter semantics
- uses same normalization algorithm as canonical sanitizer
- processes all proxy frames (no filtering, no fallback)

Methods:

- fingerprint(IThrowableProxy rootCause, Predicate<String> filter)
  - filter argument is intentionally ignored for API compatibility
  - hashes all normalized proxy frames

- addFromTrace(StackTraceElementProxy[] trace, Wyhash64.Streaming stream)
  - streams normalized payload for all proxy frames

- addFromTraceToStringBuffer(StackTraceElementProxy[] trace, StringBuffer sb)
  - string serialization for all proxy frames

- addFromTraceToOutputStream(StackTraceElementProxy[] trace, OutputStream out)
  - byte output with raw newline separator

- addFromTraceToOutputStreamJson(StackTraceElementProxy[] trace, OutputStream out)
  - byte output with escaped newline separator

- addFromTraceToOutputStreamWithNewline(StackTraceElementProxy[] trace, OutputStream out, byte[] newlineBytes)
  - generic newline-configurable output method

## Why JavaStackWriterLogback can be treated as derivative of JavaStackWriter

This is true in the behavioral/API sense even though canonical algorithm authority remains JavaStackTraceSanitizer.

Two different derivation axes are useful:

- Algorithm lineage axis
  - canonical normalization rules originate in JavaStackTraceSanitizer
  - all three derivative classes should preserve those rules

- API/semantic lineage axis
  - JavaStackWriter defines no-filter/no-fallback semantics for StackTraceElement[]
  - JavaStackWriterLogback is the same semantics mapped onto StackTraceElementProxy[]
  - therefore JavaStackWriterLogback is a semantic derivative of JavaStackWriter

So the most precise statement is:

- JavaStackWriterLogback is behaviorally/API-wise derived from JavaStackWriter
- JavaStackWriterLogback is algorithmically derived from JavaStackTraceSanitizer

Both statements are simultaneously correct and not contradictory.

## Maintenance guidance

When changing normalization behavior:

1. update JavaStackTraceSanitizer first
2. update JavaStackWriter to keep no-filter parity
3. update JavaStackSanitizerLogback to keep filter+fallback parity on proxy input
4. update JavaStackWriterLogback to keep no-filter parity on proxy input
5. verify parity tests in both core and logback modules
