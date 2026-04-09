# Metrics Monitoring

Demonstrates how to expose Solace binder metrics via Micrometer and Spring Boot Actuator. The binder automatically registers distribution summaries for message sizes, processing times, queue depths, and backpressure, and this sample exposes both `/actuator/metrics` and `/actuator/prometheus`.

## Features Demonstrated

- Automatic registration of 7 Solace binder metrics via Micrometer
- Exposing metrics through `/actuator/metrics` and `/actuator/prometheus`
- Message payload size and total size distribution summaries
- Processing time, queue wait time, and backpressure monitoring

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

# Then query metrics:
curl http://localhost:8080/actuator/metrics/solace.message.size.payload
curl http://localhost:8080/actuator/metrics/solace.message.processing.time
curl http://localhost:8080/actuator/prometheus
```

## Configuration Explained

```yaml
spring:
  cloud:
    function:
      definition: metricsConsumer
    stream:
      bindings:
        metricsPublisher-out-0:
          destination: example/metrics/topic
        metricsConsumer-in-0:
          destination: example/metrics/topic
          group: metrics-group
management:
  metrics:
    export:
      prometheus:
        enabled: true                             # (1)
  endpoints:
    web:
      exposure:
        include: metrics,prometheus               # (2)
```

1. **`prometheus.enabled: true`** — Enables the Prometheus metrics endpoint for scraping.
2. **`exposure.include: metrics,prometheus`** — Exposes both `/actuator/metrics` and `/actuator/prometheus` over HTTP.

> **For production monitoring**, you would also configure percentile histograms and SLO boundaries:
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

## Code Walkthrough

```java
@SpringBootApplication
@EnableScheduling
public class MetricsMonitoringApp {
    private static final Logger log = LoggerFactory.getLogger(MetricsMonitoringApp.class);
    public static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final StreamBridge streamBridge;

    public MetricsMonitoringApp(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    public static void main(String[] args) { SpringApplication.run(MetricsMonitoringApp.class, args); }

    @Scheduled(fixedRate = 1000)
    public void publish() {
        if (count.get() < 5) {
            int c = count.incrementAndGet();
        String payload = "msg-" + c;
        streamBridge.send("metricsPublisher-out-0", MessageBuilder.withPayload(payload)
            .setHeader(SolaceHeaders.TIME_TO_LIVE, Duration.ofSeconds(30).toMillis())
            .setHeader(SolaceHeaders.DMQ_ELIGIBLE, true)
            .build());
            log.info("Published metric msg: {}", "msg-" + c);
        }
    }
}
```

  A simple publisher/consumer pair. The publisher uses the standard 30 second TTL and `solace_dmqEligible=true` headers, and the binder automatically instruments both bindings with metrics — no additional code is required.

## What to Observe

After startup, let the sample publish and consume a few messages before querying the actuator endpoints so the binder metrics have data to expose.

**Query `/actuator/metrics/solace.message.size.payload`:**

```json
{
  "name": "solace.message.size.payload",
  "baseUnit": "bytes",
  "measurements": [
    { "statistic": "COUNT", "value": 5.0 },
    { "statistic": "TOTAL", "value": 30.0 },
    { "statistic": "MAX", "value": 6.0 }
  ],
  "availableTags": [
    { "tag": "name", "values": ["metricsConsumer-in-0"] }
  ]
}
```

## Available Metrics

| Metric | Unit | Description |
|---|---|---|
| `solace.message.size.payload` | bytes | Payload size of consumed/published messages |
| `solace.message.size.total` | bytes | Total message size including headers |
| `solace.message.queue.size` | messages | Number of messages waiting in the binder's internal queue |
| `solace.message.active.size` | messages | Messages currently being processed by worker threads |
| `solace.message.queue.backpressure` | milliseconds | How long the oldest waiting message has been in the queue |
| `solace.message.queue.wait.time` | milliseconds | Time a message spent in the internal queue before processing |
| `solace.message.processing.time` | milliseconds | Total processing duration per message (including handler time) |

> **Note:** Time-based metrics are registered as `DistributionSummary` with milliseconds base unit, **not** as `Timer`. Micrometer does not auto-convert these to seconds.

## When to Use This Pattern

- Production monitoring with Prometheus + Grafana
- Detecting consumer backpressure before it causes message loss
- Tracking message throughput and processing latency
- Setting up alerts on queue depth or processing time SLOs

## Related API Documentation

- [Solace Binder Metrics](../../API.md#solace-binder-metrics) — Full table of all 7 metrics with tags and descriptions
- [Backpressure SLO Recommendations](../../API.md#backpressure-slo-recommendations) — Prometheus alert examples and histogram configuration
- [Watchdog](../../API.md#watchdog) — Deadlock detection for stuck message processing threads
