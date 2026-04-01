# Dynamic Destinations

## Overview
Demonstrates programmatic publishing directly to calculated string destinations via Spring `StreamBridge`. This allows you to route messages dynamically to topics or queues at runtime without predefined static output bindings.

## Key Properties (`application.yml`)
- You can route dynamically by passing the destination directly to `streamBridge.send(destination, message)`.
- You can use the `scst_targetDestination` header to route messages over a single generic output binding.
- You can specify destination types (topic or queue) using `solace_scst_targetDestinationType`.

## Running Locally
In the `examples/dynamic-destinations` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
You will observe messages routed dynamically to different output destinations and correctly intercepted by wildcard or generic consumers mapped to those dynamic topics.
