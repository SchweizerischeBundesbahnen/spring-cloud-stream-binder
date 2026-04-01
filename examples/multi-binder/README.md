# Multi-Binder

Demonstrates how a single Spring Boot application can connect to **two independent Solace brokers** simultaneously using named binder instances. Each binding is explicitly assigned to a specific broker, allowing cross-broker message routing within one application.

## Features Demonstrated

- Defining multiple named Solace binder instances (`solace-broker-1`, `solace-broker-2`)
- Assigning bindings to specific binder instances with the `binder` property
- Independent session configuration per binder
- Publishing to one broker and consuming from another

## Prerequisites

- Java 17+
- Docker (for local Solace brokers, or existing brokers)

## How to Run

**Option A — Automated test:**

```bash
mvn verify
```

The integration test starts **two independent Solace containers** via Testcontainers, one per binder.

**Option B — Interactive with two local brokers:**

```bash
# Start two Solace brokers on different ports
docker run -d -p 8080:8080 -p 55555:55555 --shm-size=2g --name=solace1 solace/solace-pubsub-standard:latest
docker run -d -p 8081:8080 -p 55556:55555 --shm-size=2g --name=solace2 solace/solace-pubsub-standard:latest

mvn spring-boot:run
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: fromBroker1;toBroker1;toBroker2
    stream:
      binders:
        solace-broker-1:                           # (1)
          type: solace
        solace-broker-2:                           # (2)
          type: solace
      bindings:
        fromBroker1-out-0:
          destination: example/multibinder/topic
          binder: solace-broker-1                  # (3)
        toBroker1-in-0:
          destination: example/multibinder/topic
          group: group-1
          binder: solace-broker-1                  # (4)
        toBroker2-in-0:
          destination: example/multibinder/topic
          group: group-2
          binder: solace-broker-2                  # (5)
```

1. **`solace-broker-1`** — The first named binder instance. Session properties can be nested under it:
   ```yaml
   binders:
     solace-broker-1:
       type: solace
       environment:
         solace.java:
           host: tcp://broker1:55555
           msgVpn: default
   ```
2. **`solace-broker-2`** — The second named binder instance, connecting to a different broker.
3. **`binder: solace-broker-1`** — Assigns this output binding to the first broker. Messages from `fromBroker1` are published to broker 1.
4. **`binder: solace-broker-1`** — This consumer reads from broker 1's queue.
5. **`binder: solace-broker-2`** — This consumer reads from broker 2's queue. It is completely independent of broker 1.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class MultiBinderApp {
    private static final Logger log = LoggerFactory.getLogger(MultiBinderApp.class);
    public static final BlockingQueue<String> RECEIVED_1 = new LinkedBlockingQueue<>();
    public static final BlockingQueue<String> RECEIVED_2 = new LinkedBlockingQueue<>();
    
    private final AtomicInteger count = new AtomicInteger(1);
    private final StreamBridge streamBridge;

    public MultiBinderApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(MultiBinderApp.class, args); }

    @Scheduled(fixedRate = 1000)
    public void publishToBrokers() {
        int c = count.getAndIncrement();
        String msg1 = "msg-to-broker1-" + c;
        streamBridge.send("fromBroker1-out-0", msg1);
        log.info("Published to Broker 1: {}", msg1);
        
        String msg2 = "msg-to-broker2-" + c;
        streamBridge.send("fromBroker2-out-0", msg2);
        log.info("Published to Broker 2: {}", msg2);
    }
}
```

- `fromBroker1` publishes to broker 1.
- `toBroker1` consumes from broker 1 — it receives messages from `fromBroker1`.
- `toBroker2` consumes from broker 2 — it only receives messages published to broker 2 (in the test, messages are published to broker 2 via `StreamBridge`).

**Traffic is fully isolated between brokers.** Messages published to broker 1 never appear on broker 2, and vice versa.

## What to Observe

```
INFO  Received from Broker 1: msg-to-broker1-1
INFO  Received from Broker 1: msg-to-broker1-2
INFO  Received from Broker 2: msg-to-broker2-1    # Only when publishing to broker 2
```

Each consumer only receives messages from its own broker. The two JCSMP sessions are completely independent.

## When to Use This Pattern

- Bridging messages between two independent Solace event meshes
- Migrating from one broker to another with a transition period
- Multi-datacenter deployments where each datacenter has its own broker
- Consuming from a shared corporate broker while publishing to a team-specific broker

## Related API Documentation

- [Creating a Simple Solace Binding](../../API.md#creating-a-simple-solace-binding) — Multi-binder configuration example in the TIP box
- [Solace Session Properties](../../API.md#solace-session-properties) — How to configure session properties per binder instance
