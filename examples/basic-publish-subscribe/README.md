# Basic Publish & Subscribe

The simplest starting point: publish messages to a Solace topic and consume them from a durable queue — all with pure Spring Cloud Stream functional bindings.

## Features Demonstrated

- Using `@EnableScheduling`, `@Scheduled`, and a constructor-injected `StreamBridge` to cleanly produce messages at a fixed rate
- Declaring a `Consumer<String>` bean to consume messages
- Topic-to-queue mapping via `destination` and `group` in `application.yml`
- Automatic durable queue provisioning by the Solace binder

## Prerequisites

- Java 17+
- Docker (for a local Solace broker, or an existing broker)

## How to Run

**Option A — Automated test (recommended):**

```bash
mvn verify
```

This starts a Solace broker via Testcontainers, runs the application, and asserts correct behavior.

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
      definition: sink                           # (1)
    stream:
      bindings:
        source-out-0:                            # (2)
          destination: example/basic/topic       # (3)
        sink-in-0:                               # (4)
          destination: example/basic/topic       # (5)
          group: basic-group                     # (6)
```

1. **`definition: sink`** — Registers the Spring Cloud Function consumer bean. The publisher is not defined here because it uses `StreamBridge`.
2. **`source-out-0`** — The explicit output binding configured for the scheduled `StreamBridge` publisher.
3. **`destination: example/basic/topic`** — The Solace topic the producer publishes to.
4. **`sink-in-0`** — The input binding for the `sink()` Consumer bean.
5. **`destination: example/basic/topic`** — The Solace topic subscription that will be added to the consumer's queue.
6. **`group: basic-group`** — Setting a consumer group tells the binder to create a **durable queue** named `scst/wk/basic-group/plain/example/basic/topic`. Without a group, the binder would create a temporary (anonymous) queue that is deleted when the consumer disconnects.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class BasicApp {

    private final AtomicInteger count = new AtomicInteger(1);
    private final StreamBridge streamBridge;

    public BasicApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Scheduled(fixedRate = 2000)
    public void publish() {
        String msg = "Hello from Solace #" + count.getAndIncrement();
      streamBridge.send("source-out-0", MessageBuilder.withPayload(msg)
          .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
          .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
          .build());
        log.info("Published: {}", msg);
    }
}
```

  A scheduled task executes every 2 seconds, independently publishing messages using the injected `StreamBridge`. Each outbound message gets a 30 second TTL and `solace_dmqEligible=true`, so expired durable messages remain eligible for a DMQ.

```java
@Bean
public Consumer<String> sink() {
    return msg -> {
        log.info("Received: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

The `Consumer` bean receives messages from the durable queue. The binder automatically ACKs each message after the consumer returns successfully. The `RECEIVED` queue is used by the integration test to assert that messages arrived.

## What to Observe

When running, you should see alternating log lines showing the publish/consume cycle:

```
INFO  Published: Hello from Solace #1
INFO  Received: Hello from Solace #1
INFO  Published: Hello from Solace #2
INFO  Received: Hello from Solace #2
```

**What happens under the hood:**

1. The binder provisions a durable queue `scst/wk/basic-group/plain/example/basic/topic` on the broker.
2. It subscribes this queue to the topic `example/basic/topic`.
3. The scheduled publisher uses `StreamBridge` to publish a persistent message to `example/basic/topic` every 2 seconds.
4. The broker routes the message from the topic into the queue.
5. The `sink()` Consumer receives the message from the queue and the binder ACKs it.

**Durability:** If you stop the application, messages continue to accumulate on the durable queue. When the application restarts, it receives all buffered messages.

## When to Use This Pattern

- Simple event-driven microservices that produce or consume messages
- Getting started with Solace and Spring Cloud Stream
- Any scenario where you need guaranteed delivery with durable queues

## Related API Documentation

- [Creating a Simple Solace Binding](../../API.md#creating-a-simple-solace-binding) — Full configuration reference
- [Overview](../../API.md#overview) — How topic-to-queue mapping works in the Solace binder
- [Generated Queue Name Syntax](../../API.md#generated-queue-name-syntax) — How queue names like `scst/wk/basic-group/plain/...` are generated
- [Solace Session Properties](../../API.md#solace-session-properties) — Configuring the broker connection
