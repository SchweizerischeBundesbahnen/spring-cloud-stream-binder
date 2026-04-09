# Publisher Confirmations

Demonstrates how to get explicit acknowledgment from the Solace broker that a published message was successfully persisted. By attaching a `CorrelationData` object to the message, you can use a `Future` to wait for (or asynchronously receive) the broker's confirmation.

## Features Demonstrated

- Creating and attaching a `CorrelationData` instance to outbound messages
- Using `SolaceBinderHeaders.CONFIRM_CORRELATION` to enable publisher confirmations
- Waiting synchronously for broker acknowledgment with `correlationData.getFuture().get()`
- Handling publish failures and timeouts

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

When the application starts, it publishes one sample message (`startup-confirm-msg`) and logs whether the broker confirmed it.

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: confirmConsumer
    stream:
      bindings:
        confirmPublisher-out-0:                    # (1)
          destination: example/confirm/topic
        confirmConsumer-in-0:
          destination: example/confirm/topic
          group: confirm-group
```

1. **`confirmPublisher-out-0`** — The output binding used by `StreamBridge` to publish messages. The binding name follows the convention `<beanName>-out-<index>`, but since publishing is done via `StreamBridge`, the binding name is just a configuration key.

> **Note:** Publisher confirmations only work with the default `qualityOfService: AT_LEAST_ONCE` (persistent messages). Direct/non-persistent messages do not support broker confirmations.

## Code Walkthrough

```java
@Service
class PublishService {
    private final StreamBridge streamBridge;

    public boolean publishAndWait(String payload) throws Exception {
        CorrelationData correlationData = new CorrelationData();         // (1)
        Message<String> msg = MessageBuilder.withPayload(payload)
          .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
          .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .setHeader(SolaceBinderHeaders.CONFIRM_CORRELATION,
                           correlationData)                              // (2)
                .build();

        streamBridge.send("confirmPublisher-out-0", msg);                // (3)

        try {
            correlationData.getFuture().get(5, TimeUnit.SECONDS);        // (4)
            return true;  // Broker confirmed receipt
        } catch (TimeoutException e) {
            return false; // Broker did not confirm in time
        }
    }
}
```

1. **Create `CorrelationData`** — Each message gets its own `CorrelationData` instance. This object holds a `CompletableFuture` that will be completed when the broker responds.
2. **Attach to header** — The `SolaceBinderHeaders.CONFIRM_CORRELATION` header tells the binder to track this message's delivery status. This header is **not** sent to the broker — it is consumed internally by the binder.
3. **Publish** — The message is sent via `StreamBridge`. At this point, the message is transmitted to the broker but not yet confirmed.
4. **Wait for confirmation** — `getFuture().get(5, TimeUnit.SECONDS)` blocks until the broker sends an acknowledgment or the timeout expires. If the publish failed, the Future throws an `ExecutionException`.

The added TTL and DMQ-eligibility headers are regular Solace message properties; they do not change how publisher confirms are correlated.

```java
@Bean
public Consumer<String> confirmConsumer() {
    return msg -> {
        log.info("Received: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

A standard consumer that receives the confirmed messages from the queue.

```java
@EventListener(ApplicationReadyEvent.class)
public void publishStartupSample() {
  try {
    boolean confirmed = publishService.publishAndWait("startup-confirm-msg");
    log.info("Published startup-confirm-msg and broker confirmation result: {}", confirmed);
  } catch (Exception e) {
    log.error("Failed to publish startup-confirm-msg", e);
  }
}
```

The interactive run uses the same `publishAndWait(...)` path automatically once the application is ready, so you can observe publisher confirms without writing extra code. The included test still calls `publishAndWait(...)` directly with its own payload and waits for that payload to arrive.

## What to Observe

```
INFO  Published startup-confirm-msg and broker confirmation result: true
INFO  Received: startup-confirm-msg
```

The `publishAndWait` method returns `true` after the broker confirms receipt of the persistent message. The consumer then processes it from the queue.

**Failure scenario:** If the broker is unreachable or the destination is misconfigured, `getFuture().get()` will either throw an `ExecutionException` (publish NACK) or a `TimeoutException` (no response in time).

## When to Use This Pattern

- Financial transactions or other critical messages where you must know the message reached the broker
- Synchronous publish-and-confirm workflows
- Building reliable at-least-once publishing guarantees at the application level

## Related API Documentation

- [Publisher Confirmations](../../API.md#publisher-confirmations) — Full documentation with code examples
- [Failed Producer Message Error Handling](../../API.md#failed-producer-message-error-handling) — Error channels and producer error handling
- [Solace Binder Headers](../../API.md#solace-binder-headers) — `solace_scst_confirmCorrelation` header reference
