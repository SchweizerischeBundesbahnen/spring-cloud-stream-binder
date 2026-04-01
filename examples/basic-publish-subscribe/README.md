# Basic Publish & Subscribe

## Overview
This simplest example demonstrates attaching a standard Spring Cloud Stream `Supplier` (Publisher) and `Consumer` (Subscriber) sequentially to a Solace PubSub+ backend broker. The application spins up and natively binds a 1-second continuous emitting channel to the message destination `example/topic`.

## Key Properties (`application.yml`)
- `spring.cloud.function.definition: publisher;consumer`: Instructs the framework to wire these two specific beans natively into transport channels.
- `spring.cloud.stream.bindings.<xyz>.destination: example/topic`: Specifies the explicit Solace Topic that both interfaces synchronize on natively. Without a specified `group`, it's strictly transient pub/sub natively!

## Running Locally
In the `examples/basic-publish-subscribe` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
You will immediately see the fast-polling `Supplier` generate records, successfully routing them over Solace, and your listener automatically receiving them.

```text
INFO --- [  main  ] ch.sbb.example.PubSubApp : Sent: HelloWorld-1
INFO --- [        ] ch.sbb.example.PubSubApp : Received: HelloWorld-1
INFO --- [  main  ] ch.sbb.example.PubSubApp : Sent: HelloWorld-2
INFO --- [        ] ch.sbb.example.PubSubApp : Received: HelloWorld-2
```
