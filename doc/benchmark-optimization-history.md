# Benchmark Optimization History

This document is the single source of historical benchmark evolution for the project.

Latest-state benchmark reports are intentionally kept separate and concise:

- [doc/json-log-writer-benchmark-results.md](doc/json-log-writer-benchmark-results.md)
- [doc/stacktrace-fingerprint-benchmark-results.md](doc/stacktrace-fingerprint-benchmark-results.md)
- [doc/stacktrace-outputstream-escaping-benchmark-results.md](doc/stacktrace-outputstream-escaping-benchmark-results.md)
- [doc/stacktrace-traversal-benchmark-results.md](doc/stacktrace-traversal-benchmark-results.md)
- [doc/stacktrace-write-fingerprint-singlepass-benchmark-results.md](doc/stacktrace-write-fingerprint-singlepass-benchmark-results.md)

## Scope

Optimization work covered:

1. Json event writing (`JsonLogWriter` vs `JsonLogWriterClassic`).
2. Stacktrace writing and deterministic fingerprinting.
3. Fairness corrections for benchmark methodology.
4. Hashing-path micro-optimizations (`Wyhash64.Streaming`).
5. Logback integration of single-pass write+fingerprint.

## Timeline

## 1) Baseline comparisons and hot path identification

Primary baseline findings:

- Direct writer path (`JsonLogWriter`) was consistently faster and lower-allocation than classic generator path for equivalent payloads.
- Throwable-heavy scenarios dominated end-to-end cost.
- `printStackTrace`-based paths could appear CPU-fast in some micro-runs, but allocation and GC pressure were much higher.

## 2) Controlled benchmarking and fairness corrections

Major correction:

- Separate-pass fingerprinting that called `fingerprint(Throwable, ...)` included extra stack-array recreation overhead.
- Benchmark moved to prepared-trace paths to compare equivalent work.

Result:

- Allocation gaps became realistic.
- Interpretation shifted from "implementation X is always faster" to "pipeline shape and payload equivalence matter".

## 3) Single-pass write+fingerprint APIs in core

Single-pass APIs were added in core to remove duplicate traversal:

- `addFromTraceToOutputStreamAndFingerprint(...)`
- `addFromTraceToOutputStreamJsonAndFingerprint(...)`
- `addFromTraceToOutputStreamWithNewlineAndFingerprint(...)`

Historical v4 to latest (average time):

- `singlePassRawNewline`: `1.312 us/op` -> `1.152 us/op` (about 12.2% faster)
- `singlePassJsonEscapedNewline`: `1.240 us/op` -> `1.144 us/op` (about 7.7% faster)
- `fingerprintOnlyPreparedTrace`: `0.638 us/op` -> `0.588 us/op` (about 7.8% faster)

## 4) Streaming hash micro-optimizations

The main hashing-path improvement was adding a dedicated single-byte update path.

### Key code change: single-byte fast path

From [core/src/main/java/hr/hrg/dialog/core/Wyhash64.java](core/src/main/java/hr/hrg/dialog/core/Wyhash64.java):

```java
public void updateByte(byte b) {
    this.totalLen += 1;
    if (bufLen == 48) {
        round(buf, 0);
        bufLen = 0;
    }
    buf[bufLen++] = b;
}
```

Then sanitizer call sites were migrated from tiny byte-array updates to `updateByte(...)`.

### Key code change: avoid per-frame className.getBytes()

From [core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java](core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java):

```java
int lambdaClassIdx = className.indexOf(LAMBDA_SUFFIX_FOR_CLASS);
int classEnd = (lambdaClassIdx != -1) ? lambdaClassIdx : className.length();
stream.update(className, 0, classEnd);
stream.updateByte(DOT_BYTE);
```

## 5) Logback single-pass integration

After core APIs stabilized, logback side was upgraded so `JsonLogWriter` computes hash while writing stack JSON.

### Key code change: JsonLogWriter now uses one pass

From [logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java](logback/src/main/java/hr/hrg/dialog/logback/JsonLogWriter.java):

```java
long fingerPrint = JavaStackSanitizerLogback.addFromTraceToOutputStreamJsonAndFingerprint(
    arrProxy,
    te -> true,
    out,
    throwableClassName
);
```

### Key code change: logback sanitizer API

From [logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java](logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java):

```java
public static long addFromTraceToOutputStreamJsonAndFingerprint(
        StackTraceElementProxy[] trace,
        Predicate<String> filter,
        OutputStream out,
        String throwableClassName,
        Wyhash64.Streaming stream) throws IOException {
    return addFromTraceToOutputStreamWithNewlineAndFingerprint(
            trace,
            filter,
            out,
            JavaStackSanitizer.NEWLINE_JSON_BYTES,
            throwableClassName,
            stream
    );
}
```

## Historical gain highlights

Numbers below compare earlier documented states to the latest rerun set.

1. JsonLogWriter benchmark (includeThrowable=true):
- `JsonLogWriter`: `2.122 us/op`, `1672 B/op` -> `1.898 us/op`, `480 B/op`
- Gain: about 10.6% lower latency and about 71.3% lower allocation

2. Stacktrace fingerprint benchmark (primary 3-method comparison):
- `writeWithJsonLogWriter` allocation: `2216 B/op` -> `968 B/op` (about 56.3% lower)
- `writeWithJsonLogWriterClassic` allocation: `2496 B/op` -> `1336 B/op` (about 46.5% lower)
- CPU varied by method and run shape; allocation reduction was the most stable gain signal

3. OutputStream escaping benchmark:
- Optimized direct writer remained dominant over `printStackTrace` string pipeline by large margins in both latency and allocation

## Methodology lessons captured

1. Always compare equivalent payload shape before drawing speed conclusions.
2. Separate per-frame and end-to-end costs with decomposition benchmarks.
3. Reuse hashing/stateful helpers in hot paths (`Streaming.reset(...)`) to avoid benchmark-induced allocation noise.
4. Keep one historical timeline document and keep per-benchmark docs focused on current state.

## Validation additions

Correctness tests added to protect optimization changes:

- [core/src/test/java/hr/hrg/dialog/core/Wyhash64StreamingTest.java](core/src/test/java/hr/hrg/dialog/core/Wyhash64StreamingTest.java)
- [logback/src/test/java/hr/hrg/dialog/logback/JavaStackSanitizerLogbackTest.java](logback/src/test/java/hr/hrg/dialog/logback/JavaStackSanitizerLogbackTest.java)

These tests verify:

- `updateByte(...)` hash equivalence vs bulk hashing
- mixed update-path equivalence
- reset-and-reuse correctness
- logback single-pass write+fingerprint equivalence to prior two-pass behavior
