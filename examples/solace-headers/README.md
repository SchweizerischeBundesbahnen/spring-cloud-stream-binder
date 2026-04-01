# Solace Headers

Demonstrates how to read and write Solace-specific message properties (correlation ID, priority, TTL, etc.) using `SolaceHeaders` constants, and how to exclude unwanted headers from consumed messages using `headerExclusions`.

## Features Demonstrated

- Setting Solace message properties via `SolaceHeaders.*` constants on outbound messages
- Reading Solace message properties from inbound Spring `Message<?>` headers
- Using `headerExclusions` to filter out specific headers on the consumer side
- The difference between `SolaceHeaders` (message properties) and `SolaceBinderHeaders` (binder directives)

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
      definition: headerConsumer
    stream:
      bindings:
        headerPublisher-out-0:
          destination: example/headers/topic
        headerConsumer-in-0:
          destination: example/headers/topic
          group: headers-group
      solace:
        bindings:
          headerConsumer-in-0:
            consumer:
              headerExclusions: custom-header      # (1)
```

1. **`headerExclusions: custom-header`** — Any header named `custom-header` will be stripped from the consumed message before it reaches your `Consumer` bean. This is useful for filtering out application-internal headers that should not propagate to downstream consumers.

## Code Walkthrough

### Publisher — Setting Solace Headers

```java
@SpringBootApplication
@EnableScheduling
public class SolaceHeadersApp {
    private static final Logger log = LoggerFactory.getLogger(SolaceHeadersApp.class);
    // Map of received message Correlation IDs for test assertions
    public static final BlockingQueue<String> CORRELATION_IDS = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;

    public SolaceHeadersApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(SolaceHeadersApp.class, args); }

    @Scheduled(fixedRate = 500)
    public void publish() {
        int index = count.getAndIncrement();
        Message<String> message = MessageBuilder.withPayload("custom-msg-" + index)
                // Standard Solace headers
                .setHeader(SolaceHeaders.CORRELATION_ID, "corr-id-" + index)
                .setHeader(SolaceHeaders.APPLICATION_MESSAGE_TYPE, "Example/Type")
                .setHeader(SolaceHeaders.PRIORITY, index % 255)
                // User-defined properties (Spring automatically maps these)
                .setHeader("custom-org-id", "ORG-" + index)
                .build();
                
        streamBridge.send("headerPublisher-out-0", message);
        log.info("Published with CorrelationID: corr-id-{}", index);
    }
}
```

1. **`CORRELATION_ID`** — A string identifier for correlating request/reply messages. Maps to the Solace message's `correlationId` property.
2. **`PRIORITY`** — Message priority (0–255). Higher values = higher priority in queue delivery.
3. **`TIME_TO_LIVE`** — Time in milliseconds before the message expires. After this duration, the broker discards the message or moves it to the DMQ.
4. **`APPLICATION_MESSAGE_TYPE`** — An application-defined type string (similar to JMS `JMSType`).
5. **`SENDER_TIMESTAMP`** — The publish timestamp set by the application (distinct from the broker's receive timestamp).
6. **`custom-header`** — A custom application header. This will be excluded by the consumer's `headerExclusions` config.

### Consumer — Reading Solace Headers

```java
@Bean
public Consumer<Message<String>> headerConsumer() {
    return msg -> {
        log.info("Received with headers: {}", msg.getHeaders());
        RECEIVED.offer(msg);
    };
}
```

The consumer receives the message with all Solace headers mapped to Spring `Message` headers. You can access them individually:

```java
String correlationId = msg.getHeaders().get(SolaceHeaders.CORRELATION_ID, String.class);
Integer priority = msg.getHeaders().get(SolaceHeaders.PRIORITY, Integer.class);
Boolean redelivered = msg.getHeaders().get(SolaceHeaders.REDELIVERED, Boolean.class);
```

## What to Observe

```
INFO  Received with headers: {
    solace_correlationId=corr-123,
    solace_priority=100,
    solace_timeToLive=60000,
    solace_applicationMessageType=demo/json,
    solace_senderTimestamp=1711929600000,
    solace_destination=example/headers/topic,
    solace_redelivered=false,
    ...
}
```

Notice that `custom-header` is **not** present in the received headers — it was excluded by the `headerExclusions` configuration.

## Header Categories

| Category | Prefix | Purpose | Example |
|---|---|---|---|
| **Solace Headers** | `solace_` | Get/set Solace message properties | `solace_correlationId`, `solace_priority` |
| **Solace Binder Headers** | `solace_scst_` | Binder directives and metadata | `solace_scst_partitionKey`, `solace_scst_confirmCorrelation` |
| **Read-only Headers** | `solace_` | Set by broker, read only by consumer | `solace_redelivered`, `solace_destination`, `solace_receiveTimestamp` |

## When to Use This Pattern

- Correlating request/reply message pairs with `CORRELATION_ID`
- Setting message priority for queue ordering
- Setting TTL for time-sensitive messages
- Filtering out internal headers that should not reach consumers

## Related API Documentation

- [Solace Headers](../../API.md#solace-headers) — Full table of all `solace_*` headers with types, access modes, and descriptions
- [Solace Binder Headers](../../API.md#solace-binder-headers) — Full table of all `solace_scst_*` headers
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `headerExclusions` property
- [Solace Producer Properties](../../API.md#solace-producer-properties) — `headerExclusions` and `nonserializableHeaderConvertToString`
