# Programmatic Binding Control

Demonstrates how to keep a consumer binding stopped at startup and then start, stop, and restart it from application code using Spring Cloud Stream's `BindingsLifecycleController`. This is useful when the binding must stay offline until the application has completed some other initialization step.

## Features Demonstrated

- Starting with `consumer.autoStartup: false`
- Starting a binding programmatically with `BindingsLifecycleController.start(...)`
- Stopping a running binding programmatically with `BindingsLifecycleController.stop(...)`
- Restarting the binding and draining messages that accumulated on the durable queue while it was stopped
- Publishing example messages with a 30 second TTL and `solace_dmqEligible=true`

## Prerequisites

- Java 17+
- Docker (for a local Solace broker, or an existing broker)

## How to Run

**Option A — Automated test:**

```bash
mvn verify
```

**Option B — Interactive with a local broker:**

If you do not already have a local broker running, start one first using the command in [the examples index](../README.md).

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"

# Start the consumer binding explicitly.
curl -X POST http://localhost:8080/bindings/start

# Publish a message while the binding is running.
curl -X POST http://localhost:8080/send \
  -H "Content-Type: text/plain" \
  --data 'msg-1'

# Stop the binding.
curl -X POST http://localhost:8080/bindings/stop

# Publish another message while the binding is stopped.
curl -X POST http://localhost:8080/send \
  -H "Content-Type: text/plain" \
  --data 'msg-2'

# Start the binding again so the queued message is delivered.
curl -X POST http://localhost:8080/bindings/start
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: controlledConsumer
    stream:
      default:
        consumer:
          autoStartup: false                  # (1)
      bindings:
        controlledPublisher-out-0:
          destination: example/programmatic-control/topic
        controlledConsumer-in-0:
          destination: example/programmatic-control/topic
          group: programmatic-control-group   # (2)
management:
  endpoints:
    web:
      exposure:
        include: bindings,health,info         # (3)
```

1. **`consumer.autoStartup: false`** — The consumer binding is created in the application context but is not started automatically.
2. **`group: programmatic-control-group`** — A durable queue is used so messages published while the binding is stopped remain available when the binding restarts.
3. **`management.endpoints...`** — The standard Spring Cloud Stream binding endpoint is exposed for visibility, although the example itself uses `BindingsLifecycleController` directly.

## Code Walkthrough

```java
@PostMapping("/bindings/start")
public String startBinding() {
    bindingsLifecycleController.start(BINDING_NAME);
    return "Started " + BINDING_NAME;
}

@PostMapping("/bindings/stop")
public String stopBinding() {
    bindingsLifecycleController.stop(BINDING_NAME);
    return "Stopped " + BINDING_NAME;
}
```

The application calls Spring Cloud Stream's `BindingsLifecycleController` directly instead of using the actuator endpoint. That keeps the control logic inside the application, which is useful when startup depends on some other programmatic prerequisite.

```java
streamBridge.send("controlledPublisher-out-0", MessageBuilder.withPayload(payload)
        .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
        .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
        .build());
```

Published messages use a 30 second TTL and set `solace_dmqEligible=true`, matching the recommended header combination for durable traffic.

## What to Observe

- Before the first `/bindings/start` call, the consumer binding does not process messages because it is not running yet.
- After `/bindings/start`, normal delivery begins.
- After `/bindings/stop`, the durable queue still exists and continues buffering messages.
- After the next `/bindings/start`, buffered messages are delivered immediately.

## Related API Documentation

- [Programmatic Start](../../API.md#consumer-bindings-pauseresume)