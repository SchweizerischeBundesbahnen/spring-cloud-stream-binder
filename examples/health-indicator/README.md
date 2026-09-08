# Health Indicator

Demonstrates how to expose the Solace binder's connection health through Spring Boot Actuator's `/actuator/health` endpoint. An external orchestrator (e.g., Kubernetes liveness/readiness probes) can use this endpoint to detect session disconnects or provisioning failures.

## Features Demonstrated

- Enabling the Solace binder health indicator via Actuator
- The health statuses: `UNKNOWN` before a session is connected, then `UP` and `DOWN`
- Reading the `connection`, `bindings` and `provisioning` sub-indicators under `binders.solace`
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

The included automated test validates the healthy `UP` path. To see `DOWN`, stop the broker while the application keeps running and query `/actuator/health` again: a reconnecting session reports `DOWN` immediately rather than through a separate transitional status. A broker that is unreachable *before* the first connect does not produce a `DOWN` response at all — the binder context fails to build and the application does not finish starting.

**Healthy state** — `GET /actuator/health`, binder subtree only:

```json
{
  "status": "UP",
  "components": {
    "binders": {
      "status": "UP",
      "components": {
        "solace": {
          "status": "UP",
          "components": {
            "connection": { "status": "UP" },
            "bindings": {
              "status": "UP",
              "components": {
                "healthConsumer-in-0": { "status": "UP" }
              }
            },
            "provisioning": { "status": "UP" }
          }
        }
      }
    }
  }
}
```

`connection` follows the JCSMP session, `bindings` carries one entry per consumer binding, and `provisioning` turns `DOWN` when endpoint provisioning fails. The Solace health appears exactly once, under `binders.solace`.

**Broker gone** — after the broker stops, the same subtree reports:

```json
{
  "status": "DOWN",
  "components": {
    "binders": {
      "status": "DOWN",
      "components": {
        "solace": {
          "status": "DOWN",
          "components": {
            "connection": {
              "status": "DOWN",
              "details": {
                "error": "com.solacesystems.jcsmp.JCSMPTransportException: Channel is closed by peer"
              }
            },
            "bindings": {
              "status": "DOWN",
              "components": {
                "healthConsumer-in-0": {
                  "status": "DOWN",
                  "details": {
                    "error": "com.solacesystems.jcsmp.JCSMPTransportException: Channel is closed by peer",
                    "info": "Channel is closed by peer"
                  }
                }
              }
            },
            "provisioning": { "status": "UP" }
          }
        }
      }
    }
  }
}
```

The JCSMP event that caused the transition is attached as `error`, `info` and — when the broker sent one — `responseCode`. A session that is reconnecting and one whose reconnect attempts are exhausted both report `DOWN`; only those details differ.

## Health Status Reference

| Status | Meaning | Typical Cause |
|---|---|---|
| **UNKNOWN** | No session has been connected yet, detail `info: no session connected yet` | Application start, before the binder opens its session |
| **UP** | Binder is connected and functioning normally | Normal operation |
| **DOWN** | Binder has no usable connection | Session is reconnecting, all reconnect attempts exhausted, session destroyed, provisioning failure |

> [!IMPORTANT]
> Reconnecting is reported as `DOWN`, not as a distinct transitional status. A brief broker restart therefore flips `/actuator/health` to `DOWN` even though the binder recovers on its own. Take that into account when wiring this endpoint to a Kubernetes **liveness** probe — a readiness probe (or a liveness probe with a generous `failureThreshold`) avoids restarting a pod that is merely waiting to reconnect.

## When to Use This Pattern

- Kubernetes liveness/readiness probes to detect broker disconnections
- Load balancer health checks to remove unhealthy instances from rotation
- Monitoring dashboards to track binder connection state
- Alerting on binder connection failures

## Related API Documentation

- [Solace Binder Health Indicator](../../API.md#solace-binder-health-indicator) — Full documentation of health statuses and configuration
- [Solace Connection Health-Check Properties](../../API.md#solace-connection-health-check-properties) — Events that trigger DOWN status
