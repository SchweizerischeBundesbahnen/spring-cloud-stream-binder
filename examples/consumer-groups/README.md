# Consumer Groups (Durable vs Anonymous Queues)

Demonstrates the critical difference between **named consumer groups** (durable queues) and **anonymous consumer groups** (temporary queues), and why consumer groups matter for reliable messaging.

## Features Demonstrated

- Named consumer group with the `group` property → automatic durable queue provisioning
- Anonymous consumer (no `group` set) → temporary queue that is deleted on disconnect
- Topic-to-queue mapping for both consumer types
- Message persistence during consumer downtime (durable groups only)

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
      definition: queuedConsumer;anonConsumer     # (1)
    stream:
      bindings:
        publisher-out-0:
          destination: example/groups/topic               # (2)
        queuedConsumer-in-0:
          destination: example/groups/topic
          group: durable-group                            # (3)
        anonConsumer-in-0:
          destination: example/groups/topic                # (4)
```

1. **Independent beans** — a publisher using a `@Scheduled` task, a durable consumer, and an anonymous consumer.
2. **Shared destination** — All three bindings use the same topic `example/groups/topic`.
3. **`group: durable-group`** — This is the key property. Setting it causes the binder to provision a **durable queue** named `scst/wk/durable-group/plain/example/groups/topic`. This queue survives application restarts and broker reboots. Messages published while the consumer is offline accumulate on the queue.
4. **No `group` set** — Without a group, the binder creates a **temporary (anonymous) queue** with a random name. This queue is deleted as soon as the consumer disconnects, and any unprocessed messages are lost.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class ConsumerGroupsApp {

    private final AtomicInteger count = new AtomicInteger(1);
    private final StreamBridge streamBridge;

    public ConsumerGroupsApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Scheduled(fixedRate = 2000)
    public void publish() {
        String msg = "group-msg-" + count.getAndIncrement();
        streamBridge.send("publisher-out-0", msg);
        log.info("Published: {}", msg);
    }
}
```

Publishes a message every 2 seconds to `example/groups/topic` cleanly using a scheduled task.

```java
@Bean
public Consumer<String> queuedConsumer() {
    return msg -> {
        log.info("Durable consumer received: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

This consumer is bound to the **durable queue** `scst/wk/durable-group/plain/example/groups/topic`. Messages are guaranteed to be delivered at least once, even after restarts.

```java
@Bean
public Consumer<String> anonConsumer() {
    return msg -> log.info("Anonymous consumer received: {}", msg);
}
```

This consumer is bound to a **temporary queue**. It receives messages only while the application is running. If the application restarts, the temporary queue is destroyed and a new one is created.

## What to Observe

Both consumers receive messages while the application is running:

```
INFO  Durable consumer received: group-msg-1711929600000
INFO  Anonymous consumer received: group-msg-1711929600000
```

**Experiment — Observe durability:**

1. Stop the application while messages are being published externally (or by another instance).
2. Restart the application.
3. The `queuedConsumer` receives all buffered messages from the durable queue.
4. The `anonConsumer` receives nothing from the downtime period — its temporary queue was deleted.

## When to Use This Pattern

| Scenario | Use `group` (Durable) | No `group` (Anonymous) |
|---|---|---|
| Must not lose messages during downtime | ✅ | ❌ |
| Multiple instances share work (competing consumers) | ✅ | ❌ |
| Broadcast/fan-out to all instances | ❌ | ✅ |
| Temporary debugging/monitoring consumers | ❌ | ✅ |

## Related API Documentation

- [Overview](../../API.md#overview) — How consumer groups map to durable queues and topic-to-queue mapping
- [Generated Queue Name Syntax](../../API.md#generated-queue-name-syntax) — How the queue name `scst/wk/durable-group/plain/...` is constructed
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `provisionDurableQueue`, `queueNameExpression`, and other queue options
