# Metrics Monitoring

## Overview
Exposes intrinsic Spring Binder and Solace native statistics via Micrometer to monitor payload traffic flow natively.

## Key Properties (`application.yml`)
- Enables Micrometer `DistributionSummary` metrics.
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,metrics,prometheus
    metrics:
      distribution:
        percentiles-histogram:
          solace.message.queue.backpressure: true
          solace.message.processing.time: true
  ```

## Running Locally
In the `examples/metrics-monitoring` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
You will hit `/actuator/metrics` endpoints like `/actuator/metrics/solace.message.processing.time` and `/actuator/metrics/solace.message.size.payload` observing live histograms and timers of messages flowing through the bindings.
