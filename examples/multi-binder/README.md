# Multi-Binder

## Overview
Shows how to instruct a single application to connect simultaneously to two independent Solace Event Brokers natively via independent Spring Binder configurations.

## Key Properties (`application.yml`)
- Define distinct binder instantiations linking out to different URLs and credentials concurrently.
  ```yaml
  spring:
    cloud:
      stream:
        binders:
          solace-broker-1:
            type: solace
            environment:
              solace.java.host: tcp://localhost:55555
          solace-broker-2:
            type: solace
            environment:
              solace.java.host: tcp://localhost:55556
  ```

## Running Locally
In the `examples/multi-binder` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
You will observe messages successfully publishing out towards Broker A, while simultaneously distinct payloads flow through Broker B concurrently without intersecting.
