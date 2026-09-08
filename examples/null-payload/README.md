# Null Payload

Demonstrates how a consumer tells a message that carries no payload apart from one that carries an
empty payload. Spring messages cannot hold `null`, so the binder substitutes a null-equivalent
payload and flags the message with the `solace_scst_nullPayload` header.

## Features Demonstrated

- Reading the `solace_scst_nullPayload` header on the consumer side
- The null-equivalent payload the binder substitutes (empty `String` for a `TextMessage`)
- Publishing a payload-less message through the `JCSMPSession` bean the binder provides

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
      definition: nullPayloadConsumer
    stream:
      bindings:
        nullPayloadPublisher-out-0:
          destination: example/nullpayload/topic
        nullPayloadConsumer-in-0:
          destination: example/nullpayload/topic
          group: null-payload-group
```

Nothing has to be configured for this: the binder sets `solace_scst_nullPayload` on every message
that arrives without a payload.

## Code Walkthrough

A producer binding always writes the payload it was given, so it cannot send the message this
example is about. The sample publishes that one straight through the `JCSMPSession` bean instead,
imitating the outside publisher — a REST gateway, a JMS client, an MQTT bridge — that leaves the
payload off:

```java
private void publishWithoutAPayload() throws JCSMPException {
    XMLMessageProducer producer = jcsmpSession.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
        @Override
        public void responseReceivedEx(Object key) {
            // The binder's own producer bindings handle confirmations; see the publisher-confirms example.
        }

        @Override
        public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            log.error("Failed to publish the payload-less message", cause);
        }
    });
    try {
        producer.send(JCSMPFactory.onlyInstance().createMessage(TextMessage.class),
                JCSMPFactory.onlyInstance().createTopic(TOPIC));
        log.info("Published a message with no payload at all");
    } finally {
        producer.close();
    }
}
```

```java
public Consumer<Message<String>> nullPayloadConsumer() {
    return msg -> {
        boolean nullPayload = Boolean.TRUE.equals(msg.getHeaders().get(SolaceBinderHeaders.NULL_PAYLOAD, Boolean.class));
        String payload = msg.getPayload();
        log.info("Received '{}' | solace_scst_nullPayload={}", payload, nullPayload);
        RECEIVED_PAYLOADS.offer(new ReceivedPayload(payload, nullPayload));
    };
}
```

The consumer reads the flag rather than the payload, because the payload alone cannot answer the
question: the binder replaces a missing payload with the null equivalent of the message type — an
empty `String` for a `TextMessage` and an `XMLContentMessage`, an empty `byte[]` for a
`BytesMessage`, an empty `SDTMap` or `SDTStream` for the two SDT types.

## What to Observe

```
INFO  Published order-42 through the producer binding
INFO  Published a message with no payload at all
INFO  Received 'order-42' | solace_scst_nullPayload=false
INFO  Received '' | solace_scst_nullPayload=true
```

Both messages reach the consumer as a `String`. Only the header separates them.

> [!IMPORTANT]
> This works for `TextMessage`, `MapMessage` and `StreamMessage`. A binary or XML-content publisher
> cannot be told apart this way, because Solace turns an empty payload into a null payload for those
> two message types before the consumer ever sees it.

## When to Use This Pattern

- Consuming from a topic that a REST gateway, a JMS client or an MQTT bridge also publishes to
- Treating "no data" as a domain event — a tombstone or a delete marker — rather than as a defect
- Rejecting payload-less messages explicitly instead of letting an empty payload fall through
  parsing as valid input

## Related API Documentation

- [Empty Payload VS Null Payload](../../API.md#empty-payload-vs-null-payload) — What the binder substitutes, and for which message types the distinction survives
- [Solace Binder Headers](../../API.md#solace-binder-headers) — Full table of all `solace_scst_*` headers
