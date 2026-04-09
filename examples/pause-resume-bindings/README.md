# Pause / Resume Consumer Bindings

Demonstrates how to dynamically pause and resume consumer bindings at runtime using Spring Boot Actuator's `/actuator/bindings` endpoint. While a binding is paused, messages accumulate on the Solace queue without being delivered. Resuming re-enables message delivery and flushes the backlog.

## Features Demonstrated

- Exposing the `/actuator/bindings` endpoint for binding lifecycle control
- Exposing a small `/send` helper endpoint so the sample can publish messages on demand
- Pausing a consumer binding via HTTP POST
- Resuming a paused binding to drain accumulated messages
- How pausing affects the underlying Solace flow without destroying the queue

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

# Publish a message while the binding is active:
curl -X POST http://localhost:8080/send \
  -H "Content-Type: text/plain" \
  --data 'msg1'

# Pause the binding:
curl -X POST http://localhost:8080/actuator/bindings/pausableConsumer-in-0 \
  -H "Content-Type: application/json" \
  -d '{"state":"PAUSED"}'

# Publish another message while paused:
curl -X POST http://localhost:8080/send \
  -H "Content-Type: text/plain" \
  --data 'msg2'

# Resume the binding:
curl -X POST http://localhost:8080/actuator/bindings/pausableConsumer-in-0 \
  -H "Content-Type: application/json" \
  -d '{"state":"RESUMED"}'
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: pausableConsumer
    stream:
      bindings:
        pausableConsumer-in-0:
          destination: example/pausable/topic
          group: pausable-group
management:
  endpoints:
    web:
      exposure:
        include: bindings                          # (1)
```

1. **`exposure.include: bindings`** — Exposes the `/actuator/bindings` endpoint over HTTP. This is the Spring Cloud Stream actuator endpoint that allows listing, pausing, and resuming bindings.

## Code Walkthrough

```java
@PostMapping("/send")
public String publish(@RequestBody String payload) {
  streamBridge.send("example/pausable/topic", MessageBuilder.withPayload(payload)
      .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
      .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
      .build());
    log.info("Published to pausable topic: {}", payload);
    return "Sent " + payload;
}

@Bean
public Consumer<String> pausableConsumer() {
    return msg -> {
        log.info("Received from pausable consumer: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

The `/send` helper endpoint publishes test messages into the same destination that the pausable consumer reads from, with a 30 second TTL and `solace_dmqEligible=true`. The pause/resume functionality itself is still provided by the Spring Cloud Stream framework and the Solace binder — no special pause logic is needed in the consumer.

## What to Observe

**Normal operation:**
```
INFO  Published to pausable topic: msg1
INFO  Received from pausable consumer: msg1
```

**After pausing:**
```bash
curl -X POST http://localhost:8080/actuator/bindings/pausableConsumer-in-0 \
  -H "Content-Type: application/json" -d '{"state":"PAUSED"}'
```
- No more messages are delivered to the consumer.
- Messages continue to accumulate on the durable queue (`scst/wk/pausable-group/plain/example/pausable/topic`).
- The Solace flow is stopped, but the queue still exists and receives messages.

**After resuming:**
```bash
curl -X POST http://localhost:8080/actuator/bindings/pausableConsumer-in-0 \
  -H "Content-Type: application/json" -d '{"state":"RESUMED"}'
```
- All accumulated messages are delivered immediately.
- Normal message delivery continues.

```text
INFO  Received from pausable consumer: msg2
```

**List all bindings:**
```bash
curl http://localhost:8080/actuator/bindings | jq .
```

```json
[
  {
    "bindingName": "pausableConsumer-in-0",
    "state": "started",      // or "paused"
    "input": true,
    "group": "pausable-group"
  }
]
```

## When to Use This Pattern

- Graceful maintenance windows — pause consumption, deploy changes, resume
- Backpressure management — pause a consumer to let an overloaded downstream system recover
- Testing/debugging — temporarily stop message processing without losing messages
- Blue/green deployments — pause old consumers before resuming new ones

> **Note:** There is no guarantee that pausing is instantaneous. Messages already in-flight or being processed by the binder may still be delivered immediately after the pause call returns.

## Related API Documentation

- [Consumer Bindings Pause/Resume](../../API.md#consumer-bindings-pauseresume) — Full documentation and caveats
