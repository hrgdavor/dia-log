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
