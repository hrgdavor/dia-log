# Fix Wyhash64 Unicode / UTF-16 Bugs

## Overview

Three tests in [`WyhashZeroAllocTest`](../core/src/test/java/hr/hrg/dialog/core/WyhashZeroAllocTest.java) are failing:

| Test | Failure |
|------|---------|
| `hash_chars_matchesString_unicode` (L167) | `ArrayIndexOutOfBoundsException: Index 19 out of bounds for length 19` |
| `determinism_allPaths` (L752) | `ArrayIndexOutOfBoundsException: Index 41 out of bounds for length 41` |
| `streaming_chars_unicode` (L385) | hash mismatch: expected `273722214075852741` but got `-379178694834135994` |

All three are in [`Wyhash64.java`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java) and relate to Unicode (non-Latin-1) char processing.

---

## Root Cause Analysis

### Bug 1: ArrayIndexOutOfBoundsException in `hashUtf16(char[])`

**Location**: [`hashUtf16(long, char[], int, int)`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java:430)

The `hashUtf16` method reads 4 chars at once via `charToLongLE(chars, off)` which accesses `chars[off+3]`. The method uses a mixed accounting system:

- `i` starts as `byteLen = len * 2` (byte count)
- `p` starts as `off` (char offset)
- Loop condition checks `i` (in bytes) but array indexing uses `p` (in chars)

Each main-loop iteration consumes `24` chars (24 × 2 = 48 bytes), decrementing `i` by 48 and incrementing `p` by 24. Each inner-loop iteration consumes `8` chars (16 bytes), decrementing `i` by 16 and incrementing `p` by 8.

The final reads `a = charToLongLE(chars, off + len - 8)` and `b = charToLongLE(chars, off + len - 4)` access the last 4 and 8 chars from the end.

**The bug in the compiled version**: The compiled `.class` had a different version of this method where the bounds checking was incorrect — it used `i` (byte count) to guard char-offset reads, causing `charToLongLE` to read past the array end.

**Current source assessment**: The current source code at line 430 appears to have correct bounds for the test cases. However, the compiled class does not match the source (line numbers differ: stack trace says `charToLongLE` at line 621, source has it at line 624). This means the source was edited after the last `mvn compile`. A `mvn clean compile` may fix this particular issue, **but** a safe version of `charToLongLE` should be used to prevent any future recurrence.

### Bug 2: `Streaming.update(char[])` doesn't handle UTF-16

**Location**: [`Streaming.update(char[], int, int)`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java:846)

The streaming update treats **all** char arrays as Latin-1 (single byte per char):

```java
totalLen += len; // char[] is treated as Latin-1 bytes
buf[bufLen + i] = (byte) latin1Byte(chars[off + i]);
```

For Unicode strings like `"héllo wörld 🚀 Ñoño"`, the non-Latin-1 chars (`é`, `ö`, `🚀`, `Ñ`, `ñ`) need to be encoded as UTF-16 little-endian (2 bytes per char) to match `hash(String)` behavior. The test `streaming_chars_unicode` compares `Streaming.update(char[])` result with `Wyhash64.hash(0, s)` — the latter correctly detects UTF-16 via `hashUtf16(char[])`.

The fix requires:
1. **Scan** the input `char[]` for non-Latin-1 chars (> 0xFF) before processing.
2. **If UTF-16**: pack chars as 2 bytes each (low byte first, high byte second = LE) instead of using `latin1Byte()`. Double `totalLen` to account for 2 bytes/char.
3. **If Latin-1**: keep existing single-byte-per-char behavior.

---

## Fix Plan

### Fix 1: Add bounds-safe char reading in `hashUtf16(char[])`

**File**: [`Wyhash64.java`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java)

**Change**: Add a safe `charToLongLE` variant that checks the remaining length.

Specifically, in `hashUtf16(char[])`, replace direct `charToLongLE` calls with a conditional that handles the case where fewer than 4 chars remain. The approach should mirror how the byte[] version handles tail bytes — using partial reads.

**Current `charToLongLE`** (reads 4 chars unconditionally):
```java
private static long charToLongLE(char[] chars, int off) {
    return ((long) chars[off] & 0xFFFFL)
         | (((long) chars[off + 1] & 0xFFFFL) << 16)
         | (((long) chars[off + 2] & 0xFFFFL) << 32)
         | (((long) chars[off + 3] & 0xFFFFL) << 48);
}
```

**Fix needed**: The `hashUtf16` method must guarantee that when it calls `charToLongLE(chars, p)`, it has already verified that at least 4 more chars exist starting at `p`. The existing code does this via the `i > 48` / `i > 16` guards — but only if `p` (char offset) and `i` (byte count) stay in sync. We need to verify that all call sites in `hashUtf16(char[])` are safe.

