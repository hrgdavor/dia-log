# Plan: Align JavaStackTraceWriter with JavaStackSanitizer

## Purpose

`JavaStackTraceWriter` is a derivative of `JavaStackSanitizer` with the **filter omitted** and **without fallback**. When `JavaStackSanitizer` is modified, `JavaStackTraceWriter` must be aligned to reflect those changes while keeping the filter and fallback code **commented out** (not deleted).

## File Locations

- **Source:** `core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java`
- **Reference:** `core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java`
- **Test:** `core/src/test/java/hr/hrg/dialog/core/JavaStackSanitizerTest.java`

## Alignment Procedure

### Step 1: Compare the two files

Diff `JavaStackTraceWriter.java` against `JavaStackSanitizer.java` to identify structural changes in the reference file.

### Step 2: Apply structural changes to JavaStackTraceWriter

For each method in `JavaStackTraceWriter`, apply the corresponding change from `JavaStackSanitizer` while keeping the filter and fallback commented out:

#### 2a. Method signatures
- Keep the `filter` parameter **commented out** in method signatures (e.g., `//Predicate<String> filter,`)
- Keep the `filter` argument **commented out** in method calls (e.g., `/*, filter*/`)

#### 2b. Filter usage in method bodies
- Keep filter checks **commented out** (e.g., `//if (!filter.test(className)) continue;`)
- Keep the entire filter-related `if` blocks **commented out** with `/* */`

#### 2c. Fallback code
- Keep fallback blocks **commented out** with `/* */` — never delete them
- The fallback is the block that handles the case when all frames are filtered out (top 3 raw frames)

#### 2d. Delimiter logic
- `JavaStackTraceWriter` must always write the newline delimiter **before each frame** (not conditionally between frames)
- In `addFromTrace`: add `stream.update(NEWLINE_BYTES, 0, 1);` before each frame
- In `addFromTraceToStringBuffer`: use `sb.append(NEWLINE);` unconditionally before each frame
- In `addFromTraceToOutputStreamWithNewline`: use `out.write(newlineBytes);` unconditionally before each frame

#### 2e. Constants vs string literals
- Replace any string literals `"$$Lambda$"` with `LAMBDA_SUFFIX_FOR_CLASS`
- Replace any string literals `"lambda$"` with `LAMBDA_PREFIX_FOR_METHOD`
- Use `LAMBDA_METHOD_BYTES` constant instead of `"lambda".getBytes(StandardCharsets.UTF_8)` where applicable

#### 2f. `isFirstFrame` variable
- Since there is no fallback and the newline is always written before each frame, `isFirstFrame` is **commented out** in all methods
- Comment it out with `// boolean isFirstFrame = true;` and `// isFirstFrame = false;`

#### 2g. Imports
- Keep the `Predicate` import since filter parameters remain in signatures (commented out)
- Keep `java.util.function.Predicate` import

### Step 3: Update class-level documentation

Update the class javadoc to reflect that it is `JavaStackSanitizer` without the filter and without the fallback.

### Step 4: Verify

1. Compile: `mvn compile -pl core -q`
2. Test: `mvn test -pl core -q`
3. Verify no compilation errors or test failures

## Key Principles

1. **Comment out, never delete** — All filter and fallback code must remain as comments so it can be restored if needed
2. **Always write newline before each frame** — The delimiter logic must match `JavaStackSanitizer`'s streaming hash behavior (newline before every frame, not just between frames)
3. **Use constants** — Always use the defined constants instead of string literals for lambda-related checks
4. **Comment out `isFirstFrame`** — Since there's no fallback and newlines are always written, `isFirstFrame` is unnecessary and should be commented out

## Checklist for Each Alignment

- [ ] Compare `JavaStackSanitizer.java` with `JavaStackTraceWriter.java` for structural differences
- [ ] Apply delimiter logic fix (always write newline before each frame)
- [ ] Replace string literals with constants
- [ ] Comment out `isFirstFrame` where no longer needed
- [ ] Keep all filter code commented out (not deleted)
- [ ] Keep all fallback code commented out (not deleted)
- [ ] Update class javadoc
- [ ] Compile and run tests
- [ ] Verify no new warnings or errors
