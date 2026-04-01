# Partitioned Queues

Demonstrates how to use Solace's native Partitioned Queue feature to ensure strict message ordering per partition key, even with concurrent consumers. Messages with the same partition key are always delivered to the same consumer flow.

## Features Demonstrated

- Setting `SolaceBinderHeaders.PARTITION_KEY` on outbound messages
- How the Solace broker routes messages by partition key
- Combining partitioned queues with `concurrency: 2` for parallel processing with per-key ordering
- Verifying that same-key messages are consistently delivered to the same thread

## Prerequisites

- Java 17+
- Docker (for a local Solace broker, or an existing broker)
- Solace PubSub+ broker with Partitioned Queue support

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
      definition: partitionedPublisher;partitionedConsumer
    stream:
      bindings:
        partitionedPublisher-out-0:
          destination: example/partitioned/topic
        partitionedConsumer-in-0:
          destination: example/partitioned/topic
          group: partitioned-group
          consumer:
            concurrency: 2                        # (1)
      solace:
        bindings:
          partitionedConsumer-in-0:
            consumer:
              provisionDurableQueue: true          # (2)
```

1. **`concurrency: 2`** — Two worker threads consume from the queue. Without partitioning, message ordering would not be guaranteed. With partitioning, messages sharing the same key are delivered to the same consumer flow, preserving per-key ordering.
2. **`provisionDurableQueue: true`** — The binder provisions the durable queue. The Solace broker enables partitioning on queues when it detects the `JMSXGroupID` property (which the binder sets from `PARTITION_KEY`).

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class PartitionedQueuesApp {
    private static final Logger log = LoggerFactory.getLogger(PartitionedQueuesApp.class);
    // Track which instance processed which message to assert partitioning correctness
    public static final BlockingQueue<String> RECEIVED_INSTANCE_0 = new LinkedBlockingQueue<>();
    public static final BlockingQueue<String> RECEIVED_INSTANCE_1 = new LinkedBlockingQueue<>();
    
    // Some mock data to partition by
    private static final String[] PAYLOADS = {"apple", "banana", "cherry", "date"};
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;

    public PartitionedQueuesApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(PartitionedQueuesApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        int index = count.getAndIncrement() % PAYLOADS.length;
        String payload = PAYLOADS[index];
        // The binder uses the payload string as the partition key natively
        Message<String> msg = MessageBuilder.withPayload(payload).build();
        streamBridge.send("partitionedPublisher-out-0", msg);
        log.info("Published: {}", payload);
    }
}
```

1. **Alternate keys** — Even-numbered messages get `"Key-A"`, odd-numbered get `"Key-B"`. This simulates a multi-tenant or multi-entity scenario where ordering matters within each key.
2. **`SolaceBinderHeaders.PARTITION_KEY`** — This header tells the binder to set the Solace partition key on the outbound message. The broker uses this key to determine which consumer flow receives the message.

```java
@Bean
public Consumer<Message<String>> partitionedConsumer() {
    return msg -> {
        String payload = msg.getPayload();
        String thread = Thread.currentThread().getName();
        log.info("Received '{}' on thread '{}'", payload, thread);
        MSG_TO_THREAD.put(payload, thread);                          // (1)
    };
}
```

1. **Track thread affinity** — Records which thread processed each message. The integration test asserts that all `Key-A` messages go to the same thread and all `Key-B` messages go to the same thread.

## What to Observe

```
INFO  Received 'msg-1' on thread 'binding-0'    # Key-B
INFO  Received 'msg-2' on thread 'binding-1'    # Key-A
INFO  Received 'msg-3' on thread 'binding-0'    # Key-B → same thread as msg-1
INFO  Received 'msg-4' on thread 'binding-1'    # Key-A → same thread as msg-2
```

Messages with the same partition key are always delivered to the same consumer flow (thread). Messages with different keys can be processed in parallel.

**How it works internally:**

1. The publisher sets `SolaceBinderHeaders.PARTITION_KEY` on each message.
2. The binder translates this to the Solace `JMSXGroupID` message property.
3. The broker hashes the partition key and assigns the message to a specific partition within the queue.
4. Each partition is bound to exactly one consumer flow, ensuring per-key ordering.
5. With `concurrency: 2`, two flows exist — the broker distributes partitions across them.

## When to Use This Pattern

- Order processing where events for the same order ID must be processed sequentially
- Multi-tenant systems where per-tenant ordering is required
- Any scenario where you want **parallel processing** (multiple keys) with **per-key ordering**

> **⚠️ Ordering caveat:** Per-key ordering is only guaranteed if your consumer is single-threaded per flow. If your message handler offloads work to async threads (`CompletableFuture`, `@Async`), the processing order may become non-deterministic.

## Related API Documentation

- [Partitioning](../../API.md#partitioning) — Full documentation on partition keys, native PubSub+ support, and ordering caveats
- [Solace Binder Headers](../../API.md#solace-binder-headers) — `solace_scst_partitionKey` header reference
- [Consumer Concurrency](../../API.md#consumer-concurrency) — How concurrency interacts with partitioned queues
