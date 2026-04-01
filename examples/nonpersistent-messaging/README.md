# Non-Persistent Messaging (Direct / AT_MOST_ONCE)

Demonstrates fire-and-forget messaging using Solace's Direct delivery mode and `AT_MOST_ONCE` Quality of Service. Messages are delivered via topic subscriptions without persistent queues, trading delivery guarantees for lower latency and higher throughput.

## Features Demonstrated

- Setting `deliveryMode: DIRECT` on the producer to send non-persistent messages
- Setting `qualityOfService: AT_MOST_ONCE` on the consumer for topic-based consumption
- How topic-only messaging works without queue provisioning
- The trade-offs between persistent and non-persistent messaging

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
      definition: directConsumer
    stream:
      bindings:
        directPublisher-out-0:
          destination: example/direct/topic
        directConsumer-in-0:
          destination: example/direct/topic        # (1)
      solace:
        bindings:
          directPublisher-out-0:
            producer:
              deliveryMode: DIRECT                 # (2)
          directConsumer-in-0:
            consumer:
              qualityOfService: AT_MOST_ONCE       # (3)
```

1. **No `group` set on consumer** — Combined with `AT_MOST_ONCE`, this means no durable queue is created. The consumer subscribes directly to the topic.
2. **`deliveryMode: DIRECT`** — Messages are sent as non-persistent (Direct) messages. They are not written to the broker's message spool, resulting in lower latency.
3. **`qualityOfService: AT_MOST_ONCE`** — The consumer uses a topic subscription instead of a queue binding. Messages are delivered directly from the topic to the consumer without persistence.

> **Important:** Both settings should be used together. Using `deliveryMode: DIRECT` with `AT_LEAST_ONCE` would cause the producer to send non-persistent messages that the consumer expects to persist — leading to confusing behavior.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class NonPersistentApp {
    private static final Logger log = LoggerFactory.getLogger(NonPersistentApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;

    public NonPersistentApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(NonPersistentApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        String msg = "direct-msg-" + System.currentTimeMillis();
        streamBridge.send("directPublisher-out-0", msg);
        log.info("Published Direct msg: {}", msg);
    }
}
```

Publishes a timestamped message as a Direct (non-persistent) message. The broker does not persist this to its message spool.

```java
@Bean
public Consumer<String> directConsumer() {
    return msg -> {
        log.info("Received Direct msg: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

Receives messages via a topic subscription. There is no queue involved — the consumer subscribes directly to `example/direct/topic`.

## What to Observe

```
INFO  Received Direct msg: direct-msg-1711929600000
INFO  Received Direct msg: direct-msg-1711929602000
```

## Persistent vs Non-Persistent Comparison

| Aspect | AT_LEAST_ONCE (Default) | AT_MOST_ONCE (This Example) |
|---|---|---|
| **Delivery guarantee** | At least once | At most once (fire-and-forget) |
| **Message persistence** | Stored on broker spool | Not stored |
| **Consumer mechanism** | Queue-based | Topic subscription |
| **Latency** | Higher (spool write) | Lower (in-memory only) |
| **Throughput** | Lower | Higher |
| **Survives consumer downtime** | Yes (with durable queue) | No — messages are lost |
| **Acknowledgment** | ACK/NACK | None |

## When to Use This Pattern

- Real-time telemetry, sensor data, or market data where losing occasional messages is acceptable
- High-frequency event streams where latency matters more than reliability
- Broadcast notifications where consumers are expected to be online
- Scenarios where the cost of persistence (disk I/O) is not justified

## Related API Documentation

- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `qualityOfService` property with `AT_MOST_ONCE` and `AT_LEAST_ONCE` values
- [Solace Producer Properties](../../API.md#solace-producer-properties) — `deliveryMode` property with `PERSISTENT` and `DIRECT` values
