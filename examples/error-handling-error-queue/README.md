# Error Handling — Error Queue (Dead Letter)

Demonstrates how to automatically route permanently failed messages to a dedicated Solace error queue using `autoBindErrorQueue: true`. After all retry attempts are exhausted, the binder republishes the failed message to an error queue instead of discarding it.

## Features Demonstrated

- Enabling `autoBindErrorQueue` to auto-provision a Solace error queue
- How Spring Retry's `max-attempts` interacts with the error queue
- Automatic error queue naming convention (`scst/error/wk/...`)
- Preserving failed messages for later inspection or reprocessing

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
      definition: failingConsumer
    stream:
      bindings:
        errorProducer-out-0:
          destination: example/error/topic
        failingConsumer-in-0:
          destination: example/error/topic
          group: error-group
          consumer:
            max-attempts: 1                       # (1)
      solace:
        bindings:
          failingConsumer-in-0:
            consumer:
              auto-bind-error-queue: true          # (2)
```

1. **`max-attempts: 1`** — Disables Spring's internal retry template (only one attempt, no retries). This means the very first failure immediately triggers the error queue flow. In production, you might set this higher (e.g., `3`) to allow transient failures to recover before routing to the error queue.
2. **`auto-bind-error-queue: true`** — This is the key Solace binder property. It instructs the binder to:
   - Provision a durable error queue named `scst/error/wk/error-group/plain/example/error/topic`
   - After all retry attempts are exhausted, **republish** the failed message to this error queue
   - ACK the original message on the source queue (removing it)

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class ErrorQueueApp {
    private static final Logger log = LoggerFactory.getLogger(ErrorQueueApp.class);
    public static final AtomicInteger ATTEMPT_COUNT = new AtomicInteger(0);
    private final StreamBridge streamBridge;

    public ErrorQueueApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(ErrorQueueApp.class, args); }

    @Scheduled(fixedRate = 5000)
    public void publishErrorTrigger() {
      streamBridge.send("errorProducer-out-0", MessageBuilder.withPayload("fail-me")
          .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
          .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
          .build());
        log.info("Published message that will fail");
    }
}
```

  Produces a message that will always fail processing. The published message carries a 30 second TTL and stays DMQ-eligible so the example mirrors the recommended durable-message headers.

```java
@Bean
public Consumer<String> failingConsumer() {
    return msg -> {
        int attempt = ATTEMPT_COUNT.incrementAndGet();
        log.info("Attempt {} for: {}", attempt, msg);
        throw new RuntimeException("Intentional failure for error queue demo");
    };
}
```

This consumer **always throws an exception**. Since `max-attempts: 1`, no retries are attempted. The binder catches the exception and republishes the message to the error queue.

## What to Observe

```
INFO  Attempt 1 for: fail-me
WARN  Message processing failed, republishing to error queue...
```

**What happens step by step:**

1. The `errorProducer` publishes `"fail-me"` to `example/error/topic`.
2. The broker routes the message to the consumer queue `scst/wk/error-group/plain/example/error/topic`.
3. The `failingConsumer` receives the message and throws a `RuntimeException`.
4. Since `max-attempts: 1`, no retries are attempted.
5. The binder republishes the failed message to `scst/error/wk/error-group/plain/example/error/topic`.
6. The binder ACKs the original message on the source queue.
7. The failed message now sits on the error queue for inspection, reprocessing, or manual intervention.

**Inspecting the error queue:** You can view messages on the error queue using the Solace broker's management UI (`http://localhost:8081`) under Queues → `scst/error/wk/error-group/plain/...`.

## Error Queue vs Dead Message Queue (DMQ)

| Feature | Error Queue (`autoBindErrorQueue`) | Dead Message Queue (DMQ) |
|---|---|---|
| **Trigger** | Application-level failure (exception thrown) | Broker-level TTL expiry or max redelivery exceeded |
| **Provisioned by** | Solace binder (automatically) | Broker administrator |
| **Contains** | Messages that were consumed but could not be processed | Messages that could not be delivered |
| **Naming** | `scst/error/wk/<group>/plain/<destination>` | Configured per-VPN on the broker |

## When to Use This Pattern

- You want to preserve failed messages for debugging or later reprocessing
- Your processing logic has permanent failure modes (e.g., invalid data, schema mismatches)
- You need a "dead letter" pattern at the application level

## Related API Documentation

- [Error Queue Republishing](../../API.md#error-queue-republishing) — Full documentation on error queues vs DMQs
- [Failed Consumer Message Error Handling](../../API.md#failed-consumer-message-error-handling) — Overview of all error handling strategies
- [Generated Error Queue Name Syntax](../../API.md#generated-error-queue-name-syntax) — How error queue names are generated
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `autoBindErrorQueue`, `errorQueueMaxDeliveryAttempts`, `errorMsgTtl`, `errorQueueNameExpression`, and other error queue properties
