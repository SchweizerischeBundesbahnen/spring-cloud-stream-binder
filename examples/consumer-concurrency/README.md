# Consumer Concurrency

Demonstrates how to process messages in parallel by setting `concurrency > 1`, which instructs the binder to spin up multiple worker threads consuming from the same queue.

## Features Demonstrated

- Setting `concurrency: 4` on a consumer binding for parallel processing
- Multiple worker threads consuming from a shared durable queue
- How the binder's internal threading model handles concurrent message dispatch
- Trade-off between throughput and message ordering

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
      definition: concurrentConsumer
    stream:
      bindings:
        fastPublisher-out-0:
          destination: example/concurrency/topic
        concurrentConsumer-in-0:
          destination: example/concurrency/topic
          group: concurrent-group
          consumer:
            concurrency: 4                       # (1)
```

1. **`concurrency: 4`** — The binder starts **4 worker threads** that poll an internal `BlockingQueue` for messages. The Solace dispatcher thread places incoming messages into this internal queue, and worker threads pick them up for parallel processing. This means up to 4 messages can be processed simultaneously.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class ConcurrencyApp {

    private final AtomicInteger count = new AtomicInteger(1);
    private final StreamBridge streamBridge;

    public ConcurrencyApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Scheduled(fixedRate = 100)
    public void publishBurst() {
        int c = count.getAndIncrement();
        if (c <= 20) {
            streamBridge.send("fastPublisher-out-0", "msg-" + c);
            if (c == 20) {
                log.info("Finished publishing 20 messages in burst");
            }
        }
    }
}
```

Publishes 20 messages in quick succession, then stops. This burst of messages ensures there is enough backlog for the 4 worker threads to process in parallel.

```java
@Bean
public Consumer<String> concurrentConsumer() {
    return msg -> {
        THREADS.offer(Thread.currentThread().getName());
        log.info("Thread {} processing {}", Thread.currentThread().getName(), msg);
    };
}
```

Each invocation logs the thread name. When `concurrency: 4`, you will see 4 different thread names, proving that messages are processed in parallel.

## What to Observe

```
INFO  Thread binding-concurrentConsumer-in-0-1 processing msg-1
INFO  Thread binding-concurrentConsumer-in-0-3 processing msg-2
INFO  Thread binding-concurrentConsumer-in-0-2 processing msg-3
INFO  Thread binding-concurrentConsumer-in-0-4 processing msg-4
INFO  Thread binding-concurrentConsumer-in-0-1 processing msg-5
```

Notice the different thread names (`-1`, `-2`, `-3`, `-4`). Messages are distributed across all 4 worker threads.

**Internal architecture:**

1. The **Solace dispatcher thread** receives messages from the broker and places them into an in-memory `BlockingQueue` (never blocking the dispatcher).
2. **4 worker threads** continuously poll this internal queue.
3. When a worker thread picks up a message, it invokes your `Consumer` bean.
4. After processing completes, the worker thread sends an ACK back to the broker.

> **⚠️ Ordering caveat:** With `concurrency > 1`, there is **no guarantee of message ordering**. If you need strict ordering, use `concurrency: 1` or use [Partitioned Queues](../partitioned-queues/) to maintain per-key ordering.

## When to Use This Pattern

- High-throughput consumers where processing is the bottleneck
- I/O-bound message handlers (e.g., database writes, HTTP calls) where parallelism improves throughput
- Scenarios where message ordering is not required

## Related API Documentation

- [Consumer Concurrency](../../API.md#consumer-concurrency) — Full documentation of the concurrency model, threading architecture, and caveats
- [Inbound Message Flow](../../API.md#inbound-message-flow) — Detailed explanation of the dispatcher → queue → worker thread pipeline
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `maxUnacknowledgedMessages` to control backpressure
