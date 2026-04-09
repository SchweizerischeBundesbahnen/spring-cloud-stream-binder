# Dynamic Destinations

Demonstrates how to publish messages to dynamically determined destinations at runtime using Spring's `StreamBridge`, rather than configuring fixed output bindings. A REST endpoint accepts a destination name and payload, and routes the message to whatever topic the caller specifies.

## Features Demonstrated

- Using `StreamBridge.send(destination, message)` to publish to arbitrary Solace topics with explicit TTL and DMQ eligibility
- Wildcard topic subscriptions (`example/dynamic/>`) to consume from a topic hierarchy
- Runtime-determined routing without static output binding configuration
- How dynamic destinations interact with topic-to-queue mapping

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

# Then send messages to dynamic destinations:
curl -X POST "http://localhost:8080/send?destination=example/dynamic/orders" -H "Content-Type: text/plain" -d "Order 123"
curl -X POST "http://localhost:8080/send?destination=example/dynamic/events" -H "Content-Type: text/plain" -d "Event ABC"
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: wildcardConsumer
    stream:
      bindings:
        wildcardConsumer-in-0:
          destination: "example/dynamic/>"        # (1)
          group: dynamic-group                    # (2)
```

1. **`destination: example/dynamic/>`** — The `>` is a Solace wildcard that matches **any topic** starting with `example/dynamic/`. This means the consumer's queue will receive messages published to `example/dynamic/orders`, `example/dynamic/events`, `example/dynamic/foo/bar`, etc.
2. **`group: dynamic-group`** — Creates a durable queue subscribed to the wildcard topic.

> **No output binding is configured.** The producer uses `StreamBridge` directly, which does not require a pre-configured output binding in `application.yml`.

## Code Walkthrough

```java
@RestController
public class DynamicDestinationsApp {
    private final StreamBridge streamBridge;

    @PostMapping("/send")
    public String publish(@RequestParam String destination, @RequestBody String payload) {
        log.info("Publishing to dynamic destination '{}': {}", destination, payload);
      streamBridge.send(destination, MessageBuilder.withPayload(payload)
          .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
          .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
          .build());                         // (1)
        return "Sent to " + destination;
    }
}
```

  1. **`streamBridge.send(destination, message)`** — `StreamBridge` is Spring Cloud Stream's API for sending messages without pre-configured bindings. The `destination` parameter becomes the Solace topic the message is published to, while the `Message<?>` envelope lets the sample apply the same 30 second TTL and DMQ-eligibility defaults as the rest of the suite. The binder creates an ephemeral producer binding on the fly.

```java
@Bean
public Consumer<String> wildcardConsumer() {
    return msg -> {
        log.info("Received from wildcard consumer: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

This consumer receives **all messages** that match the wildcard subscription `example/dynamic/>`, regardless of which specific sub-topic they were published to.

> **Alternative approach:** Instead of `StreamBridge.send(destination, ...)`, you can also use the `scst_targetDestination` header on a fixed output binding to redirect messages dynamically. See the [API documentation](../../API.md#dynamic-producer-destinations) for details on `BinderHeaders.TARGET_DESTINATION` and `SolaceBinderHeaders.TARGET_DESTINATION_TYPE`.

## What to Observe

```
# After sending: curl -X POST "http://localhost:8080/send?destination=example/dynamic/orders" -H "Content-Type: text/plain" -d "Order 123"
INFO  Publishing to dynamic destination 'example/dynamic/orders': Order 123
INFO  Received from wildcard consumer: Order 123

# After sending: curl -X POST "http://localhost:8080/send?destination=example/dynamic/events" -H "Content-Type: text/plain" -d "Event ABC"
INFO  Publishing to dynamic destination 'example/dynamic/events': Event ABC
INFO  Received from wildcard consumer: Event ABC
```

Both messages arrive at the same consumer because the wildcard subscription `example/dynamic/>` matches all sub-topics.

## When to Use This Pattern

- Multi-tenant systems where the destination depends on the tenant ID
- Event routing where the target topic is determined by business logic at runtime
- Fan-out to topic hierarchies where consumers subscribe to wildcards

## Related API Documentation

- [Dynamic Producer Destinations](../../API.md#dynamic-producer-destinations) — Full documentation on `scst_targetDestination`, `solace_scst_targetDestinationType`, and StreamBridge usage
- [Solace Producer Properties](../../API.md#solace-producer-properties) — `destinationType` for sending to queues vs topics
