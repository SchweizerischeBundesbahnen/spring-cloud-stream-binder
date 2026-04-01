# Large Message Chunking

Demonstrates how to send and receive messages that exceed the Solace broker's maximum message size by using the binder's built-in chunking mechanism. The binder automatically fragments large payloads into smaller chunks, sends them individually, and reassembles them transparently on the consumer side.

## Features Demonstrated

- Sending payloads larger than the broker's maximum message size (this example sends 1 MB)
- Automatic chunking and reassembly by the binder
- Using `SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT` or configuration to enable chunking
- Consumer-side transparent reassembly

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
      definition: chunkedConsumer
    stream:
      bindings:
        chunkedPublisher-out-0:
          destination: example/chunk/topic
        chunkedConsumer-in-0:
          destination: example/chunk/topic
          group: chunk-group                       # (1)
```

1. **`group: chunk-group`** — A consumer group is required for large message support. The binder uses a durable queue to ensure all chunks are received in order and reassembled correctly.

> **Important:** When using consumer groups with large message chunking, the queue should be either **exclusive** or a **partitioned queue** to ensure all chunks from the same message are delivered to the same consumer instance. Otherwise, chunks may be delivered to different consumers and reassembly will fail.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class LargeMessageApp {
    private static final Logger log = LoggerFactory.getLogger(LargeMessageApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final StreamBridge streamBridge;
    private final String largePayload;
    
    public LargeMessageApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
        this.largePayload = "A".repeat(1024 * 1024); // 1 MB payload
    }

    public static void main(String[] args) { SpringApplication.run(LargeMessageApp.class, args); }

    @Scheduled(fixedRate = 5000)
    public void publishLargeMessage() {
        streamBridge.send("chunkedPublisher-out-0", largePayload);
        log.info("Published large message of {} bytes", largePayload.length());
    }
}
```

Generates a 1 MB string payload. The broker's default max message size is typically much smaller. The binder detects that `LARGE_MESSAGE_SUPPORT` is enabled and automatically splits this into multiple chunk messages.

```java
@Bean
public Consumer<String> chunkedConsumer() {
    return msg -> {
        log.info("Received large message of length: {}", msg.length());
        RECEIVED.offer(msg);
    };
}
```

The consumer receives the **fully reassembled** 1 MB payload. The chunking and reassembly is completely transparent — the consumer sees a single `String` with the original content.

## What to Observe

```
INFO  Received large message of length: 1048576
```

The consumer receives the complete 1 MB message (1,048,576 bytes), even though it was transmitted as multiple smaller chunk messages.

**What happens under the hood:**

1. The publisher sets a 1 MB payload.
2. The binder splits the payload into N chunks, each within the broker's max message size.
3. Each chunk is published as a separate Solace message with internal headers (`solace_scst_chunkId`, `solace_scst_chunkIndex`, `solace_scst_chunkCount`).
4. The consumer's binder collects all chunks with the same `chunkId`.
5. When all chunks are received (determined by `chunkCount`), the binder reassembles them into the original payload.
6. The reassembled message is delivered to the consumer's `Consumer` bean.

## When to Use This Pattern

- Sending large files, reports, or data exports as messages
- Working with brokers that have strict max message size limits
- Any scenario where payload size may exceed the broker's MTU

> **Performance note:** Large message chunking adds overhead due to fragmentation and reassembly. For very large payloads, consider using an external object store (e.g., S3) and sending only a reference/URL in the message.

## Related API Documentation

- [Solace Binder Headers](../../API.md#solace-binder-headers) — `solace_scst_largeMessageSupport`, `solace_scst_chunkId`, `solace_scst_chunkIndex`, `solace_scst_chunkCount` headers
