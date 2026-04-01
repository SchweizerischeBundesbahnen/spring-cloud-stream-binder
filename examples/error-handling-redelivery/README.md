# Error Handling — Redelivery (Retry + NACK)

Demonstrates how to combine Spring's internal retry template with Solace's broker-level message redelivery. When a consumer fails to process a message, Spring retries locally first. If all local retries are exhausted, the binder NACKs the message, causing the broker to redeliver it.

## Features Demonstrated

- Configuring `max-attempts` for Spring's internal retry template
- Configuring `back-off-initial-interval` and `back-off-max-interval` for exponential backoff
- How the `solace_redelivered` header indicates broker-level redelivery
- The interaction between application-level retries and broker-level redelivery

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
      definition: retryProducer;retryConsumer
    stream:
      bindings:
        retryProducer-out-0:
          destination: example/retry/topic
        retryConsumer-in-0:
          destination: example/retry/topic
          group: retry-group
          consumer:
            max-attempts: 5                       # (1)
            back-off-initial-interval: 100        # (2)
            back-off-max-interval: 500            # (3)
```

1. **`max-attempts: 5`** — Spring's retry template will attempt to process each message up to 5 times (1 initial + 4 retries) before giving up. All retries happen in-memory without re-fetching the message from the broker.
2. **`back-off-initial-interval: 100`** — The first retry waits 100ms.
3. **`back-off-max-interval: 500`** — Subsequent retries use exponential backoff, capped at 500ms.

## Code Walkthrough

```java
@Bean
public Consumer<Message<String>> retryConsumer() {
    return msg -> {
        int count = ATTEMPT_COUNT.incrementAndGet();
        log.info("Attempt {}: {} (redelivered: {})", count, msg.getPayload(),
            msg.getHeaders().get(SolaceHeaders.REDELIVERED));     // (1)
        if (count < 3) {
            throw new RuntimeException("Retry attempt " + count); // (2)
        }
        log.info("Succeeded on attempt {}", count);               // (3)
        SUCCESS_COUNT.incrementAndGet();
    };
}
```

1. **`SolaceHeaders.REDELIVERED`** — This Solace header is `true` when the broker has redelivered the message (after a NACK). On the first delivery it is `false`; on redeliveries after a broker NACK, it becomes `true`.
2. **Throw on first 2 attempts** — The consumer simulates a transient failure that resolves on the 3rd attempt.
3. **Success** — After enough attempts, the message is processed and ACKed.

## What to Observe

```
INFO  Attempt 1: retry-me (redelivered: false)
INFO  Attempt 2: retry-me (redelivered: false)
INFO  Attempt 3: retry-me (redelivered: false)
INFO  Succeeded on attempt 3
```

In this example, the consumer succeeds on attempt 3, which is within the `max-attempts: 5` limit. All retries happen locally via Spring's retry template (notice `redelivered: false` — the broker hasn't redelivered yet).

**What would happen if `max-attempts` was exceeded:**

1. Spring's retry template exhausts all 5 attempts.
2. The binder sends a NACK (negative acknowledgment) with `FAILED` settlement to the broker.
3. The broker redelivers the message (now with `redelivered: true`).
4. The full retry cycle starts again from attempt 1.
5. This continues until the message succeeds or the queue's `queueMaxMsgRedelivery` limit is reached.

## Two Levels of Retry

```
┌─────────────────────────────────────────────┐
│ Spring Retry Template (in-memory)           │
│  Attempt 1 → fail → wait 100ms             │
│  Attempt 2 → fail → wait 200ms             │
│  Attempt 3 → fail → wait 400ms             │
│  Attempt 4 → fail → wait 500ms             │
│  Attempt 5 → fail → ALL RETRIES EXHAUSTED  │
├─────────────────────────────────────────────┤
│ Solace Broker NACK → Redeliver             │
│  (broker sends the message again)           │
│  Spring Retry Template starts over...       │
└─────────────────────────────────────────────┘
```

## When to Use This Pattern

- Transient failures (network timeouts, temporary database unavailability)
- You want to distinguish between "retry locally" and "retry from broker" strategies
- You need exponential backoff for local retries

## Related API Documentation

- [Message Redelivery](../../API.md#message-redelivery) — How broker-level NACK redelivery works internally
- [Failed Consumer Message Error Handling](../../API.md#failed-consumer-message-error-handling) — Overview of all error handling strategies
- [Mutating Messages while using Spring's Retry Template](../../API.md#mutating-messages-while-using-springs-retry-template) — Important caveat about payload mutations persisting across retries
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `queueMaxMsgRedelivery` to limit broker-level redeliveries
