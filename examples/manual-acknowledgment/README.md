# Manual Acknowledgment

Demonstrates how to take explicit control over message acknowledgment by disabling auto-ACK and manually calling `accept()`, `reject()`, or `requeue()` on the `AcknowledgmentCallback`.

## Features Demonstrated

- Accessing the `AcknowledgmentCallback` from the Spring `Message<?>` headers
- Calling `acknowledge(Status.ACCEPT)` to explicitly ACK a message
- Binding an error queue (`autoBindErrorQueue: true`) to catch rejected messages
- The difference between ACCEPT, REJECT, and REQUEUE behaviors

## Prerequisites

- Java 17+
- Docker (for a local Solace broker, or an existing broker)

## How to Run

**Option A — Automated test:**

```bash
mvn verify
```

**Option B — Interactive with a local broker:**

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: producer;manualAckConsumer
    stream:
      bindings:
        producer-out-0:
          destination: example/ack/topic
        manualAckConsumer-in-0:
          destination: example/ack/topic
          group: ack-group
      solace:
        bindings:
          manualAckConsumer-in-0:
            consumer:
              auto-bind-error-queue: true         # (1)
```

1. **`auto-bind-error-queue: true`** — Provisions a dedicated error queue (named `scst/error/wk/ack-group/plain/example/ack/topic`) where messages that are `REJECT`ed will be republished. Without this, rejected messages are discarded by the broker.

## Code Walkthrough

```java
@Bean
public Consumer<Message<String>> manualAckConsumer() {
    return msg -> {
        AcknowledgmentCallback ack = msg.getHeaders().get(
            IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK,
            AcknowledgmentCallback.class);                            // (1)
        String payload = msg.getPayload();
        log.info("Processing: {}", payload);
        if (ack != null) {
            ack.acknowledge(Status.ACCEPT);                           // (2)
            ACCEPTED.offer(payload);
        }
    };
}
```

1. **Get the callback** — The `AcknowledgmentCallback` is attached as a header to every inbound message. You retrieve it using `IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK`.
2. **Explicitly ACK** — Calling `acknowledge(Status.ACCEPT)` sends a positive acknowledgment to the Solace broker, which removes the message from the queue. In a real application, you would conditionally choose:

| Action | Method | Effect |
|---|---|---|
| **Accept** | `ack.acknowledge(Status.ACCEPT)` | Message is removed from the queue (ACK) |
| **Reject** | `ack.acknowledge(Status.REJECT)` | Message is moved to the error queue (if `autoBindErrorQueue: true`) or discarded |
| **Requeue** | `ack.acknowledge(Status.REQUEUE)` | Message is NACKed and redelivered by the broker |

> **Important:** If you want to ACK messages asynchronously (e.g., after an external callback completes), you must first call `ack.noAutoAck()` inside the message handler's thread. Otherwise, the binder will auto-acknowledge the message when the handler method returns.

## What to Observe

```
INFO  Processing: msg-1
INFO  Processing: msg-2
INFO  Processing: msg-3
```

Each message is explicitly accepted by the consumer. In a real application, you might conditionally reject or requeue messages based on business logic:

```java
// Example: reject invalid messages, requeue retriable failures
if (isInvalid(payload)) {
    ack.acknowledge(Status.REJECT);   // → error queue
} else if (isRetriable(exception)) {
    ack.acknowledge(Status.REQUEUE);  // → broker redelivers
} else {
    ack.acknowledge(Status.ACCEPT);   // → message removed
}
```

## When to Use This Pattern

- Processing depends on an external system (database, HTTP API) and you want to ACK only after confirmation
- You need fine-grained control over which messages succeed, fail, or need retry
- Asynchronous processing where the acknowledgment happens on a different thread than the message handler

## Related API Documentation

- [Manual Message Acknowledgment](../../API.md#manual-message-acknowledgment) — Full documentation including `noAutoAck()`, async ACK caveats, and `SolaceAcknowledgmentException`
- [Acknowledgment Actions](../../API.md#acknowledgment-actions) — Detailed table of ACCEPT/REJECT/REQUEUE behaviors
- [Error Queue Republishing](../../API.md#error-queue-republishing) — How the error queue works with REJECT
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `autoBindErrorQueue`, `errorQueueMaxDeliveryAttempts`, and related options
