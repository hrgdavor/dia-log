

## single statement to log trace



```java
log.atDebug().stackWhenTrace()
    .kv("state",state)
    .log("Change state to {state}");
```



## config in logback.xml to show errors for missing keys

when contextual logger does not provide value for key (missing, null is ok) append error to message and error and add a stack trace	

```java
log.debug("user {user} logged out")
```





