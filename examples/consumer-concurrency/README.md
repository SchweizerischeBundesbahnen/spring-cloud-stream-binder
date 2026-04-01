# Consumer Concurrency

## Overview
Configure `concurrency: X` to transparently spin up multiple Solace receiver threads sharing identical Queue bindings. This demonstrates how to enable concurrent message processing with multiple worker threads.

## Key Properties (`application.yml`)
- `spring.cloud.stream.bindings.concurrentConsumer-in-0.consumer.concurrency: 4`: This configures the Solace binder to run four underlying API receiver threads to consume concurrently.

## Running Locally
In the `examples/consumer-concurrency` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
You will observe multiple threads processing messages concurrently. The fast publisher will send messages rapidly, while the concurrent consumer processes them in parallel across multiple threads.
