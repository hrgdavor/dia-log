# Step 2: `BufferFullException` + no-grow buffer foundation

> Parent overview: [`../no-grow-jsonlogwriter-placeholder.md`](../no-grow-jsonlogwriter-placeholder.md)
> Prereqs: Step 1 ([`01-appender-buffer-capacity.md`](01-appender-buffer-capacity.md)).
> Establishes the exception type and the throwing write contract on the now-fixed-size buffer.

- [x] **2a.** New unchecked exception `hr.hrg.dialog.core.BufferFullException extends
  RuntimeException`. Unchecked keeps hot-path internal helpers free of `throws`
  clauses while remaining catchable at the `JsonLogWriter` boundary. Document in
  the Javadoc: a forgotten catch propagates to the appender's `writeOut`, which
  logback will error-handle.
- [x] **2b.** `ReusableByteArrayOutputStream`: **remove** `grow(int)` and `ensure(int)`.
  All write methods become no-grow — on overflow throw `BufferFullException` instead
  of growing: `write(int)`, `write(byte[],int,int)`, `writeByte(int)`,
  `writeRaw(byte[],int,int)`, `writeLongPrefixLE(long,int)`, `writeIntPrefixLE(int,int)`,
  `writePackedLE(long,int)`, `writePackedLE(int,int)`.
  **All-or-nothing semantics**: `write(byte[],int,int)` checks `pos + len > buf.length`
  first, throws if so, then `arraycopy`. No partial writes.
- [x] **2c.** **Keep**: `buf`, `pos`, `reset()`, `buffer()`, `position()`, `setPosition(int)`,
  `size()`, `writeTo(OutputStream)`, `DEFAULT_CAPACITY`, constructors.
  `putPackedLE` / `WriteOps.LE_LONG` stay (absolute offsets, caller-ensured;
  misuse → `AIOOBE` is acceptable/defensive).
- [x] **2d.** **Remove** the no-op `publish()` and `resync()` methods. Step 5 removes
  every call site (`JsonLogWriter` is the only caller), so they become dead once the
  cursor is a caller-owned local.

## Validation

- `mvn -o -pl core,logback test` passes.
- `ReusableByteArrayOutputStreamTest` / `ReusableByteArrayOutputStreamDirectApiTest`:
  replace growth assertions with **no-grow/throw** assertions (write beyond capacity
  throws `BufferFullException`; buffer length unchanged).
