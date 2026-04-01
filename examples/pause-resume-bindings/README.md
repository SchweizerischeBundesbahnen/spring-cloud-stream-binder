# Pause / Resume Bindings

## Overview
Leverage Spring Cloud Stream actuator hooks to safely suspend queue traffic natively on the fly without breaking backend client queue bindings.

## Key Properties (`application.yml`)
- Exposes Actuator endpoint `/actuator/bindings` natively to manually orchestrate paused bindings.
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,bindings
  ```

## Running Locally
In the `examples/pause-resume-bindings` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
POSTing `{"state":"PAUSED"}` dynamically halts internal message dispatch loops. Payloads accumulate natively onto the physical internal Solace queue. Resuming via `{"state":"RESUMED"}` flashes all pending queued messages out natively instantly.
