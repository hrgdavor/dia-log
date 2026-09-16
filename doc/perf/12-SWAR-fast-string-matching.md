# SWAR matching

Using pre-packed `long` constants for **reading and searching** strings (like HTTP headers) in native memory (`MemorySegment`) is a widely used, ultra-low-latency pattern.

By reading 8 bytes at once using `ValueLayout.JAVA_LONG_UNALIGNED` and applying **SWAR (SIMD Within A Register)** bitwise operations, you can perform case-folding, length-masking, and delimiter matching across 8 bytes in parallel within single CPU registers.

---

### Key SWAR & Masking Techniques for HTTP Headers

#### 1. ASCII Case-Folding Mask (`0x2020202020202020L`)
In ASCII encoding, uppercase letters (`'A'`–`'Z'`) and lowercase letters (`'a'`–`'z'`) differ by exactly **bit 5** (`0x20`):
* `'A'` = `0x41` (`0100 0001`) \\(\rightarrow\\) `'a'` = `0x61` (`0110 0001`)
* `':'` = `0x3A` (`0011 1010`) \\(\rightarrow\\) `0x3A | 0x20 = 0x3A` (unaffected!)
* `'-'` = `0x2D` (`0010 1101`) \\(\rightarrow\\) `0x2D | 0x20 = 0x2D` (unaffected!)

By bitwise OR-ing an 8-byte `long` word with `0x2020202020202020L`, you convert all 8 ASCII characters to lowercase simultaneously in **1 CPU clock cycle**:

\\[\text{lowerWord} = \text{rawLong} \mid \text{0x2020202020202020L}\\]

#### 2. Sub-Word Length Bitmasks
When a header key is shorter than 8 bytes (e.g., `"Host:"` is 5 bytes) or doesn't align cleanly to an 8-byte boundary, reading an 8-byte `long` from `MemorySegment` will pull in 3 trailing "garbage" bytes.

To safely compare only the valid \\(N\\) bytes in Little-Endian byte order, apply a dynamic or pre-computed byte mask:

```java
// Mask for 5 bytes: 0x000000FFFFFFFFFFL
long byteMask = (1L << (length * 8)) - 1L; 

long cleanWord = rawLong & byteMask;
```

#### 3. SWAR Delimiter Finder (Finding `:` or `\r\n` in 8 Bytes)
Rather than checking byte-by-byte for the header key-value separator (`':'` / `0x3A`), you can use Alan Mycroft’s SWAR zero-byte detection algorithm to find the exact offset of a character within 8 bytes simultaneously:

```java
// 1. XOR word with repeated target byte (e.g. 0x3A for ':')
long xor = inputLong ^ 0x3A3A3A3A3A3A3A3AL;
// 2. SWAR zero-byte check
long matchBits = (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;
// 3. Extract byte index of the first match (0 to 7)
int matchIndex = Long.numberOfTrailingZeros(matchBits) >>> 3;
```

---

### Implementation: `OptimizedHeaderReader`

Here is a complete SWAR-enabled header reader and matcher operating directly over `MemorySegment`:

