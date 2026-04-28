# Default Headers

Demonstrates how to use the producer's `defaultHeader` configuration to inject headers into every published message — and how an explicitly set header on a `Message` overrides the configured default.

## Features Demonstrated

- Configuring producer-side `defaultHeader` entries in `application.yml`
- Mixing custom headers (`custom-default-header`) with Solace headers (`solace_timeToLive`, `solace_senderId`)
- Resolving environment placeholders (e.g. `${HOSTNAME}`) inside default header values
- Per-message override: a header set on the outgoing `Message` wins over the configured default
- Reading both custom and Solace headers from the consumed Spring `Message<?>`

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
          headerPublisher-out-0:
            producer:
              default-header:                              # (1)
                custom-default-header: my-default-value    # (2)
                solace_timeToLive: 23000                   # (3)
                solace_senderId: my-project_${HOSTNAME}    # (4)
```

1. **`default-header`** — Producer property: a `Map<String, Object>` of headers automatically attached to every message published through this binding. If the message already carries a header with the same name, the configured default is ignored.
2. **`custom-default-header`** — A user-defined header. Mapped to a Solace user property and delivered to the consumer like any normal header.
3. **`solace_timeToLive`** — A standard Solace header. Defaulting it here means every message gets a 23 s TTL without each producer having to set it explicitly.
4. **`solace_senderId`** — Demonstrates that default header values support Spring property placeholders, so values can be resolved from the environment (e.g. host name, profile, build info).

## Code Walkthrough

### Publisher — Default vs. Override

```java
@Scheduled(fixedRate = 500)
public void publish() {
    int index = count.getAndIncrement();
    Message<String> message;

    if (index % 2 == 0) {
        // No header set → the configured default `custom-default-header=my-default-value` is applied. (1)
        message = MessageBuilder.withPayload("custom-msg-" + index)
                .build();
    } else {
        // Header set explicitly → overrides the configured default. (2)
        message = MessageBuilder.withPayload("custom-msg-" + index)
                .setHeader("custom-default-header", "overridden-value")
                .build();
    }

    streamBridge.send("headerPublisher-out-0", message);
    log.info("Published message {}", index);
}
```

1. **Fallback to default** — Even-indexed messages do not set `custom-default-header`, so the binder fills it in from `default-header.custom-default-header`. The Solace headers `solace_timeToLive` and `solace_senderId` are also injected automatically.
2. **Override** — Odd-indexed messages set `custom-default-header` explicitly. The configured default is silently skipped for that header on that message; other defaults still apply.

### Consumer — Reading the Resulting Headers

```java
@Bean
public Consumer<Message<String>> headerConsumer() {
    return msg -> {
        String customDefaultHeader = msg.getHeaders().get("custom-default-header", String.class);
        Long timeToLive = msg.getHeaders().get(SolaceHeaders.TIME_TO_LIVE, Long.class);
        String senderId = msg.getHeaders().get(SolaceHeaders.SENDER_ID, String.class);

        log.info("Received {} | custom-default-header={} | timeToLive={} | senderId={}",
                msg.getPayload(), customDefaultHeader, timeToLive, senderId);
    };
}
```

The consumer side is intentionally plain — there is no special configuration. It simply reads the headers and shows that:

- `custom-default-header` is `my-default-value` for messages that did not set it, and `overridden-value` for those that did.
- `timeToLive` is `23000` for every message (defaulted, never overridden in this example).
- `senderId` is the resolved `my-project_<HOSTNAME>` value for every message.

The included integration test (`DefaultHeadersIT`) asserts exactly this behavior.

## What to Observe

```
INFO  Published message 0
INFO  Received custom-msg-0 | custom-default-header=my-default-value | timeToLive=23000 | senderId=my-project_<host>
INFO  Published message 1
INFO  Received custom-msg-1 | custom-default-header=overridden-value | timeToLive=23000 | senderId=my-project_<host>
```

Notice how `custom-default-header` alternates between the configured default and the per-message override, while the Solace headers stay constant — they are never overridden by the application code.

## When to Use This Pattern

- Tagging every message produced by a service with static metadata (service name, version, region) without touching every producer call site
- Setting a service-wide default `solace_timeToLive` so individual producers have a fallback time-to-live for general use-cases, reducing code duplication and providing a safety net if they forget to think about expiration
- Stamping every outgoing message with environment-derived identifiers (host name, pod name, build number) via `${...}` placeholders
- Keeping per-message overrides simple: just set the header on the `Message` and the default steps aside

## Related Documentation

- [Solace Producer Properties — `defaultHeader`](../../API.md#solace-producer-properties)
- [Solace Headers](../../API.md#solace-headers) — Full table of `solace_*` headers, including `solace_timeToLive` and `solace_senderId`
- [`solace-headers` example](../solace-headers/README.md) — Reading and writing Solace-specific headers, plus consumer-side `headerExclusions`
