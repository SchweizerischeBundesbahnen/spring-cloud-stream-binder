# Queue Provisioning Options

Demonstrates advanced queue provisioning features: custom queue names via SpEL expressions, exclusive queue access types, and additional topic subscriptions on a single queue.

## Features Demonstrated

- Using `queueNameExpression` with a SpEL expression to generate custom queue names
- Setting `queueAccessType` with the native endpoint access type constant for single-consumer queues
- Adding multiple `queueAdditionalSubscriptions` beyond the primary destination
- How custom queue naming overrides the default `scst/wk/...` pattern

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
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: customQueueConsumer
    stream:
      bindings:
        customQueueConsumer-in-0:
          destination: example/primary/topic
          group: custom-group
      solace:
        bindings:
          customQueueConsumer-in-0:
            consumer:
              queueNameExpression: "'custom-prefix.' + group + '.v1'"   # (1)
              queueAccessType: 0  # EndpointProperties.ACCESSTYPE_EXCLUSIVE  # (2)
              queueAdditionalSubscriptions: "example/extra/>,example/more"  # (3)
```

1. **`queueNameExpression`** — A SpEL expression that overrides the default queue naming convention. Instead of `scst/wk/custom-group/plain/example/primary/topic`, the binder provisions a queue named **`custom-prefix.custom-group.v1`**. The expression has access to variables: `destination`, `group`, `isAnonymous`, `properties.solace`, `properties.spring`.

2. **`queueAccessType: 0`** — The property is backed by the native JCSMP endpoint access type constants. In this example, `0` maps to `EndpointProperties.ACCESSTYPE_EXCLUSIVE`, so only one consumer can bind to the queue at a time.

3. **`queueAdditionalSubscriptions`** — Comma-separated list of extra topic subscriptions added to the queue, in addition to the primary `destination`. This queue will receive messages published to:
   - `example/primary/topic` (the primary destination)
   - `example/extra/>` (any sub-topic matching the wildcard)
   - `example/more` (exact match)

## Code Walkthrough

```java
@Bean
public Consumer<String> customQueueConsumer() {
    return msg -> {
        log.info("Received from custom generated queue: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

A standard consumer — the queue provisioning is entirely configuration-driven. The consumer itself doesn't need to know about the custom queue name or additional subscriptions.

## What to Observe

```
INFO  Received from custom generated queue: message-from-primary-topic
INFO  Received from custom generated queue: message-from-extra-subtopic
INFO  Received from custom generated queue: message-from-more-topic
```

The consumer receives messages from **all three subscriptions** on its single queue:
- `example/primary/topic`
- `example/extra/>`
- `example/more`

**Verify the queue name** in the Solace management UI (`http://localhost:8081`):
- Look for a queue named `custom-prefix.custom-group.v1` (not the default `scst/wk/...` pattern).

## SpEL Expression Context Variables

When writing custom `queueNameExpression` SpEL expressions, the following variables are available:

| Variable | Type | Description |
|---|---|---|
| `destination` | String | The binding's destination name |
| `group` | String | The consumer group name |
| `isAnonymous` | boolean | Whether this is an anonymous consumer group |
| `properties.solace` | Object | The Solace binding properties |
| `properties.spring` | Object | The Spring binding properties |

**Example expressions:**

```
"'myapp.' + group"                                    → myapp.custom-group
"'queue/' + destination.replaceAll('/', '.')"         → queue/example.primary.topic
"'scst/v2/' + group + '/' + destination"              → scst/v2/custom-group/example/primary/topic
```

## When to Use This Pattern

- Your organization has queue naming conventions that don't match the binder's default pattern
- You need a single consumer to listen to messages from multiple topics
- You want exclusive access to a queue (singleton consumer pattern)
- Pre-provisioned queues with `provisionDurableQueue: false` where the queue name must match exactly

## Related API Documentation

- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `queueNameExpression`, `queueAccessType`, `queueAdditionalSubscriptions`, `provisionDurableQueue`, and all other queue endpoint properties
- [Generated Queue Name Syntax](../../API.md#generated-queue-name-syntax) — Default naming convention and SpEL context variables
- [Solace Producer Properties](../../API.md#solace-producer-properties) — `queueAdditionalSubscriptions` and `requiredGroups` on the producer side
