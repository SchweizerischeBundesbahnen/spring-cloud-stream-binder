# Processor Pipeline

## Overview
Demonstrates using simple Spring `Function<In, Out>` components to seamlessly read, transform natively, and republish messages downstream. Output bindings seamlessly loop towards your Solace natively.

## Key Properties (`application.yml`)
- Combine multiple Spring Cloud Stream `Function` definitions:
  ```yaml
  spring.cloud.function.definition: uppercase
  ```
- Any return object inherently triggers a natively configured automatic publish to your outgoing destination!

## Running Locally
In the `examples/processor-pipeline` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
You will witness incoming messages seamlessly transforming logically within your `Function` bean explicitly logging and flowing right back out towards the corresponding chained topic automatically!
