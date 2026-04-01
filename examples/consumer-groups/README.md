# Consumer Groups (Durable Queues)

## Overview
Consumer `groups` establish strict physical persistence inside your Solace Broker! If you specify a group, the binder automatically requests that Solace allocate a strictly guaranteed Durable Queue mapped exactly to that name natively. If the consumers go offline, the physical queue buffers the payloads natively indefinitely.

## Key Properties (`application.yml`)
- `spring.cloud.stream.bindings.queuedConsumer-in-0.group: example-group`: This forces Solace to allocate a persistent queue natively named `scst/wk/example-group/plain/example/topic`.
- `spring.cloud.stream.bindings.queuedConsumer-in-0.destination: example/topic`: It natively subscribes this specific wildcard-supporting Topic physically onto the Queue!

## Running Locally
In the `examples/consumer-groups` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
If you abruptly shut down your Spring Boot application locally, the underlying Solace Broker strictly caches the incoming persistent payloads onto Disk natively. Upon rebooting later, your listener will instantly receive the physical backlog sequentially automatically.
