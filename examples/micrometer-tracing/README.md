# Micrometer Tracing

Demonstrates distributed tracing across Solace producer and consumer using Micrometer Tracing with OpenTelemetry. Trace and span IDs are automatically propagated through Solace message headers, enabling end-to-end request tracing across message-driven microservices.

## Features Demonstrated

- Automatic trace context propagation from producer to consumer via Solace message headers
- Verifying that the consumer's trace ID matches the producer's trace ID
- Configuring Micrometer Tracing with 100% sampling
- Accessing `Tracer` to inspect current span context

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
      definition: tracingConsumer
    stream:
      bindings:
        tracingPublisher-out-0:
          destination: example/tracing/topic
        tracingConsumer-in-0:
          destination: example/tracing/topic
          group: tracing-group
management:
  tracing:
    enabled: true                                  # (1)
    sampling:
      probability: 1.0                             # (2)
```

1. **`management.tracing.enabled: true`** — Enables Micrometer Tracing integration. The Solace binder detects the presence of a `Tracer` bean and automatically instruments message production and consumption.
2. **`sampling.probability: 1.0`** — Traces 100% of messages. In production, you would typically reduce this (e.g., `0.1` for 10% sampling).

### Additional Dependencies Required

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-micrometer-tracing-opentelemetry</artifactId>
</dependency>
```

This brings in the OpenTelemetry bridge for Micrometer Tracing, which provides the `Tracer` and `Propagator` beans that the Solace binder uses.

## Code Walkthrough

```java
@SpringBootApplication
public class MicrometerTracingApp {
    private final Tracer tracer;                                       // (1)

    public MicrometerTracingApp(Tracer tracer) {
        this.tracer = tracer;
    }

    @Scheduled(fixedRate = 500)
    public void publish() {
        if (count.get() < 3) {
            int c = count.incrementAndGet();
            
            // Start a new span manually to observe trace propagation
            io.micrometer.tracing.Span newSpan = tracer.nextSpan().name("send-msg").start();
            try (io.micrometer.tracing.Tracer.SpanInScope ws = tracer.withSpan(newSpan)) {
                String currentTraceId = newSpan.context().traceId();
                String currentSpanId = newSpan.context().spanId();
                log.info("Publishing msg {}. TraceID: {}, SpanID: {}", c, currentTraceId, currentSpanId);
                TRACING_LOGS.offer("PUBLISHER:" + currentTraceId);
                
                streamBridge.send("tracingPublisher-out-0", MessageBuilder.withPayload("msg-" + c).build());
            } finally {
                newSpan.end();
            }
        }
    }

    @Bean
    public Consumer<Message<String>> tracingConsumer() {
        return msg -> {
            String traceId = tracer.currentSpan() != null
                ? tracer.currentSpan().context().traceId() : "none";   // (3)
            log.info("Consumed {}. TraceID: {}", msg.getPayload(), traceId);
            TRACING_LOGS.offer("CONSUMER:" + traceId);
        };
    }
}
```

1. **`Tracer` injection** — Spring Boot auto-configures a `Tracer` bean from the Micrometer Tracing / OpenTelemetry dependencies.
2. **Producer trace ID** — When the producer sends a message, a trace context is active. The binder automatically injects the trace ID and span ID into the Solace message headers.
3. **Consumer trace ID** — When the consumer receives the message, the binder extracts the trace context from the Solace message headers and creates a child span. The `traceId` on the consumer matches the producer's `traceId`, proving end-to-end propagation.

## What to Observe

```
INFO  Publishing msg 1. TraceID: 64a8f3b2c1d4e5f6, SpanID: a1b2c3d4e5f6g7h8
INFO  Consumed msg-1. TraceID: 64a8f3b2c1d4e5f6, SpanID: f8e7d6c5b4a39281
```

The **trace ID is the same** on both producer and consumer, while the **span IDs are different** (the consumer has a child span). This confirms that the trace context was propagated through the Solace message.

**How it works internally:**

1. The producer's binder creates a send span and injects context into the Solace message's user properties using the W3C Trace Context propagation format.
2. The message travels through the Solace broker (which passes through user properties transparently).
3. The consumer's binder extracts the context from user properties and creates a receive span as a child of the producer's span.
4. Both spans share the same trace ID, enabling tools like Jaeger, Zipkin, or Grafana Tempo to render them as a single distributed trace.

## When to Use This Pattern

- Distributed systems where you need to trace a request across multiple message-driven microservices
- Debugging message flows to identify latency bottlenecks
- Production observability with Jaeger, Zipkin, Grafana Tempo, or similar APM tools

## Related API Documentation

- [Micrometer Tracing](../../API.md#micrometer-tracing) — How to enable tracing and required beans
