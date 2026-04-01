# Error Handling (Redelivery)

## Overview
Demonstrates how to effectively configure and interact with Spring Retry to absorb transient failures. It shows Spring's internal Retry Template configuration along with message redelivery via NACKing.

## Key Properties (`application.yml`)
- `spring.cloud.stream.bindings.<binding>.consumer.max-attempts: 3`: Ensures the consumer attempts to process the message multiple times before giving up.
- By throwing an exception natively, you reject the message back to the broker, forcing a broker redelivery.

## Running Locally
In the `examples/error-handling-redelivery` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
Intermittent failures trigger local retries and/or broker-level redeliveries, until the message successfully processes and logs correctly.
