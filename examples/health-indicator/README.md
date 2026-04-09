# Health Indicator

Demonstrates how to expose the Solace binder's connection health through Spring Boot Actuator's `/actuator/health` endpoint. An external orchestrator (e.g., Kubernetes liveness/readiness probes) can use this endpoint to detect session disconnects or provisioning failures.

## Features Demonstrated

- Enabling the Solace binder health indicator via Actuator
- The three health statuses: `UP`, `RECONNECTING`, `DOWN`
- Exposing detailed health information with `show-details: always`
- How health reflects session state, binding status, and provisioning failures

## Prerequisites

- Java 17+
- Docker (for a local Solace broker, or an existing broker)

## How to Run

**Option A — Automated test:**

```bash
mvn verify
```

**Option B — Interactive with a local broker:**

If you do not already have a local broker running, start one first using the command in [the examples index](../README.md).

```bash
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"

# Then check health:
curl http://localhost:8080/actuator/health | jq .
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: healthConsumer
    stream:
      bindings:
        healthConsumer-in-0:
          destination: example/health/topic
          group: health-group
management:
  health:
    binders:
      enabled: true                               # (1)
  endpoint:
    health:
      show-details: always                        # (2)
  endpoints:
    web:
      exposure:
        include: health                           # (3)
```

1. **`management.health.binders.enabled: true`** — Enables the Spring Cloud Stream binder health contributor. When enabled, the Solace binder registers health indicators for the JCSMP session and each consumer binding.
2. **`show-details: always`** — Displays the full health tree including session status, binding names, and flow states. In production, you may use `show-details: when-authorized` for security.
3. **`exposure.include: health`** — Exposes the `/actuator/health` endpoint over HTTP.

## Code Walkthrough

```java
@Bean
public Consumer<String> healthConsumer() {
    return msg -> {};
}
```

A minimal consumer that establishes a binding to the Solace broker. The health indicator monitors the underlying JCSMP session and this binding's flow, regardless of what the consumer does with the messages.

## What to Observe

The included automated test validates the healthy `UP` path. To observe `RECONNECTING` or `DOWN`, interrupt the broker connection while the app is running and query `/actuator/health` again.

**Healthy state** — `GET /actuator/health`:

```json
{
  "status": "UP",
  "components": {
    "binders": {
      "status": "UP",
      "components": {
        "solace": {
          "status": "UP",
          "details": {
            "solaceBinderHealthAccessor": {
              "status": "UP"
            }
          }
        }
      }
    }
  }
}
```

**During reconnection** — If the broker connection drops and the binder is reconnecting:

```json
{
  "status": "UP",
  "components": {
    "binders": {
      "status": "RECONNECTING"
    }
  }
}
```

**Down state** — If all reconnection attempts are exhausted or the session is destroyed:

```json
{
  "status": "DOWN"
}
```

## Health Status Reference

| Status | Meaning | Typical Cause |
|---|---|---|
| **UP** | Binder is connected and functioning normally | Normal operation |
| **RECONNECTING** | Binder is actively trying to reconnect | Temporary network issue, broker restart |
| **DOWN** | Binder has suffered an unrecoverable failure | All reconnect attempts exhausted, session destroyed, provisioning failure |

## When to Use This Pattern

- Kubernetes liveness/readiness probes to detect broker disconnections
- Load balancer health checks to remove unhealthy instances from rotation
- Monitoring dashboards to track binder connection state
- Alerting on binder connection failures

## Related API Documentation

- [Solace Binder Health Indicator](../../API.md#solace-binder-health-indicator) — Full documentation of health statuses and configuration
- [Solace Connection Health-Check Properties](../../API.md#solace-connection-health-check-properties) — Events that trigger DOWN status
