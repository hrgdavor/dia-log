> **UPDATE (Logback 1.5.18+):** Logback now has **native XZ compression support**.
> `RollingPolicyBase.determineCompressionMode()` auto-detects a `.xz` suffix and sets
> `CompressionMode.XZ`; the built-in `XZCompressionStrategy` then compresses rotated
> files with `org.tukaani.xz.XZOutputStream`. No custom rolling policy or compressor
> is needed — just add the `org.tukaani:xz` dependency and use a `fileNamePattern`
> ending in `.xz`. The custom-extension approach below is only necessary for
> Logback versions older than 1.5.18.

To enable Logback to compress rotated logs to **XZ** format on Logback versions before 1.5.18, you need to implement a custom rolling policy that uses the XZ library. Since older Logback only natively supports `.gz` and `.zip`, you have to override its compression mechanism. The solution below provides a straightforward extension of `TimeBasedRollingPolicy` (or `SizeAndTimeBasedRollingPolicy`) that uses `XZOutputStream` from the `org.tukaani.xz` package.

---

## 1. Add the XZ library (if not already present)
You mentioned it’s already in the project. For reference, the typical Maven dependency is:
```xml
<dependency>
    <groupId>org.tukaani</groupId>
    <artifactId>xz</artifactId>
    <version>1.9</version>
</dependency>
```

---

## 2. Create a custom rolling policy

Create a class that extends `TimeBasedRollingPolicy` and overrides the `start()` method to inject a custom compressor when the file name pattern ends with `.xz`.

```java
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.rolling.helper.CompressionMode;
import ch.qos.logback.core.rolling.helper.Compressor;
import org.tukaani.xz.XZOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class XZTimeBasedRollingPolicy<E> extends TimeBasedRollingPolicy<E> {

    @Override
    public void start() {
        super.start();
        String fileNamePatternStr = getFileNamePatternStr();
        if (fileNamePatternStr != null && fileNamePatternStr.endsWith(".xz")) {
            // Override compression mode to trigger compression (any non-NONE value works)
            this.compressionMode = CompressionMode.GZ;
            // Replace the default compressor with our XZ compressor
            this.compressor = new XZCompressor(fileNamePatternStr);
        }
    }

    private static class XZCompressor extends Compressor {

        public XZCompressor(String fileNamePatternStr) {
            super(fileNamePatternStr);
        }

        @Override
        public void compress(String nameOfFile2Compress, String nameOfCompressedFile, String innerEntryName) throws IOException {
            // Compress the temporary file (nameOfFile2Compress) to the final .xz file (nameOfCompressedFile)
            try (FileInputStream fis = new FileInputStream(nameOfFile2Compress);
                 FileOutputStream fos = new FileOutputStream(nameOfCompressedFile);
                 XZOutputStream xos = new XZOutputStream(fos)) {

                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) != -1) {
                    xos.write(buffer, 0, len);
                }
            }

            // Delete the uncompressed temporary file
            File toDelete = new File(nameOfFile2Compress);
            if (!toDelete.delete()) {
                addWarn("Failed to delete temporary file: " + nameOfFile2Compress);
            }
        }
    }
}
```

---

## 3. Configure Logback (logback.xml)

Use your custom policy in a `RollingFileAppender`. The active log file is specified with `<file>`, and the archived files will have the `.xz` extension.

```xml
<configuration>
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/app.log</file>

        <rollingPolicy class="com.example.XZTimeBasedRollingPolicy">
            <!-- Pattern must end with .xz to trigger custom compression -->
            <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log.xz</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>

        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

---

## 4. For size‑and‑time‑based rotation

If you also need to rotate by file size, extend `SizeAndTimeBasedRollingPolicy` in the same way:

```java
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;

public class XZSizeAndTimeBasedRollingPolicy<E> extends SizeAndTimeBasedRollingPolicy<E> {

    @Override
    public void start() {
        super.start();
        String fileNamePatternStr = getFileNamePatternStr();
        if (fileNamePatternStr != null && fileNamePatternStr.endsWith(".xz")) {
            this.compressionMode = CompressionMode.GZ;
            this.compressor = new XZCompressor(fileNamePatternStr);
        }
    }

    // The same XZCompressor inner class as above
}
```

Then in `logback.xml`:
```xml
<rollingPolicy class="com.example.XZSizeAndTimeBasedRollingPolicy">
    <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.log.xz</fileNamePattern>
    <maxFileSize>100MB</maxFileSize>
    <maxHistory>30</maxHistory>
    <totalSizeCap>10GB</totalSizeCap>
</rollingPolicy>
```

---

## How it works

- The `fileNamePattern` ends with `.xz`, so the normal Logback compressor would be disabled.
- Our custom policy overrides `start()` to set `compressionMode = CompressionMode.GZ` (any non‑`NONE` value) and replaces the compressor with our own.
- During rollover, Logback renames the active file to a temporary name and then calls `compressor.compress()`.
- Our `XZCompressor` reads the temporary file and writes it compressed to the final `.xz` file using `XZOutputStream`. The temporary file is then deleted.

---

## Important notes

- **Active file**: The `<file>` element should **not** have the `.xz` extension – it is the uncompressed, currently written log file.
- **Compression settings**: The default compression level of `XZOutputStream` is used (6). You can customise it by passing a `LZMA2Options` instance to the constructor if needed.
- **Error handling**: The policy logs warnings via Logback’s internal logger if deletion of temporary files fails.
- **Performance**: XZ compression is slower than GZIP, but offers better compression ratios. Consider using asynchronous logging if performance is critical.

This approach integrates seamlessly with Logback’s existing rollover logic and requires no external scripts or separate cron jobs.