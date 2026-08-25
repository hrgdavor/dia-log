# Step 1: Appender buffer-capacity config

> Parent overview: [`../no-grow-jsonlogwriter-placeholder.md`](../no-grow-jsonlogwriter-placeholder.md)
> Prereqs: none. Sets the buffer size first so the no-grow contract has a fixed
> capacity to build against.

- [x] **1a.** `JsonAppender` / `JsonAppenderRolling` / `JsonAppenderDev` /
  `JsonAppenderRollingDev`: add `void setEventBufferCapacity(int bytes)` (logback
  `<eventBufferCapacity>`), default `16 * 1024 * 1024`; reject `bytes < 64`
  with `IllegalArgumentException` (smaller than that, the fixed fields alone won't
  fit). Document the 2 GiB ceiling (`byte[]` max `Integer.MAX_VALUE`). Pass to the
  `ReusableByteArrayOutputStream` ctor.
- [x] **1b.** `writeOut`: replace `eventBuffer.write(JsonLogWriter.NL)` (which grew)
  with a direct no-grow newline — write `'\n'` into the buffer at `eventBuffer.pos`
  guarded by `if (pos < eventBuffer.buf.length)` — then `writeTo(activeStreamLoc)`.
  Once Step 5 lands, `RESERVE = 27` makes the guard always true; until then it
  keeps this step safe standalone (the buffer still grows before Step 5, so `pos`
  may equal `buf.length`). The snapshot handler receives the JSON **without** the
  newline (snapshot is taken before the newline is appended to the buffer).

## Validation

- `setEventBufferCapacity` reflected; buffer does **not** grow after a large event
  (compare `eventBuffer.buffer().length` before/after).
