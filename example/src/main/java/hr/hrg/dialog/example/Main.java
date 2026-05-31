package hr.hrg.dialog.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        // Set up some MDC context
        MDC.put("requestId", "req-42");
        MDC.put("userId", "alice");
        MDC.put("tenant", "acme-corp");

        System.out.println("=== Dia-Log: JSON Console Logging ===");
        System.out.println("Waiting 2 seconds for startup...");
        sleep(2000);
        System.out.println("=== Logging at all levels ===");

        log.trace("This is a TRACE message (may not appear if root level is DEBUG)");
        log.debug("This is a DEBUG message");
        log.info("This is an INFO message");
        log.warn("This is a WARN message");
        log.error("This is an ERROR message");

        System.out.println();
        System.out.println("=== Structured key-value pairs (SLF4J 2.0 style) ===");

        // SLF4J 2.0 fluent API with key-value pairs
        log.atInfo()
            .addKeyValue("statusCode", 200)
            .addKeyValue("durationMs", 42)
            .log("GET /api/users completed");

        log.atWarn()
            .addKeyValue("retryCount", 3)
            .addKeyValue("threshold", 0.95)
            .addKeyValue("fallback", "cache")
            .log("Rate limit approaching for endpoint /api/orders");

        System.out.println();
        System.out.println("=== Parameterized logging ===");

        log.info("User {} logged in from IP {}", "alice", "192.168.1.42");
        log.warn("Disk usage at {}/{} MB", 850, 1024);

        System.out.println();
        System.out.println("=== Exception logging ===");

        try {
            riskyOperation();
        } catch (Exception e) {
            log.error("Operation failed: {}", e.getMessage(), e);
        }

        System.out.println();
        System.out.println("=== Nested cause exception ===");

        try {
            outerOperation();
        } catch (Exception e) {
            log.atError()
                .addKeyValue("component", "order-service")
                .addKeyValue("orderId", 12345)
                .setCause(e)
                .log("Failed to process order");
        }

        MDC.clear();

        System.out.println();
        System.out.println("=== Done! Check the JSON output above ===");
        System.out.println("Each line is a valid JSON object with fields: ts, level, logger, thread, msg, kv, ctx, err");
        System.out.println("Try: java -jar example/target/dia-log-example-1.0.0-SNAPSHOT.jar | jq .");
    }

    static void riskyOperation() {
        throw new RuntimeException("Something went wrong in the example module");
    }

    static void outerOperation() {
        try {
            innerOperation();
        } catch (Exception e) {
            throw new RuntimeException("Outer operation failed", e);
        }
    }

    static void innerOperation() {
        throw new IllegalArgumentException("Invalid input value: negative not allowed");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}