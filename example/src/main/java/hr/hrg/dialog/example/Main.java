package hr.hrg.dialog.example;

import ch.qos.logback.classic.LoggerContext;
import hr.hrg.dialog.core.DiaLogger;
import hr.hrg.dialog.logback.JsonAppender;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class Main {

    private static final DiaLogger log = new DiaLogger(LoggerFactory.getLogger(Main.class));

    public static void main(String[] args) {
        // JsonAppender extends OutputStreamAppender, so its output stream must be
        // assigned programmatically. Here we attach a console JsonAppender to the
        // root logger in addition to the rolling file appender from logback.xml.
        attachConsoleJsonAppender();

        // Set up some MDC context
        MDC.put("requestId", "req-42");
        MDC.put("userId", "alice");
        MDC.put("tenant", "acme-corp");

        System.out.println("=== Dia-Log: JSON Logging ===");
        System.out.println("Waiting 2 seconds for startup...");
        sleep(2000);
        System.out.println("=== Logging at all levels ===");

        log.trace("This is a TRACE message (may not appear if root level is DEBUG)");
        log.debug("This is a DEBUG message");
        log.info("This is an INFO message");
        log.warn("This is a WARN message");
        log.error("This is an ERROR message");

        System.out.println();
        System.out.println("=== Structured key/value pairs (SLF4J 2.0 fluent API) ===");

        // kv() is a shorthand for addKeyValue()
        log.atInfo()
            .kv("method", "GET")
            .kv("path", "/api/users")
            .kv("statusCode", 200)
            .kv("durationMs", 42)
            .log("Request completed");

        log.atWarn()
            .kv("endpoint", "/api/orders")
            .kv("retryCount", 3)
            .kv("threshold", 0.95)
            .kv("fallback", "cache")
            .log("Rate limit approaching");

        System.out.println();
        System.out.println("=== stackWhenTraceEnabled() ===");

        // Conditional call stack: a synthetic throwable is attached only when TRACE is enabled.
        // With root DEBUG, no stack is emitted; switch root to TRACE to see errClass/errHash.
        log.atDebug().stackWhenTraceEnabled()
            .kv("state", "PAID")
            .log("Change state to {state}");

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
                .kv("component", "order-service")
                .kv("orderId", 12345)
                .setCause(e)
                .log("Failed to process order");
        }

        MDC.clear();

        System.out.println();
        System.out.println("=== Done! Check the JSON output above ===");
        System.out.println("Each line is a valid JSON object with fields: ts, level, logger, thread, msg, kv, errClass, errMessage, stack, errHash");
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

    private static void attachConsoleJsonAppender() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        // A fresh JsonAppender writes directly to System.out.
        JsonAppender appender = new JsonAppender();
        appender.setContext(context);
        appender.setName("JSON_CONSOLE");
        appender.setOutputStream(System.out);

        // JsonAppender overrides writeOut() to emit JSON directly, so this encoder
        // is only required to satisfy OutputStreamAppender.start().
        ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder =
                new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.start();
        appender.setEncoder(encoder);

        appender.start();
        root.addAppender(appender);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}