```java
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Ultra-low-latency MemorySegment reader using 64-bit SWAR (SIMD Within A Register)
 * for fast HTTP header matching and delimiter scanning.
 */
public final class OptimizedHeaderReader {

    private static final VarHandle VH_LONG_UNALIGNED = ValueLayout.JAVA_LONG_UNALIGNED.varHandle();
    
    // Mask to force bit 5 (case bit) on 8 ASCII bytes simultaneously
    private static final long ASCII_LOWERCASE_MASK = 0x2020202020202020L;
    
    // SWAR constants for byte matching
    private static final long SWAR_LOW_BYTES  = 0x0101010101010101L;
    private static final long SWAR_HIGH_BYTES = 0x8080808080808080L;

    /**
     * Matches up to 8 ASCII bytes at currentOffset against a pre-packed expected long key,
     * ignoring ASCII case and masking out trailing bytes beyond length.
     *
     * @param segment memory segment buffer
     * @param offset byte offset to read from
     * @param expectedLowerKey pre-packed lowercase expected key
     * @param byteMask pre-computed mask for key length (e.g. 0x000000FFFFFFFFFFL for 5 bytes)
     * @return true if matches case-insensitively
     */
    public static boolean matchKey8(MemorySegment segment, long offset, long expectedLowerKey, long byteMask) {
        // 1. Read 8 bytes unaligned from memory segment in single MOV instruction
        long rawWord = (long) VH_LONG_UNALIGNED.get(segment, offset);
        
        // 2. SWAR Lowercase conversion + Length Masking
        long lowerWord = (rawWord | ASCII_LOWERCASE_MASK) & byteMask;
        
        // 3. Single 64-bit register comparison
        return lowerWord == expectedLowerKey;
    }

    /**
     * SWAR Delimiter Search: Finds the offset of the first occurrence of a target byte
     * (e.g., ':' or '\r') within an 8-byte block starting at offset.
     *
     * @return relative byte index (0-7) if found, or -1 if target byte is not in these 8 bytes
     */
    public static int findByteInWord(MemorySegment segment, long offset, byte targetByte) {
        long rawWord = (long) VH_LONG_UNALIGNED.get(segment, offset);
        
        // Broadcast target byte across all 8 bytes of a long
        long broadcast = (targetByte & 0xFFL) * SWAR_LOW_BYTES;
        long xor = rawWord ^ broadcast;
        
        // SWAR Zero-Byte Detection
        long matchBits = (xor - SWAR_LOW_BYTES) & ~xor & SWAR_HIGH_BYTES;
        
        if (matchBits != 0) {
            // Find index of first matching byte (0..7)
            return Long.numberOfTrailingZeros(matchBits) >>> 3;
        }
        return -1; // Not found in this 8-byte block
    }

    /**
     * Pre-calculates an 8-byte Little-Endian mask for a given payload length (1 to 8 bytes).
     */
    public static long makeLengthMask(int length) {
        if (length >= 8) return -1L; // 0xFFFFFFFFFFFFFFFFL
        return (1L << (length * 8)) - 1L;
    }
}
```

---

### Example: Generated HTTP Header Router

Below is an example of what code generated for a high-performance HTTP header parser looks like using SWAR matching constants:

```java
public class GeneratedHttpHeaderParser {

    // Pre-packed Lowercase ASCII Keys + Structural Delimiters (Little-Endian)
    
    // "host:" (5 bytes) -> 'h' 'o' 's' 't' ':' -> 0x3A74736F68L
    private static final long KEY_HOST_LOWER = 0x3A74736F68L;
    private static final long MASK_5_BYTES   = 0x000000FFFFFFFFFFL;

    // "accept:" (7 bytes) -> 'a' 'c' 'c' 'e' 'p' 't' ':' -> 0x3A747065636361L
    private static final long KEY_ACCEPT_LOWER = 0x3A747065636361L;
    private static final long MASK_7_BYTES     = 0x00FFFFFFFFFFFFFFL;

    public void parseHeaderLine(MemorySegment segment, long offset) {
        // Try matching "host:" (5 bytes) in 1 register operation
        if (OptimizedHeaderReader.matchKey8(segment, offset, KEY_HOST_LOWER, MASK_5_BYTES)) {
            processHostHeader(segment, offset + 5);
            return;
        }

        // Try matching "accept:" (7 bytes) in 1 register operation
        if (OptimizedHeaderReader.matchKey8(segment, offset, KEY_ACCEPT_LOWER, MASK_7_BYTES)) {
            processAcceptHeader(segment, offset + 7);
            return;
        }

        // SWAR Fallback: Scan 8 bytes for colon delimiter position if unknown header
        int colonIdx = OptimizedHeaderReader.findByteInWord(segment, offset, (byte) ':');
        if (colonIdx != -1) {
            processCustomHeader(segment, offset, colonIdx);
        }
    }

    private void processHostHeader(MemorySegment segment, long valueOffset) { /* ... */ }
    private void processAcceptHeader(MemorySegment segment, long valueOffset) { /* ... */ }
    private void processCustomHeader(MemorySegment segment, long headerOffset, int colonPos) { /* ... */ }
}
```

