# Health Indicator

## Overview
Binds the JCSMP Session health directly to the `/actuator/health` endpoint natively. This allows external orchestrators (like Kubernetes probes) to monitor Solace transport disconnects natively.

## Key Properties (`application.yml`)
- Expose health via Actuator web endpoints:
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health
    endpoint:
      health:
        show-details: always
  ```

## Running Locally
In the `examples/health-indicator` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
Accessing `/actuator/health` will return the `solace` binder health check with status `UP` (or `DOWN` during forced disconnects), detailing session connection state and underlying binding handlers.
