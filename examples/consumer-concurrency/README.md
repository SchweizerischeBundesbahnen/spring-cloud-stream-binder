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
    private final AtomicBoolean burstPublished = new AtomicBoolean();

    private final StreamBridge streamBridge;

    public ConcurrencyApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 60000)
    public void publishBurst() {
        if (burstPublished.compareAndSet(false, true)) {
            for (int messageIndex = 1; messageIndex <= 20; messageIndex++) {
          String payload = "msg-" + messageIndex;
          try {
            streamBridge.send("fastPublisher-out-0", MessageBuilder.withPayload(payload)
                .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
                .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .build());
          } catch (MessagingException e) {
            log.error("Failed to publish {}", payload, e);
          }
            }
            log.info("Finished publishing 20 messages in burst");
        }
    }
}
```

  Publishes a single 20-message burst shortly after startup, then stops. The burst messages carry a 30 second TTL and `solace_dmqEligible=true`, which keeps the example aligned with the durable-message header guidance while still creating enough backlog for parallel processing. Each `streamBridge.send(...)` is wrapped in a `try/catch` for `org.springframework.messaging.MessagingException` — the call is synchronous and can throw once the producer's `sendRetryTimeoutMs` (default `60000`) is exhausted, so guarding it per message keeps one failed publish from aborting the rest of the burst. See [Failed Producer Message Error Handling](../../API.md#failed-producer-message-error-handling).

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
INFO  Thread example/concurrency/topic-0 processing msg-1
INFO  Thread example/concurrency/topic-1 processing msg-2
INFO  Thread example/concurrency/topic-2 processing msg-3
INFO  Thread example/concurrency/topic-3 processing msg-4
INFO  Thread example/concurrency/topic-0 processing msg-5
```

Worker threads are named after the binding's destination followed by a zero-based worker index, so `concurrency: 4` yields `example/concurrency/topic-0` through `example/concurrency/topic-3`. Messages are distributed across all 4 worker threads.

**Internal architecture:**

1. The **Solace dispatcher thread** receives messages from the broker and places them into an in-memory `BlockingQueue` (never blocking the dispatcher).
2. **4 worker threads** continuously poll this internal queue.
3. When a worker thread picks up a message, it invokes your `Consumer` bean.
4. After processing completes, the worker thread sends an ACK back to the broker.

> **⚠️ Ordering caveat:** With `concurrency > 1` and the default `partitionAware: false`, all worker threads compete for the same shared queue, so there is **no guarantee of message ordering**.
>
> To keep parallelism *and* per-partition ordering, set the consumer property `partitionAware: true`: the binder then gives every worker thread its own queue and routes each message by its partition key, so messages sharing a key are processed sequentially by one thread while different keys still run in parallel. See [Partitioned Queues](../partitioned-queues/README.md) and [Consumer Concurrency](../../API.md#consumer-concurrency).
>
> For strict global ordering across all messages, keep the consumer single-threaded with `concurrency: 1`. In either case, ordering only holds if your handler does the work inline — offloading to `CompletableFuture` or `@Async` makes it non-deterministic again.

## When to Use This Pattern

- High-throughput consumers where processing is the bottleneck
- I/O-bound message handlers (e.g., database writes, HTTP calls) where parallelism improves throughput
- Scenarios where message ordering is not required

## Related API Documentation

- [Consumer Concurrency](../../API.md#consumer-concurrency) — Full documentation of the concurrency model, threading architecture, and caveats
- [Inbound Message Flow](../../API.md#inbound-message-flow) — Detailed explanation of the dispatcher → queue → worker thread pipeline
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — client-flow `subAckWindowSize` (inherits `SUB_ACK_WINDOW_SIZE`) vs. producer `pubAckWindowSize` (inherits `PUB_ACK_WINDOW_SIZE`) and the broker queue `maxDeliveredUnackedMsgsPerFlow` limit
