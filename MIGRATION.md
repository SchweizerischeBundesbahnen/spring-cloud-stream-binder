# Migration Guide

This document contains migration guides for major version upgrades of the Spring Cloud Stream Binder for Solace PubSub+.

---

## 8.0.0 to 9.0.0

> [!IMPORTANT]
> Version 9.0.0 introduces breaking changes related to watchdog and monitoring functionality. Queue-size-based warnings have been removed in favor of metrics-based backpressure monitoring.

### Breaking Changes

#### Configuration Properties Removed

The following configuration properties have been removed:

*   `spring.cloud.stream.solace.bindings.<binding>.consumer.extension.urgent-warning-multiplier`
*   `spring.cloud.stream.solace.bindings.<binding>.consumer.extension.time-between-warnings-s`
*   `spring.cloud.stream.solace.bindings.<binding>.consumer.extension.max-processing-time-ms`
*   `spring.cloud.stream.solace.bindings.<binding>.consumer.extension.selector` (JMS selector syntax is unsupported in native JCSMP consumer flows)

**Action Required:** Remove these properties from your configuration files. If you relied on `selector`, you must re-architect your conditional message filtering.

#### Watchdog Behavior Changed

*   **Old behavior (8.x):** Logged warnings when `messageQueueSize > threads` at regular intervals
*   **New behavior (9.0.0):** Logs warnings only for potential deadlocks (messages processing longer than `watchdogTimeoutMs`)
*   **Default timeout changed:** From 2 seconds to 5 minutes (300000ms)

**Action Required:**

1.  If you relied on queue-size warnings for backpressure detection, set up metrics-based monitoring (see below)
2.  If you need faster deadlock detection, explicitly configure `watchdogTimeoutMs`:
```yaml
spring:
  cloud:
    stream:
      solace:
        bindings:
          <binding-name>:
            consumer:
              extension:
                watchdogTimeoutMs: 60000  # 1 minute instead of 5 minutes
```


### Migrating to Metrics-Based Monitoring

#### Step 1: Expose Metrics Endpoint

Ensure your application exposes the metrics endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

#### Step 2: Configure Prometheus

Add Prometheus scrape configuration:

```yaml
scrape_configs:
  - job_name: 'spring-boot-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

#### Step 3: Configure Alerts

Set up Prometheus alerts for backpressure detection:

```yaml
groups:
  - name: solace_binder_alerts
    rules:
      - alert: SolaceBinderHighBackpressure
        expr: solace_message_queue_backpressure{quantile="0.99"} > 60000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High backpressure on {{ $labels.name }}"
          description: "Message processing is taking longer than expected (p99 > 60s)"

      - alert: SolaceBinderQueueGrowth
        expr: solace_message_queue_size > 100
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Queue growing on {{ $labels.name }}"
          description: "Internal queue has {{ $value }} messages waiting"
```

> [!IMPORTANT]
> The `quantile` labels in Prometheus alerts require explicit percentile histogram configuration in your `application.yaml`:
>
> ```yaml
> management:
>   metrics:
>     distribution:
>       percentiles-histogram:
>         solace.message.queue.backpressure: true
>         solace.message.processing.time: true
>       slo:
>         solace.message.queue.backpressure: 1000,5000,10000,30000,60000
> ```

### Key Metrics for Monitoring

After migration, monitor these metrics:

| Metric | Purpose | Recommended Alert |
| --- | --- | --- |
| `solace.message.queue.backpressure` | Detect slow message processing | p99 > expected processing time |
| `solace.message.queue.size` | Detect queue growth | > concurrency * 10 |
| `solace.message.active.size` | Monitor thread utilization | Consistently at max concurrency |
| `solace.message.processing.time` | Track processing performance | p99 > SLO threshold |

---

## 7.0.0 to 8.0.0

### Retry Behavior Change

**Action Required:**
Check your retry configurations. Retries now start counting at 0 instead of 1.
*   If you have `maxAttempts=0`, this now means **no retries** (previously it might have allowed one attempt).
*   Adjust `maxAttempts` if you relied on the old behavior.

### Spring Boot 4.0 Migration

**Action Required:**
This release upgrades to Spring Boot 4.0. Follow the [Spring Boot 4.0 Migration Guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).

### Spring Cloud 2025.1 Migration

**Action Required:**
This release upgrades to Spring Cloud 2025.1. Review the [Spring Cloud 2025.1 Release Notes](https://github.com/spring-cloud/spring-cloud-release/wiki/Spring-Cloud-2025.1-Release-Notes).

---

## 6.0.0 to 7.0.0

### Health Check Configuration

**Action Required:**
Remove the `SolaceSessionHealthProperties.reconnectAttemptsUntilDown` property from your configuration. The health indicator now immediately reports `DOWN` status when the connection is down or reconnecting.

---

## 5.0.0 to 6.0.0

### Spring Boot 3.5 Migration

**Action Required:**
This release upgrades to Spring Boot 3.5. Follow standard Spring Boot migration procedures.

---

## 4.0.0 to 5.0.0

### Batch Processing Removed

**Action Required:**
If you were using batch processing, transactions on batch processing, or pollable message sources, you must migrate to standard message processing. These features have been removed.

### TopicEndpoint Removed

**Action Required:**
Remove any usage of `TopicEndpoint`.

---

## 3.0.0 to 4.0.0

### Dependency Management

**Action Required:**
The project structure has been flattened. Remove any dependency management or starter dependencies specific to the old multi-module structure. Use the single `spring-cloud-stream-binder-solace` artifact.

### Batch Messaging Deprecation

**Action Required:**
Batch messaging processing is deprecated. Plan to migrate to standard message processing.
