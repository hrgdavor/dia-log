# Step 6: Test/benchmark cleanup

> Parent overview: [`../no-grow-jsonlogwriter-placeholder.md`](../no-grow-jsonlogwriter-placeholder.md)
> Prereqs: Steps 1–5. Fixes up existing tests and benchmarks that relied on `grow()`.

- [x] **6a.** `CursorBufferWriterBenchmark`: remove `rbo.ensure(...)`.
- [x] **6b.** `ForyPerfComparisonTest` / `ForyPerfComparisonBenchmark`: 16-byte buffers
  + long strings now throw — use a buffer large enough (or assert the new behavior).
- [x] **6c.** `AllocationBenchmark` / `EscapedJsonStringWriterTest` / `StringByteExtractorTest`:
  confirm they use the `OutputStream` overloads; where they pass a
  `ReusableByteArrayOutputStream`, give it enough capacity (no grow expected).
  `StringByteExtractorTest` keeps its 2-arg `writeLatin1(out, bytes)` calls — the
  signature is unchanged (Step 3d).
- [x] **6d.** `ReusableByteArrayOutputStreamTest` / `ReusableByteArrayOutputStreamDirectApiTest`:
  rewrite `growsWhenEventExceedsCapacity` and `growsFromSingleByteWrites` to assert
  `BufferFullException` is thrown and buffer length unchanged.
  `resetReusesBufferWithoutShrinking` stays valid (buffer never shrinks, never grows)
  — update the comment to reflect the new rationale.
