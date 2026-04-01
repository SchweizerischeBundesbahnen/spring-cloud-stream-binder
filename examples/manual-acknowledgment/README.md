# Manual Acknowledgment

## Overview
Disable auto-acking and capture `AcknowledgmentCallback` headers to explicitly `accept()`, `reject()`, or `requeue()` messages conditionally. This yields precise control over when a payload safely leaves the queue.

## Key Properties (`application.yml`)
- `spring.cloud.stream.bindings.<binding>.consumer.max-attempts: 1`: Disable retries.
- Request explicit ACKing by explicitly unwrapping `AcknowledgmentCallback` from the Spring `Message<?>` header `IntegrationMessageHeaderAccessor.ACKNOWLEDGMENT_CALLBACK` instead of automatically returning normally.

## Running Locally
In the `examples/manual-acknowledgment` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
The consumer explicitly issues ACCEPT, REJECT, or REQUEUE calls based on business logic. Messages that are explicitly REJECTed skip any retry counts and disappear (or route directly to Dead Letter queues if provisioned), while ACKs explicitly finalize the removal natively.
