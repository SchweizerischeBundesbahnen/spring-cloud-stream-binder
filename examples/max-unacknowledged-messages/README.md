# Max Unacknowledged Messages

Demonstrates how to configure the broker queue's "Maximum Delivered Unacknowledged Messages per Flow" setting (`maxDeliveredUnackedMsgsPerFlow`) via SEMP before starting the consumers. This is the broker-side setting many teams informally refer to as `maxUnacknowledgedMessages`, but it is **not** something the binder can provision through consumer YAML.

The sample starts one slow consumer profile, one fast consumer profile, and one publisher profile that sends a burst of messages to the shared queue. Before each consumer binding starts, the application ensures the queue exists with `maxDeliveredUnackedMsgsPerFlow=1` via SEMP.

## Features Demonstrated

- Provisioning the example queue via SEMP with `maxDeliveredUnackedMsgsPerFlow: 1`
- A slow consumer profile and a fast consumer profile sharing that same queue configuration
- A publisher profile that sends a short burst to the shared queue
- Why a low broker-side delivered-unacknowledged limit lets the fast consumer take more of the backlog
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

The sample uses the broker management API, so pass SEMP connection settings as well:

Start the slow consumer first:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=slow-consumer \
  -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default --app.semp.host=http://localhost:8081 --app.semp.username=admin --app.semp.password=admin"
```

Start the fast consumer in a second terminal:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=fast-consumer \
  -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default --app.semp.host=http://localhost:8081 --app.semp.username=admin --app.semp.password=admin"
```

Start the publisher in a third terminal:

```bash
mvn spring-boot:run \
  -Dspring-boot.run.profiles=publisher \
  -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Configuration Explained

```yaml
spring:
  cloud:
    stream:
      default:
        consumer:
          autoStartup: false                  # (1)
      bindings:
        loadBalancedConsumer-in-0:
          destination: example/max-unacknowledged/topic
          group: max-unacknowledged-group
      solace:
        bindings:
          loadBalancedConsumer-in-0:
            consumer:
              provisionDurableQueue: false      # (2)
app:
  semp:
    host: http://localhost:8081                 # (3)
    username: admin
    password: admin
  processing-delay-ms: 2000                     # (4)
```

1. **`consumer.autoStartup: false`** — The consumer binding is held back until the application has configured the example queue via SEMP.
2. **`provisionDurableQueue: false`** — The example queue is provisioned explicitly through SEMP so the broker-side `maxDeliveredUnackedMsgsPerFlow` value is set before the consumer starts.
3. **`app.semp.*`** — Broker management connection settings used to create or recreate the example queue with the required broker-side limit.
4. **`processing-delay-ms: 2000`** — The slow profile simulates a slow downstream dependency without blocking the Solace dispatcher thread, because the binder is already running the function on its worker thread.

Both consumer profiles share the same queue configuration. The fairness effect comes from the broker queue's `maxDeliveredUnackedMsgsPerFlow=1`, not from different per-binding YAML values.

## Code Walkthrough

```java
@Bean
ApplicationRunner consumerQueueProvisioner() {
    return args -> {
        ensureQueueWithConfiguredMaxDeliveredUnackedMsgsPerFlow();
        bindingsLifecycleController.start(BINDING_NAME);
    };
}
```

- `ensureQueueWithConfiguredMaxDeliveredUnackedMsgsPerFlow()` uses SEMP to create or recreate the example queue with `maxDeliveredUnackedMsgsPerFlow=1`, ingress/egress enabled, and `modify-topic` permission so the binder can add the destination subscription.
- `BindingsLifecycleController.start(...)` starts the consumer binding only after that broker-side queue setting is in place.

```java
@Bean
@ConditionalOnProperty(name = "app.consumer.enabled", havingValue = "true")
public Consumer<String> loadBalancedConsumer() {
    return payload -> {
        if (processingDelayMs > 0) {
            Thread.sleep(processingDelayMs);
        }
        PROCESSED_COUNTS.computeIfAbsent(instanceName, key -> new AtomicInteger()).incrementAndGet();
    };
}
```

The same consumer code is reused for both profiles. The profile changes only the artificial processing delay.

```java
streamBridge.send("publisher-out-0", MessageBuilder.withPayload(payload)
        .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
        .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
        .build());
```

The publisher sends a short burst of durable messages with a TTL and `solace_dmqEligible=true`, so the example stays aligned with the recommended header defaults.

## What to Observe

- While the backlog is still being drained, the slow consumer only processes a small number of messages.
- During that same period, the fast consumer processes more of the burst.
- The queue still guarantees delivery, but the slow consumer no longer hoards a large batch of unacknowledged messages because the broker only allows one outstanding delivered-but-unacknowledged message per flow.

> [!IMPORTANT]
> The binder property `spring.cloud.stream.solace.bindings.<binding>.consumer.subAckWindowSize` does **not** provision this broker queue setting. In the binder implementation it only maps to the client flow subscribe acknowledgment window (`ConsumerFlowProperties.setTransportWindowSize(...)`) and inherits from session `SUB_ACK_WINDOW_SIZE` when unset. If you need the broker queue limit shown in this example, configure it via SEMP or equivalent broker management.

## Related API Documentation

- [Consumer Concurrency](../../API.md#consumer-concurrency)
- [Solace Consumer Properties](../../API.md#solace-consumer-properties)
