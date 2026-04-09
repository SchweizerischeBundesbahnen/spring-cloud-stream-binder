# Error Handling — Redelivery (Spring Retry + Broker Redelivery)

Demonstrates how Spring's local retry template and Solace broker redelivery work together. The sample intentionally fails every non-redelivered delivery so the first broker delivery exhausts all local retries. The next broker redelivery succeeds and exposes the `solace_redelivered` header.

## Features Demonstrated

- Configuring `max-attempts` for Spring's internal retry template
- Configuring `back-off-initial-interval` and `back-off-max-interval` for exponential backoff
- How the `solace_redelivered` header indicates broker-level redelivery
- Exhausting local retries before allowing the broker-redelivered copy to succeed

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
      definition: retryConsumer
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
private final AtomicBoolean published = new AtomicBoolean();

@Scheduled(initialDelay = 1000, fixedDelay = 60000)
public void publish() {
  if (published.compareAndSet(false, true)) {
    streamBridge.send("retryProducer-out-0", MessageBuilder.withPayload("retry-me")
      .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
      .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
      .build());
    log.info("Published message designed for retry and broker redelivery");
  }
}

@Bean
public Consumer<Message<String>> retryConsumer() {
    return msg -> {
    boolean redelivered = Boolean.TRUE.equals(
      msg.getHeaders().get(SolaceHeaders.REDELIVERED, Boolean.class));  // (1)
    int count = TOTAL_ATTEMPTS.incrementAndGet();
    log.info("Attempt {}: {} (redelivered: {})", count, msg.getPayload(), redelivered);
    if (!redelivered) {
      throw new RuntimeException("Force broker redelivery after exhausting local retries"); // (2)
        }
    log.info("Succeeded after broker redelivery on attempt {}", count);   // (3)
        SUCCESS_COUNT.incrementAndGet();
    };
}
```

The publisher uses the same 30 second TTL and `solace_dmqEligible=true` combination recommended for durable traffic; the retry and broker redelivery behavior is unchanged.

1. **`SolaceHeaders.REDELIVERED`** — This Solace header is `true` only when the broker has redelivered the message after the binder settled the previous delivery as failed.
2. **Fail the first broker delivery** — Every non-redelivered attempt throws an exception, so Spring Retry exhausts all configured local attempts and the binder asks the broker to redeliver.
3. **Succeed on redelivery** — The broker-redelivered copy is processed successfully and ACKed.

## What to Observe

```
INFO  Attempt 1: retry-me (redelivered: false)
INFO  Attempt 2: retry-me (redelivered: false)
INFO  Attempt 3: retry-me (redelivered: false)
INFO  Attempt 4: retry-me (redelivered: false)
INFO  Attempt 5: retry-me (redelivered: false)
INFO  Attempt 6: retry-me (redelivered: true)
INFO  Succeeded after broker redelivery on attempt 6
```

Attempts 1 through 5 are all local retries inside Spring's retry template, so `redelivered` stays `false`. Attempt 6 is the first time the broker sends the message again, so `redelivered` flips to `true` and the consumer accepts the message.

**What happens step by step:**

1. Spring's retry template exhausts all 5 local attempts.
2. The binder sends a `FAILED` settlement to the broker.
3. The broker redelivers the same message.
4. On the next delivery, `SolaceHeaders.REDELIVERED` is `true`.
5. The consumer accepts the message and processing completes.

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
│  Attempt 6 → redelivered=true → success     │
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
