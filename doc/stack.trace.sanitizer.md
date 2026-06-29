# Stack trace sanitizer

Stack trace is written to log fully up-to max size. But for fingerprint hash of sanitized trace is used.

- line numbers are not included to avoid hash change due to unrelated code changes in the same file
- do not include every stacktrace element in fingerprint by defining a filter
  - you should limit lines for fingerprint only from app packages
- fingerprint is using wyhash do to its speed, and author's admiration of this specific hash function

# bonus: hardcoded filter

Using a trie or some other structure for filtering is optimal when prefixes are not known is recommended.
When prefixes are known compile time, a hardcoded filter for stack frames, switch statements and zero garbage is likely JIT friendly and faster.
This type of optimization was not tested in this project, just considered, and even if it is faster you need to consider if you really need it.

```java
public static boolean shouldSkipHardcoded(String className) {
    if (className == null || className.isEmpty()) return false;

    // Jump based on the very first character
    switch (className.charAt(0)) {
        case 'j': // java., javax., jdk.
            return className.startsWith("java.") ||
                    className.startsWith("javax.") ||
                    className.startsWith("jdk.");

        case 's': // sun.
            return className.startsWith("sun.");

        case 'o': // org.springframework., org.apache., org.hibernate.
            if (className.startsWith("org.")) {
                // Nested switch or secondary checks to minimize string scanning
                return className.startsWith("org.springframework.") ||
                        className.startsWith("org.apache.") ||
                        className.startsWith("org.hibernate.");
            }
            return false;

        case 'c': // com.sun.
            return className.startsWith("com.sun.");

        default:
            return false;
    }
}
```
