# Stack When Trace — Conditional Call-Stack Visibility

`stackWhenTrace()` is a feature of dia-log's `LoggingEventBuilderWrapper` that lets you attach a throwable (call stack) to a log line only when TRACE level is enabled. This gives you two modes of output from a single log statement:

- **Normal mode (TRACE off)** — clean message, no stack trace cluttering the output
- **Trace mode (TRACE on)** — the same message with a throwable showing exactly what code path triggered it

## The Problem

You want to log state changes, transitions, or important events at DEBUG level so they're always visible in production. But when debugging an issue, you also need to see *where* the log was called from — the call stack. Without `stackWhenTrace()`, you'd have to either:

- Always log the stack (noisy in production)
- Or never log the stack (no help when debugging)

## The Solution

```java
log.atDebug().stackWhenTrace()
    .kv("state", state)
    .log("Change state to {state}");
```

A single log statement. One line in the output. The behavior adapts automatically based on the current TRACE level.

## How It Works

When `stackWhenTrace()` is called on the builder, a flag is set. At log time:

1. If TRACE is **enabled**: a `Throwable("stackWhenTrace")` is attached as the cause via SLF4J's `setCause()`. The throwable flows through `ILoggingEvent.getThrowableProxy()` to **every** appender — JSON, text, file, console — with no special handling needed.
2. If TRACE is **disabled**: no throwable is attached. The log fires normally with just the message.

The key insight: the throwable is set on the underlying SLF4J builder *before* the log event is created, so it becomes part of the event's `ThrowableProxy`. Every appender that handles exceptions (which is all of them) will see it.

## Output Examples

### Plain Text Appender

**TRACE off (DEBUG enabled):**
```
12:34:56.789 [main] DEBUG com.example.OrderService - Change state to PAID
```

**TRACE on:**
```
12:34:56.789 [main] DEBUG com.example.OrderService - Change state to PAID
java.lang.Throwable: stackWhenTrace
	at com.example.OrderService.processPayment(OrderService.java:87)
	at com.example.CheckoutController.handleCheckout(CheckoutController.java:42)
	at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
	at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)
	...
```

Standard Logback text appenders print exceptions by default after the log line. No pattern changes needed — the throwable just appears when TRACE is on.

### JSON Appender (`ConsoleAppenderJson`)

**TRACE off:**
```json
{"ts":1748765696789,"level":"DEBUG","logger":"com.example.OrderService","thread":"main","msg":"Change state to PAID","kv":{"state":"PAID"}}
```

**TRACE on:**
```json
{"ts":1748765696789,"level":"DEBUG","logger":"com.example.OrderService","thread":"main","msg":"Change state to PAID","kv":{"state":"PAID"},"err":{"class":"java.lang.Throwable","msg":"stackWhenTrace","stack":["\tat com.example.OrderService.processPayment(OrderService.java:87)","\tat com.example.CheckoutController.handleCheckout(CheckoutController.java:42)","\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)","\t..."]}}
```

The JSON output adds an `err` object with `class`, `message`, and `stack` array — the same fields used for real exceptions. The `ConsoleAppenderJson` handles `ThrowableProxy` the same way whether it comes from a real exception or from `stackWhenTrace()`.

### Dev Console Appender (`ConsoleAppenderDev`)

**TRACE off:**
```
12:34:56.789 [main] DEBUG  c.e.OrderService - Change state to PAID
```

**TRACE on:**
```
12:34:56.789 [main] DEBUG  c.e.OrderService - Change state to PAID
java.lang.Throwable: stackWhenTrace
	at com.example.OrderService.processPayment(OrderService.java:87)
	at com.example.CheckoutController.handleCheckout(CheckoutController.java:42)
	...
```

## `kv()` Shorthand

The `LoggingEventBuilderWrapper` also provides a `kv()` method as a shorthand for `addKeyValue()`:

```java
// These are equivalent:
log.atDebug().kv("userId", id).log("Processing user");
log.atDebug().addKeyValue("userId", id).log("Processing user");
```

Combined with `stackWhenTrace()`:

```java
log.atDebug().stackWhenTrace()
    .kv("orderId", orderId)
    .kv("total", total)
    .log("Order {orderId} placed for ${total}");
```

## ThrowableProxy and Exception Serialization

When a throwable is attached to a log event (whether from a real exception or from `stackWhenTrace()`), it flows through Logback's `ThrowableProxy`. This is what `ConsoleAppenderJson` reads to produce the `err` field:

| `err` field | Source |
|-------------|--------|
| `err.class` | `ThrowableProxy.getClassName()` — the exception type |
| `err.msg` | `ThrowableProxy.getMessage()` — the exception message |
| `err.stack` | `ThrowableProxyConverter` — formatted stack trace lines |
| `err.hash` | `Wyhash64` of the sanitized stack — for deduplication |
| `err.cause` | `ThrowableProxy.getCause()` — wrapped cause (if any) |

For `stackWhenTrace()`, the throwable is a plain `java.lang.Throwable` with message `"stackWhenTrace"`. There is no real cause — it exists solely to carry the call stack. The hash field lets you group identical stack traces in dashboards (e.g. Elasticsearch) without storing full stacks repeatedly.

## Configuration

No special configuration needed. `stackWhenTrace()` works with any Logback appender. To activate the call-stack output, set the logger to TRACE level:

```xml
<configuration>
    <!-- In logback.xml, enable TRACE for the packages you want call stacks from -->
    <logger name="com.example" level="TRACE" />
    
    <!-- Or for everything (verbose!) -->
    <root level="TRACE">
        <appender-ref ref="JSON" />
    </root>
</configuration>
```

At runtime, change it via JMX or Logback's `scan=true` feature — no restart needed.