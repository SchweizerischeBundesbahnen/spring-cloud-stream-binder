# Partitioned Queues

Demonstrates how to set Solace partition keys on outbound messages using `SolaceBinderHeaders.PARTITION_KEY`, and how to provision the example queue as a partitioned queue over SEMP before starting the consumer binding. Partitioned queues let the broker keep messages for the same partition key on the same consumer flow, which is useful for partition affinity and distributing different keys across flows. They do **not** change the binder's rule that `concurrency > 1` provides no ordering guarantee.

## Features Demonstrated

- Setting `SolaceBinderHeaders.PARTITION_KEY` on outbound messages
- Provisioning the example queue with `partitionCount: 2` via SEMP before starting the binding
- How the binder translates partition keys to Solace's `JMSXGroupID` message property
- Combining partition keys with `concurrency: 2` for parallel processing inside one process

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
  -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default --app.semp.host=http://localhost:8081 --app.semp.username=admin --app.semp.password=admin"
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: partitionedConsumer
    stream:
      default:
        consumer:
          autoStartup: false                   # (1)
      bindings:
        partitionedPublisher-out-0:
          destination: example/partitioned/topic
        partitionedConsumer-in-0:
          destination: example/partitioned/topic
          group: partitioned-group
          consumer:
            concurrency: 2                     # (2)
      solace:
        bindings:
          partitionedConsumer-in-0:
            consumer:
              provisionDurableQueue: false    # (3)
app:
  partition-count: 2                          # (4)
  semp:
    host: http://localhost:8081               # (5)
    username: admin
    password: admin
```

1. **`consumer.autoStartup: false`** — The consumer binding is held back until the application has provisioned the example queue via SEMP.
2. **`concurrency: 2`** — Two worker threads consume from the queue in parallel inside the process.
3. **`provisionDurableQueue: false`** — The example provisions the queue itself via SEMP so it can set `partitionCount` before the binding starts.
4. **`partition-count: 2`** — The example queue is created as a partitioned queue with two partitions.
5. **`app.semp.*`** — Broker management connection settings used to create or recreate the example queue before the consumer starts.

## Code Walkthrough

```java
@Bean
ApplicationRunner partitionedQueueProvisioner() {
    return args -> {
        ensurePartitionedQueue();
        bindingsLifecycleController.start(BINDING_NAME);
        publisherEnabled.set(true);
    };
}
```

- `ensurePartitionedQueue()` uses SEMP to create the example queue with `partitionCount=2`, ingress/egress enabled, and `modify-topic` permission so the binder can add the destination subscription. If a previous queue exists with different settings, the example recreates it.
- `BindingsLifecycleController.start(...)` starts the consumer binding only after the partitioned queue exists.
- `publisherEnabled` prevents the scheduled publisher from sending messages before provisioning and binding startup are complete.

```java
@Scheduled(initialDelay = 1000, fixedRate = 500)
public void publish() {
    int currentCount = count.getAndIncrement();
    if (currentCount < 10) {
        String key = KEYS[currentCount % 2];
        String payload = "msg-" + currentCount;
        Message<String> msg = MessageBuilder.withPayload(payload)
        .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
        .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
                .setHeader(SolaceBinderHeaders.PARTITION_KEY, key)
                .build();
        streamBridge.send("partitionedPublisher-out-0", msg);
    }
}
```

`SolaceBinderHeaders.PARTITION_KEY` tells the binder to set the Solace partition key (`JMSXGroupID`) on the outbound message. The sample also applies a 30 second TTL and `solace_dmqEligible=true` so partitioned durable messages follow the same header guidance as the rest of the suite. Even-numbered messages get `"Key-A"`, odd-numbered get `"Key-B"`.

```java
@Bean
public Consumer<Message<String>> partitionedConsumer() {
    return msg -> {
        String payload = msg.getPayload();
        String thread = Thread.currentThread().getName();
        log.info("Received '{}' on thread '{}'", payload, thread);
        MSG_TO_THREAD.put(payload, thread);
    };
}
```

## How Partition Affinity Works in Production

For full partition-key-based routing, three conditions must be met:

1. **Partition key on message** — The publisher sets `SolaceBinderHeaders.PARTITION_KEY` (demonstrated in this example).
2. **Partitioned queue on broker** — The queue must be configured with `partitionCount > 0` via SEMP or broker management. This example does that programmatically before starting the consumer binding.
3. **Multiple consumer flows** — Multiple application instances (or multiple bindings to the same queue) each create their own JCSMP flow. The broker assigns partitions to flows so the same key stays on the same flow.

> **Note:** In this single-app example, the binder still creates one JCSMP flow regardless of `concurrency`. The `concurrency` setting controls internal worker threads that share the same flow. Flow affinity for a given partition key requires deploying multiple application instances, each binding to the same partitioned queue. It does **not** provide an ordering guarantee once `concurrency > 1` or application code processes messages asynchronously.

The included test validates both behaviors that matter for this example: partition-key-tagged messages are published and consumed successfully, and the queue is actually configured with `partitionCount=2` on the broker.

## When to Use This Pattern

- Workloads that benefit from keeping the same key on the same consumer flow across multiple application instances
- Multi-tenant systems where partition affinity helps distribute tenants across consumer flows
- Any scenario where you want **parallel processing across keys** while preserving broker-side key-to-flow affinity

> **⚠️ Ordering caveat:** The binder does **not** guarantee processing order when `concurrency > 1`, including when the source queue is partitioned. Partitioned queues help the broker keep the same key on the same flow, but strict ordering still requires a single-threaded consumer (`concurrency: 1`) and synchronous handler execution.

## Related API Documentation

- [Partitioning](../../API.md#partitioning) — Full documentation on partition keys, native PubSub+ support, and ordering caveats
- [Solace Binder Headers](../../API.md#solace-binder-headers) — `solace_scst_partitionKey` header reference
- [Consumer Concurrency](../../API.md#consumer-concurrency) — How concurrency interacts with partitioned queues
