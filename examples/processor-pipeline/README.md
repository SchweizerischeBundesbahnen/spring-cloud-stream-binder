# Processor Pipeline

Demonstrates using a Spring `Function<String, String>` bean to create a message transformation pipeline: a producer emits messages, a processor transforms them in-flight, and a consumer receives the result — all wired together with the pipe (`|`) operator.

## Features Demonstrated

- Using `Function<In, Out>` as an inline message processor
- Composing functions with the pipe operator (`source|uppercase|sink`)
- Content-type negotiation between pipeline stages
- Zero-code routing — the framework wires the pipeline automatically

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
      definition: uppercase|sink                 # (1)
    stream:
      bindings:
        publisher-out-0:
          destination: example/processor/topic   # (2)
        uppercaseSink-in-0:
          destination: example/processor/topic   # (3)
          group: processor-group                 # (4)
```

1. **`uppercase|sink`** — The pipe `|` operator composes the two beans into a single pipeline. The `uppercase` outputs directly into `sink`'s input internally without going across the broker.
2. **`publisher-out-0`** — The publisher thread uses `StreamBridge` to send messages to this output binding.
3. **`uppercaseSink-in-0`** — The implicit input binding for the pipeline (which receives from the topic).
4. **`group: processor-group`** — Creates a durable queue for the pipeline.

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class ProcessorApp {

    private final StreamBridge streamBridge;

    public ProcessorApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    @Scheduled(fixedRate = 2000)
    public void source() {
        streamBridge.send("publisher-out-0", "hello world");
        log.info("Sourced: hello world");
    }
}
```

The scheduled task emits the literal string `"hello world"` directly to the Solace broker using `StreamBridge`.

```java
@Bean
public Function<String, String> uppercase() {
    return String::toUpperCase;
}
```

The `Function` receives each message from `source` and transforms the payload to uppercase. This is a "processor" in Spring Cloud Stream terminology — it reads from an input binding and writes to an output binding.

```java
@Bean
public Consumer<String> sink() {
    return msg -> {
        log.info("Received transformed: {}", msg);
        RECEIVED.offer(msg);
    };
}
```

The `Consumer` receives the transformed message. By the time it arrives here, the payload has been converted to `"HELLO WORLD"`.

## What to Observe

```
INFO  Received transformed: HELLO WORLD
INFO  Received transformed: HELLO WORLD
```

The pipeline continuously produces `"hello world"`, transforms it to `"HELLO WORLD"`, and delivers it to the sink.

**What happens under the hood:**

1. Since the three functions are piped together, Spring Cloud Stream treats them as a single composite function.
2. The Solace binder creates a single outbound binding (for the source) and a single inbound binding (for the sink), with the uppercase transform applied in-memory between them.
3. The content-type negotiation ensures the `String` payload is properly serialized/deserialized between stages.

## When to Use This Pattern

- ETL-style message transformation (e.g., enrichment, format conversion, filtering)
- Keeping transformation logic separate from producer/consumer logic for reusability
- Building message processing chains without intermediate topics

## Related API Documentation

- [Creating a Simple Solace Binding](../../API.md#creating-a-simple-solace-binding) — The `Function<In, Out>` example in API.md
- [Native Payload Types](../../API.md#native-payload-types) — Supported payload types and content-type negotiation
