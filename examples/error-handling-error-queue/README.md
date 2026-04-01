# Error Handling (Error Queue)

## Overview
Shows how `autoBindErrorQueue: true` instructs the Binder to automatically provision a Solace Dead Letter Queue and route permanent failures to it natively.

## Key Properties (`application.yml`)
- `spring.cloud.stream.solace.bindings.<binding>.consumer.autoBindErrorQueue: true`: Automatically creates an error queue and routes completely failed messages to it after retries are exhausted.
- Properties like `errorQueueMaxDeliveryAttempts` and `errorMsgTtl` control how errors are handled inside this dedicated queue.

## Running Locally
In the `examples/error-handling-error-queue` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
Messages that consistently fail processing across all retries will be removed from the main queue and placed into the automatically provisioned Solace Error Queue.
