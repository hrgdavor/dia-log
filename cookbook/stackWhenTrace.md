# Stack When Trace — Conditional Call-Stack Visibility

`stackWhenTraceEnabled()` is the current API on the builder wrapper. It attaches a synthetic `Throwable` only when TRACE is enabled, so you can keep production logs compact while still getting a stack trace when you need it.

## The Problem

You often want DEBUG-level logs to stay visible in production, but you also want to see the call stack when investigating an issue. Without a conditional mechanism, you would either log the stack every time or lose it entirely.

## The Solution

```java
log.atDebug().stackWhenTraceEnabled()
    .kv("state", state)
    .log("Change state to {state}");
```

The behavior adapts automatically based on the current TRACE level.

## How It Works

When `stackWhenTraceEnabled()` is called, a flag is set on the wrapper. At log time:

1. If TRACE is enabled, a `Throwable("stackWhenTraceEnabled")` is attached as the cause via SLF4J's `setCause()`.
2. If TRACE is disabled, the log fires normally with just the message.

## Current JSON Shape

The current writer serializes exception data as flat top-level fields rather than a nested `err` object:

```json
{"ts":1748765696789,"level":"DEBUG","logger":"com.example.OrderService","thread":"main","msg":"Change state to PAID","state":"PAID","errClass":"java.lang.Throwable","errMessage":"stackWhenTraceEnabled","stack":"...","errHash":123456789}
```

The important fields are:

- `errClass` — the exception type
- `errMessage` — the exception message
- `stack` — sanitized stack-trace text written by `JsonLogWriter`
- `errHash` — a deterministic 64-bit fingerprint for grouping and deduplication

## `kv()` Shorthand

The wrapper also provides `kv()` as a convenience for `addKeyValue()`:

```java
log.atDebug().kv("orderId", orderId)
    .kv("total", total)
    .log("Order {orderId} placed for ${total}");
```

## Configuration

No special configuration is required beyond enabling TRACE on the logger you want to inspect. The current implementation works with the JSON writer used by `JsonAppender` and `JsonAppenderRolling`.