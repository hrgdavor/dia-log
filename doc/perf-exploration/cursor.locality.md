# Cursor locality

OutputStream is a nice abstraction, but for maximum performance it needs to be bypassed.

It is advisable to write to an OuputStream in batches instead of writing single characters and short values like 
numbers or short strings. The direction came about while developing JSON logging writer. A reusable buffer(limited or/and growable) for writing a log line
is easy to flush to OuputStream 

> Reusable buffer and can also be used to make a byte[] copy of a log line for sending elsewhere (like remote central logging) withput blocking the log writing. 


To achieve maximum performance when writing to a `byte[]`, we are essentially fighting two battles:
- **Memory Latency (Cache Misses)**
- **Instruction Overhead (Branching/Stack Frames)**.

When you move the "cursor" (the `pos` variable) from a class field into a local register, we are optimizing for **Cursor Locality**.

### 1. The Concept of Cursor Locality

In a standard `ByteBuffer` or `CustomBuffer` class, the `position` is a field:
```java
public class MyBuffer {
    private int pos; // Field (Stored in Heap)
    public void writeInt(int v) {
        this.buf[this.pos++] = ... // Field access (Load -> Modify -> Store)
    }
}
```

Every time you call `writeInt`, the CPU must:
1. Load the address of the `MyBuffer` object.
2. Load the value of `pos` from the heap into a register.
3. Increment it.
4. Store it back to the heap.

**Cursor Locality** is the practice of hoisting that `pos` variable into the **local stack frame** of the primary loop. 

Local variables are mapped directly to CPU registers. By passing `pos` as a primitive and returning it, you eliminate the "Load-Store" cycle to the heap.

### 2. The "Cooperative" Writing Architecture

To maximize performance, you should treat your writing routines as **pure functions** that operate on a shared state passed by value.

#### The Performance Pattern:

```java
public final class FastWriter {
    // Static, final, and small to guarantee JIT inlining
    public static int writeInt(byte[] buf, int pos, int val) {
        buf[pos] = (byte) (val >>> 24);
        buf[pos + 1] = (byte) (val >>> 16);
        buf[pos + 2] = (byte) (val >>> 8);
        buf[pos + 3] = (byte) val;
        return pos + 4;
    }

    public static int writeString(byte[] buf, int pos, String s) {
        int len = s.length();
        // Manual loop is often faster than s.getBytes() to avoid allocation
        for (int i = 0; i < len; i++) {
            buf[pos++] = (byte) s.charAt(i);
        }
        return pos;
    }
}
```

#### The Caller (The "Orchestrator"):

The caller is the only place where the "state" lives.

```java
public void serialize(Object data, byte[] buffer) {
    int pos = 0; // HOISTED TO REGISTER
    int limit = buffer.length;

    // The JIT will inline these calls. 
    // 'pos' stays in a CPU register (e.g., RAX) across all calls.
    pos = FastWriter.writeInt(buffer, pos, data.id);
    pos = FastWriter.writeString(buffer, pos, data.name);
    pos = FastWriter.writeInt(buffer, pos, data.age);
    
    // Final check at the end or at strategic milestones 
    // rather than inside every single helper call.
    if (pos > limit) throw new OverflowException();
}
```

### 3. Why this is the "Maximum Performance" approach

#### A. Elimination of Bounds Check Redundancy

Java performs bounds checking on every array access (`buf[pos]`). If you put a `if (pos + 4 > limit)` check inside every single `writeInt` helper, we are adding a branch instruction to every call.
By hoisting the cursor and performing "milestone" checks (e.g., checking if there's enough space for the next 100 bytes once), you reduce the branch predictor's load.

#### B. Store-Store Buffering (Write Combining)

Modern CPUs don't write to RAM immediately; they use "Write Combine Buffers." When the JIT inlines these helpers, it creates a tight stream of `STORE` instructions to contiguous memory addresses. 

The CPU recognizes this linear pattern and optimizes the writes into a single burst to the L1 cache, significantly increasing throughput.

#### C. Avoiding "Pointer Chasing"

In a traditional OOP approach:
 - `Buffer Object` $\rightarrow$ `Position Field` $\rightarrow$ `Array Object` $\rightarrow$ `Array Data`

In the Cursor Locality approach:
- `Register (pos)` $\rightarrow$ `Array Data`

You have removed one level of indirection from the inner loop.

### 4. Summary for Implementation

To ensure we are getting this performance:

1.  **Hoist the Cursor:** Keep `pos` as a local `int` in the main method.
2.  **Pass and Return:** Use `pos = helper(buf, pos, val)`.
3.  **Avoid Objects:** Do not pass a `State` object; pass the primitive `int`.
4.  **Keep Helpers Lean:** Ensure helpers are small enough for the JIT to inline (typically < 35 bytes of bytecode).
5.  **Strategic Bounds Checking:** Instead of checking `limit` inside every `writeInt`, check it once before a group of writes if the total size is predictable.

## Cursor locality "Return Value" pattern

When you use the "Return Value" pattern (Solution B), we are providing the JIT compiler with exactly the kind of information it needs to perform **Inlining** and **Scalar Replacement**.

Here is why this is the most performant choice and how the JIT handles it:

### 1. The Inlining Process
When the JIT compiler (C2) identifies a "hot" method (one called frequently), it doesn't just jump to that method; it physically copies the bytecode of the helper into the caller.

**Code:**
```java
int pos = 0;
pos = BufferUtils.writeInt(buf, pos, 100);
pos = BufferUtils.writeInt(buf, pos, 200);
```

**What the JIT actually executes (after inlining):**
```java
int pos = 0;
// Inlined writeInt 1
buf[pos] = (byte)(100 >> 24);
buf[pos+1] = (byte)(100 >> 16);
buf[pos+2] = (byte)(100 >> 8);
buf[pos+3] = (byte)100;
pos = pos + 4; 

// Inlined writeInt 2
buf[pos] = (byte)(200 >> 24);
// ... etc
pos = pos + 4;
```

### 2. Register Allocation

Because the `pos` variable is now just a local variable in a single large block of code (after inlining), the JIT can keep `pos` in a **CPU register** (like `RAX` or `RBX` on x86_64) for the entire duration of the sequence. It will never be written back to the stack/RAM until the sequence is finished.

### 3. Tips for "Guaranteed" Inlining

To ensure the JIT treats your helpers this way, follow these rules:

1.  **Keep helpers `static` and `final` (or in a final class):** This removes any possibility of polymorphic dispatch (virtual method lookups), making inlining trivial for the JVM.
2.  **Keep the methods small:** The JVM has an "inlining threshold" (usually based on bytecode size). If your helper is 50 lines long, the JIT might decide it's too large to inline. Keep your `writeInt`, `writeShort`, etc., lean.
3.  **Avoid `try-catch` inside the helper if possible:** While modern JVMs handle this better, keeping the "happy path" clean of exception handling blocks makes the JIT's optimization analysis much faster.

### Comparison Table

| Approach                 | JIT Potential      | Memory Overhead | Register Usage         |
| ------------------------ | ------------------ | --------------- | ---------------------- |
| **Manual Inlining**      | N/A                | None            | Perfect                |
| **Return Value**         | **High (Inlined)** | **None**        | **Perfect**            |
| **Wrapper Object**       | Medium             | High (Heap)     | Poor (Pointer chasing) |
| **ByteBuffer**           | High (Intrinsics)  | Low             | Good                   |

**Conclusion:** If you use `pos = helper(buf, pos, val)`, you get the architectural cleanliness of a helper method with the exact same machine-code performance as if you had copy-pasted the logic manually.