The tail reads at:
- `a = charToLongLE(chars, off + len - 8)` — requires `len >= 8` (guaranteed since `byteLen > 16` means `len > 8`)
- `b = charToLongLE(chars, off + len - 4)` — requires `len >= 4` (also guaranteed)

These are safe. The loop body reads at `p`, `p+4`, `p+8`, `p+12`, `p+16`, `p+20` — all fine as long as the loop condition `i > 48` ensures at least 48 bytes (24 chars) remain. And the inner loop reads at `p`, `p+4` — fine as long as `i > 16` ensures at least 16 bytes remain.

**Action**: Verify the current code is correct, then add defensive bounds checks if needed. Consider adding a `safeCharToLongLE(char[], int, int)` helper.

### Fix 2: Add UTF-16 support to `Streaming.update(char[])`

**File**: [`Wyhash64.java`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java), lines 846-888

**Change**: Modify `Streaming.update(char[], int, int)` to:
1. Scan the input range for non-Latin-1 chars (> 0xFF)
2. If all Latin-1: keep current behavior (1 byte/char)
3. If any UTF-16: pack each char as 2 bytes (LE), double `totalLen`

The UTF-16 path needs a new internal method that writes chars as 2-byte LE sequences into the `buf` byte array. This cannot use the existing `round(char[], int)` method (which reads 8 chars as 8 single bytes via `charsToLong`). Instead, for UTF-16 processing, we need to either:
- Buffer the chars and call the byte[] round method, or
- Create a new `roundUtf16(char[], int)` that reads 4 chars as 8 bytes

**Suggested approach**:
- Add a `roundUtf16(char[], int charOff)` method that reads 4 chars via `charToLongLE` and treats the result as a 8-byte long for mixing.
- In `update(char[], int, int)`:
  - Scan for UTF-16 chars first
  - If UTF-16: use a separate code path that writes 2 bytes per char into `buf`, then calls the existing `round(byte[], int)` method
  - `totalLen += len * 2` for UTF-16

**Important**: The `totalLen` is used in `finalHash()` as the byte length passed to `finish()`. It must be `len * 2` for UTF-16 to produce the same result as `hashUtf16`.

### Fix 3: Verify with clean build

Run `mvn clean compile test-compile` followed by the specific test:

```bash
mvn test -pl core -Dtest=WyhashZeroAllocTest#streaming_chars_unicode+hash_chars_matchesString_unicode+determinism_allPaths
```

Then run the full test suite:
```bash
mvn test
```

---

## Files to Modify

| File | Changes |
|------|---------|
| [`Wyhash64.java`](../core/src/main/java/hr/hrg/dialog/core/Wyhash64.java) | 1. Defensive bounds in `hashUtf16(char[])` (L430-482) |
| | 2. UTF-16 detection + encoding in `Streaming.update(char[])` (L846-888) |
| | 3. New `roundUtf16(char[], int)` helper (after L938) |
| [`Wyhash64Test.java`](../core/src/test/java/hr/hrg/dialog/core/Wyhash64Test.java) | Possibly add edge-case tests for short UTF-16 arrays |

---

## Mermaid: Fix Flow

```mermaid
flowchart TD
    A[hash long seed, char[] chars] --> B{Scan for non-Latin-1?}
    B -- No --> C[hashLatin1 char[]]
    B -- Yes --> D[hashUtf16 char[]]
    
    D --> E{charToLongLE bounds check}
    E -- off+3 >= len --> F[Partial read / safe variant]
    E -- off+3 < len --> G[Full 4-char read]
    
    H[Streaming.update char[]] --> I{Scan for non-Latin-1?}
    I -- No --> J[Latin-1 path: 1 byte/char, totalLen += len]
    I -- Yes --> K[UTF-16 path: 2 bytes/char LE, totalLen += len*2]
    K --> L[Pack chars into buf as 2-byte LE]
    L --> M[round byte[] on full 48-byte blocks]
```

---

## Test Assertions That Must Pass

1. `hash_chars_matchesString_unicode` — `Wyhash64.hash(0, s.toCharArray())` must equal `Wyhash64.hash(0, s)` for `s = "héllo wörld 🚀 Ñoño"`
2. `determinism_allPaths` — All hash paths (String, char[], Streaming) must produce deterministic results consistent with each other
3. `streaming_chars_unicode` — `Streaming.update(char[])` with Unicode must produce same result as `Wyhash64.hash(0, s)` for `s = "héllo wörld 🚀 Ñoño"`
4. Existing Latin-1 tests must still pass
