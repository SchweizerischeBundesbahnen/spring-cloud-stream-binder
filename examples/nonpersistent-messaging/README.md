# Nonpersistent Messaging (Direct QoS)

## Overview
By default, the Solace Binder publishes guaranteed (persistent) messages. This example displays how to bypass persistence mechanisms natively onto the ultra-low latency Direct Transport natively across Solace.

## Key Properties (`application.yml`)
- `spring.cloud.stream.solace.bindings.<xyz>.producer.deliveryMode: DIRECT`: Informs the Solace JCSMP producer to bypass Spooling completely natively. It will instantly dispatch over the memory-plane.
- `spring.application.name`: The framework relies strictly on the `spring.cloud.stream.bindings...group` natively. If there is no `group`, and deliveryMode is Direct, it simply listens ephemerally natively!

## Running Locally 
In the `examples/nonpersistent-messaging` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
Because it's `DIRECT`, you will see messages fly at ultra-low latency without hitting the disk at all. Any listeners that are disconnected instantly lose payloads indefinitely out of design.
