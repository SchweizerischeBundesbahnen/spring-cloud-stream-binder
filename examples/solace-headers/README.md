# Solace Headers

Demonstrates how to read and write Solace-specific message properties (correlation ID, priority, TTL, DMQ eligibility, etc.) using `SolaceHeaders` constants, and how to exclude unwanted headers from consumed messages using `headerExclusions`.

## Features Demonstrated

- Setting Solace message properties via `SolaceHeaders.*` constants on outbound messages
- Sending time-sensitive messages with `solace_timeToLive` using `Duration`
- Keeping `solace_dmqEligible=true` so expired messages can still reach a DMQ
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
              headerExclusions: custom-org-id      # (1)
```

1. **`headerExclusions: custom-org-id`** — Any header named `custom-org-id` will be stripped from the consumed message before it reaches your `Consumer` bean. This is useful for filtering out application-internal headers that should not propagate to downstream consumers.

## Code Walkthrough

### Publisher — Setting Solace Headers

```java
@SpringBootApplication
@EnableScheduling
public class SolaceHeadersApp {
    private static final Logger log = LoggerFactory.getLogger(SolaceHeadersApp.class);
    public record ReceivedHeaders(String correlationId, String customOrgId) {}

    public static final BlockingQueue<ReceivedHeaders> RECEIVED_HEADERS = new LinkedBlockingQueue<>();
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
          .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis()) // (1)
          .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)                               // (2)
                // User-defined properties (Spring automatically maps these)
          .setHeader("custom-org-id", "ORG-" + index)                              // (3)
                .build();
                
        streamBridge.send("headerPublisher-out-0", message);
        log.info("Published with CorrelationID: corr-id-{}", index);
    }
}
```

1. **`TIME_TO_LIVE`** — Uses `Duration.ofSeconds(30).toMillis()` instead of a magic number so the intent is explicit.
2. **`DMQ_ELIGIBLE`** — Keeps the message eligible for a Dead Message Queue. Without this, an expired message cannot be moved to a DMQ.
3. **`custom-org-id`** — A user-defined property. Spring automatically maps these to Solace user properties. This header will be excluded by the consumer's `headerExclusions` config.

### Consumer — Reading Solace Headers

```java
@Bean
public Consumer<Message<String>> headerConsumer() {
    return msg -> {
        String correlationId = msg.getHeaders().get(SolaceHeaders.CORRELATION_ID, String.class);
        String customOrg = msg.getHeaders().get("custom-org-id", String.class);
    Long timeToLive = msg.getHeaders().get(SolaceHeaders.TIME_TO_LIVE, Long.class);
    Boolean dmqEligible = msg.getHeaders().get(SolaceHeaders.DMQ_ELIGIBLE, Boolean.class);
        
    log.info("Received {} | CorrelationID={} | TTL={} | dmqEligible={} | custom-org-id={} (should be null due to headerExclusions)",
      msg.getPayload(), correlationId, timeToLive, dmqEligible, customOrg);

    RECEIVED_HEADERS.offer(new ReceivedHeaders(correlationId, customOrg, timeToLive, dmqEligible));
    };
}
```

The consumer receives the message with Solace headers mapped to Spring `Message` headers. The `custom-org-id` header is **excluded** by the `headerExclusions` configuration, so `customOrg` will be `null`. The included test asserts all three pieces: the correlation ID survives, TTL arrives as `30000`, and `solace_dmqEligible` stays `true` while the excluded custom header does not propagate. You can access Solace headers individually:

```java
String correlationId = msg.getHeaders().get(SolaceHeaders.CORRELATION_ID, String.class);
Integer priority = msg.getHeaders().get(SolaceHeaders.PRIORITY, Integer.class);
Long timeToLive = msg.getHeaders().get(SolaceHeaders.TIME_TO_LIVE, Long.class);
Boolean dmqEligible = msg.getHeaders().get(SolaceHeaders.DMQ_ELIGIBLE, Boolean.class);
Boolean redelivered = msg.getHeaders().get(SolaceHeaders.REDELIVERED, Boolean.class);
```

## What to Observe

```
INFO  Received custom-msg-0 | CorrelationID=corr-id-0 | TTL=30000 | dmqEligible=true | custom-org-id=null (should be null due to headerExclusions)
INFO  Received custom-msg-1 | CorrelationID=corr-id-1 | TTL=30000 | dmqEligible=true | custom-org-id=null (should be null due to headerExclusions)
```

Notice that `custom-org-id` is **`null`** in the received headers — it was excluded by the `headerExclusions` configuration. The `CorrelationID` and other Solace headers are preserved.

## Header Categories

| Category | Prefix | Purpose | Example |
|---|---|---|---|
| **Solace Headers** | `solace_` | Get/set Solace message properties | `solace_correlationId`, `solace_priority` |
| **Solace Binder Headers** | `solace_scst_` | Binder directives and metadata | `solace_scst_partitionKey`, `solace_scst_confirmCorrelation` |
| **Read-only Headers** | `solace_` | Set by broker, read only by consumer | `solace_redelivered`, `solace_destination`, `solace_receiveTimestamp` |

## When to Use This Pattern

- Correlating request/reply message pairs with `CORRELATION_ID`
- Setting message priority for queue ordering
- Setting TTL for time-sensitive messages with `Duration`
- Ensuring expired messages can still be routed to a DMQ with `DMQ_ELIGIBLE`
- Filtering out internal headers that should not reach consumers

## Related API Documentation

- [Solace Headers](../../API.md#solace-headers) — Full table of all `solace_*` headers with types, access modes, and descriptions
- [Solace Binder Headers](../../API.md#solace-binder-headers) — Full table of all `solace_scst_*` headers
- [Solace Consumer Properties](../../API.md#solace-consumer-properties) — `headerExclusions` property
- [Solace Producer Properties](../../API.md#solace-producer-properties) — `headerExclusions` and `nonserializableHeaderConvertToString`