---

### Performance Summary

1. **Case-Insensitivity for Free**: SWAR case-folding via `OR 0x2020202020202020L` adds zero branch mispredictions and takes 1 clock cycle on x86/ARM64.
2. **Bounds & Word Tearing Safety**: Single-threaded header parsing over a shared `MemorySegment` read buffer avoids word tearing issues. `ValueLayout.JAVA_LONG_UNALIGNED` handles arbitrary header start positions without throw/alignment penalties.
3. **Branch-Free Matching**: Matching an 8-byte header key requires a single `long` comparison instead of an 8-iteration byte loop or string allocation.


#  more on SWAR in Java

SWAR (**SIMD Within A Register**) is a technique that treats standard 64-bit general-purpose registers (`long` in Java) as vectors of smaller sub-words (such as 8 x 8-bit bytes, 4 x 16-bit shorts, or 2 x 32-bit ints). By executing bitwise operations, bit-shifting, and integer multiplication across the whole register, you can process up to 8 bytes concurrently in a single CPU instruction without using explicit SIMD instructions or vector registers.

**Key themes I noticed:**
1. **Zero-Byte & Character Scanning**: Using bitwise subtraction algorithms like `(x - 0x0101010101010101L) & ~x & 0x8080808080808080L` (Mycroft's algorithm) to locate byte values (such as colons, quotes, control characters, or string delimiters) across 8 bytes simultaneously in constant time.
2. **Branchless Number Parsing**: By subtracting ASCII `'0'` (`0x3030303030303030L`) across all bytes and applying compound packing multipliers (like `1 + 0x100 * 10`), SWAR parses multi-digit numbers without loops or conditional branches.
3. **Integration with Foreign Memory**: In modern Java (Java 11+ and FFM Java 22+), `ValueLayout.JAVA_LONG_UNALIGNED.varHandle()` reads 8 unaligned bytes directly from a `MemorySegment` or byte array straight into a `long` register for SWAR processing.
4. **SWAR vs. Vector API (SIMD)**: SWAR stays entirely within General Purpose Registers (GPRs), eliminating vector register setup latency, mask materialization costs, and instruction-port switching overhead for small payloads (under 16–32 bytes).

[wiki/SWAR](https://en.wikipedia.org/wiki/SWAR)


# String mathcher generator

An adaptive code generator can analyze the target key set at compile time and combine **SWAR delimiter scanning** for early rejection with **adaptive matching strategies** based on key length spread and prefix distribution.

---

### Phase 1: Early-Exit Length Check via SWAR

Before comparing key bytes, the generator reads 8 bytes from native memory using `ValueLayout.JAVA_LONG_UNALIGNED` and uses SWAR to locate the key delimiter (such as a colon `:` or quote `"`) in a single clock cycle.

#### SWAR Delimiter Detection
To scan for a delimiter byte (e.g., `:` / `0x3A`) across 8 bytes simultaneously:

```java
long raw = (long) VH_LONG_UNALIGNED.get(segment, offset);

// 1. SWAR XOR to convert target byte positions to zero
long xor = raw ^ 0x3A3A3A3A3A3A3A3AL; 

// 2. SWAR zero-byte detection mask
long delimiterBits = (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;

// 3. Extract exact byte offset of delimiter (0 to 7) using JDK compiler intrinsic
int tokenLen = Long.numberOfTrailingZeros(delimiterBits) >>> 3;
```

#### The Early-Exit Rejection Guard
At code-generation time, the generator knows the **minimum key length** in the schema (`MIN_KEY_LEN`). If `delimiterBits == 0` (meaning no delimiter in 8 bytes) or `tokenLen < MIN_KEY_LEN`, the generator emits an immediate abort:

```java
// If no delimiter was found in 8 bytes, or the token length is smaller 
// than any valid schema key, fail immediately!
if (delimiterBits == 0 || tokenLen < MIN_KEY_LEN) {
    return -1; // Fast rejection without testing any individual key
}
```

---

### Phase 2: Adaptive Generator Strategies Based on Key Spread

Once `tokenLen` is calculated, the generator routes execution using a strategy tailored to the spread and distribution of the key set:

```
                          Key Set Analysis at Code-Gen Time
                                          │
        ┌─────────────────────────────────┼────────────────────────────────┐
        ▼                                 ▼                                ▼
Strategy 1: Single Length       Strategy 2: Clustered Lengths    Strategy 3: Multi-Word SWAR
All keys same length N          Keys vary (e.g., 4, 7, 12)       Keys > 8 bytes long
(Direct Word Comparison)        (Switch on SWAR tokenLen)        (Cascaded 8-byte Blocks)
```

#### Strategy 1: Single Length (\\(N\\)) — Direct Mask Match
* **When to use**: All keys in the set have the exact same length \\(N\\) (e.g., all keys are 4 bytes long).
* **Generated Code**:
  ```java
  // Check exact length match
  if (tokenLen != 4) return -1;
  
  // Single masked word comparison
  long keyWord = raw & 0x00000000FFFFFFFFL;
  if (keyWord == KEY_HOST) return INDEX_HOST;
  if (keyWord == KEY_PORT) return INDEX_PORT;
  return -1;
  ```

#### Strategy 2: Clustered Lengths — `switch` on `tokenLen`
* **When to use**: Keys vary in length (e.g., `"id"` = 2, `"host"` = 4, `"accept"` = 6).
* **Generated Code**: The generator emits a `switch` on the SWAR-computed `tokenLen`. This narrows the candidate keys to only those of the matching length, avoiding comparisons against keys of different sizes:
  ```java
  switch (tokenLen) {
      case 2: // Only test 2-byte keys
          if ((raw & 0xFFFFL) == KEY_ID) return INDEX_ID;
          break;
      case 4: // Only test 4-byte keys
          if ((raw & 0xFFFFFFFFL) == KEY_HOST) return INDEX_HOST;
          break;
      case 6: // Only test 6-byte keys
          if ((raw & 0xFFFFFFFFFFFFL) == KEY_ACCEPT) return INDEX_ACCEPT;
          break;
  }
  return -1;
  ```

#### Strategy 3: Wide Key Spread (\\(N > 8\\) Bytes) — Cascaded SWAR Pipeline
* **When to use**: Config keys exceed 8 bytes (e.g., `"Authorization"`, `"Content-Type"`).
* **Generated Code**: The generator inspects `w1` (first 8 bytes) first. If `tokenLen >= 8`, it matches `w1` using a `tableswitch` or `if` check, then fetches `w2` (bytes 9–16) to complete the match:
  ```java
  if (tokenLen >= 8) {
      long w1 = raw | 0x2020202020202020L; // SWAR lowercase fold
      
      // Match first 8 bytes
      if (w1 == KEY_CONTENT_TYPE_W1) { 
          // Fetch second 8-byte word for wide key
          long w2 = (long) VH_LONG_UNALIGNED.get(segment, offset + 8) | 0x2020202020202020L;
          if ((w2 & MASK_CONTENT_TYPE_W2) == KEY_CONTENT_TYPE_W2) {
              return INDEX_CONTENT_TYPE;
          }
      }
  }
  ```

---

### Complete Generated Example

Here is what the final code generated for an HTTP header router with keys of varying lengths looks like:

```java
public class GeneratedHeaderMatcher {

    private static final VarHandle VH_LONG = ValueLayout.JAVA_LONG_UNALIGNED.varHandle();
    
    // Schema limits pre-calculated at code-gen time:
    // Minimum key length = 4 ("host"), Maximum key length = 12 ("content-type")
    private static final int MIN_KEY_LEN = 4;

    public static int matchHeader(MemorySegment segment, long offset) {
        long raw = (long) VH_LONG.get(segment, offset);

        // 1. SWAR Colon Detection (':' = 0x3A)
        long xor = raw ^ 0x3A3A3A3A3A3A3A3AL;
        long delimiterBits = (xor - 0x0101010101010101L) & ~xor & 0x8080808080808080L;
        
        int tokenLen = Long.numberOfTrailingZeros(delimiterBits) >>> 3;

        // 2. Early-Exit Rejection: If no colon in first 8 bytes AND token < MIN_KEY_LEN
        if (delimiterBits == 0 && tokenLen < MIN_KEY_LEN) {
            return -1; // Fast drop for invalid/short tokens
        }

        long lower = raw | 0x2020202020202020L; // SWAR Case Fold

        // 3. Adaptive Length Dispatch
        switch (tokenLen) {
            case 4: // "host:"
                if ((lower & 0xFFFFFFFFL) == 0x74736F68L) return 0;
                break;

            case 6: // "accept:"
                if ((lower & 0xFFFFFFFFFFFFL) == 0x3A7470656363L) return 1;
                break;

            default:
                // Handle keys longer than 8 bytes (e.g., "content-type:")
                if (tokenLen >= 8 && lower == 0x2D746E65746E6F63L) { // "content-"
                    long w2 = (long) VH_LONG.get(segment, offset + 8) | 0x2020202020202020L;
                    if ((w2 & 0x000000FFFFFFFFFFL) == 0x0000003A65707974L) { // "type:"
                        return 2;
                    }
                }
                break;
        }

        return -1;
    }
}
```

---

### Benefits of this Generator Architecture

1. **Immediate Rejection**: Tokens shorter than `MIN_KEY_LEN` fail in ~3 CPU instructions without touching any key comparisons.
2. **Zero False Positives Across Bounds**: Length checking prevents matching short prefixes of long keys.
3. **Branch Minimization**: Matching by length via `tokenLen` turns an \\(O(N)\\) sequential `if` ladder into a single `switch` jump table.


# fast enum matcher, case insensitive

**Yes, absolutely.** Using SWAR (SIMD Within A Register) for case-insensitive `Enum` matching directly from native memory (`MemorySegment`) is an exceptional high-performance pattern.

Standard Java approaches like `Enum.valueOf(Class, String)` or custom `String.equalsIgnoreCase()` loops come with severe performance taxes:
1. **Heap Allocation**: Parsing requires allocating a new `String` object from native memory to pass to `Enum.valueOf()`.
2. **Hash & Char Loops**: `HashMap` lookups incur pointer chasing, hashing overhead, and char-by-char comparison loops.

By using SWAR, you can turn string-to-enum resolution into a **zero-allocation, 1-to-2 instruction register lookup**.

---

### How SWAR Case-Insensitive Enum Matching Works

1. **Read 8 Bytes at Once**: Load 8 bytes from `MemorySegment` using `ValueLayout.JAVA_LONG_UNALIGNED` in a single CPU load instruction.
2. **Parallel Case-Folding (1 Clock Cycle)**: In ASCII, uppercase letters (`'A'`–`'Z'`) and lowercase letters (`'a'`–`'z'`) differ strictly by bit 5 (`0x20`). By bitwise OR-ing the 64-bit register with `0x2020202020202020L`, all 8 ASCII bytes are forced to lowercase simultaneously without conditional branching.
3. **Bitmasking Length**: Mask off trailing bytes beyond the known string length using `(raw | 0x2020202020202020L) & lengthMask`.
4. **Direct Ordinal / Enum Array Return**: Compare against pre-packed lowercase `long` constants and return the pre-allocated `Enum.values()[ordinal]` directly.

---

### Example: Fast Case-Insensitive `OrderStatus` Enum Matcher

Suppose you have an enum representing order states, and incoming JSON or HTTP payloads contain values like `"active"`, `"ACTIVE"`, `"Pending"`, or `"CANCELLED"`.

#### Generated / Pre-Packed Enum Matcher Implementation

```java
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

public enum OrderStatus {
    ACTIVE,
    PENDING,
    SHIPPED,
    CANCELLED;

    private static final OrderStatus[] VALUES = OrderStatus.values();
    private static final VarHandle VH_LONG = ValueLayout.JAVA_LONG_UNALIGNED.varHandle();

    // SWAR ASCII Lowercase Fold Mask
    private static final long ASCII_LOWER_MASK = 0x2020202020202020L;

    // Pre-packed Little-Endian ASCII Lowercase Constants
    // "active"   (6 bytes) -> 'a','c','t','i','v','e' -> 0x0000657669746361L
    private static final long KEY_ACTIVE_LOWER = 0x0000657669746361L;
    private static final long MASK_6_BYTES      = 0x0000FFFFFFFFFFFFL;

    // "pending"  (7 bytes) -> 'p','e','n','d','i','n','g' -> 0x00676E69646E6570L
    private static final long KEY_PENDING_LOWER = 0x00676E69646E6570L;
    private static final long MASK_7_BYTES      = 0x00FFFFFFFFFFFFFFL;

    // "shipped"  (7 bytes) -> 's','h','i','p','p','e','d' -> 0x0064657070696873L
    private static final long KEY_SHIPPED_LOWER = 0x0064657070696873L;

    /**
     * Matches raw ASCII memory segment bytes to OrderStatus case-insensitively.
     * Zero allocations, zero loops.
     *
     * @param segment memory segment holding incoming payload
     * @param offset  starting byte offset
     * @param length  length of the enum token
     * @return OrderStatus instance or null if no match
     */
    public static OrderStatus fromNative(MemorySegment segment, long offset, int length) {
        // 1. Single 64-bit load from memory
        long raw = (long) VH_LONG.get(segment, offset);

        // 2. SWAR 8-byte case fold to lowercase
        long lower = raw | ASCII_LOWER_MASK;

        // 3. Match by token length (Jump table / switch)
        switch (length) {
            case 6: // "active" / "ACTIVE" / "Active"
                if ((lower & MASK_6_BYTES) == KEY_ACTIVE_LOWER) {
                    return VALUES; // OrderStatus.ACTIVE
                }
                break;

            case 7: // "pending" vs "shipped"
                long masked7 = lower & MASK_7_BYTES;
                if (masked7 == KEY_PENDING_LOWER) return VALUES; // OrderStatus.PENDING
                if (masked7 == KEY_SHIPPED_LOWER) return VALUES; // OrderStatus.SHIPPED
                break;

            case 9: // "cancelled" (longer than 8 bytes)
                if (lower == 0x6C6C65636E616370L) { // "cancelle"
                    byte lastChar = segment.get(ValueLayout.JAVA_BYTE, offset + 8);
                    if ((lastChar | 0x20) == 'd') {
                        return VALUES; // OrderStatus.CANCELLED
                    }
                }
                break;
        }

        return null; // Unknown enum value
    }
}
```

---

### Why This Is Superlative for Enum Dispatch

| Metric | Standard `Enum.valueOf(String)` | SWAR `fromNative` |
| :--- | :--- | :--- |
| **Heap Allocation** | Allocates `String` + `char[]` | **0 bytes** (Zero GC) |
| **Case Folding** | `toLowerCase()` creates 2nd String | **1 bitwise OR instruction** |
| **Lookup Time** | `HashMap` / array walk (\\(O(N)\\)) | **\\(O(1)\\) constant register switch** |
| **Memory Read** | Multiple pointer dereferences | **Single 64-bit load** |

If you build an annotation processor or code generator, it can inspect any `enum` class at compile time, emit this exact SWAR `fromNative(MemorySegment, offset, length)` method into a helper class, and give you near-instant case-insensitive enum parsing.
