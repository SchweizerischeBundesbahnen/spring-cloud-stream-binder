# Plan: Watchdog Deadlock Detection & Backpressure Monitoring

This plan simplifies the watchdog implementation to focus on **detecting deadlocked threads** (time-based) while keeping existing metrics for backpressure monitoring. The current `messageQueueSize > threads` log approach will be removed and replaced with a time-based warning for long-running threads (default 5 minutes). Metrics remain unchanged for SLO-based alerting, and a sample dashboard will be provided.

## Use Cases Analysis

### NETS:
- perMsgsSec = 40sec (time to process a message)
- threadCount = 5
- messageRate = 0.125 msgs/s
- messageQueueSize = 5 (observed)

### IltisAdapter:
- perMsgsSec = <10ms
- threadCount = 1
- messageRate = 480 msgs/s
- messageQueueSize = 20-150 (observed)

### Current Implementation (7.4.5):
`messageQueueSize > threads` - Works well for NETS but not for IltisAdapter

### Previous Implementation (5.0.7):
`(now() - messageInProgress.startMillis) > maxProcessingTimeMs` with `maxProcessingTimeMs = 1000` - Works well for IltisAdapter but not for NETS

### Conclusion:
A single implementation that works for both use cases via queue-size detection is complex and will likely have edge cases. Instead:
- **Deadlock detection**: Use time-based approach with higher timeout (5 min default)
- **Backpressure monitoring**: Rely on metrics and SLO-based alerting

---

## Implementation Steps

### 1. Modify watchdog timeout logic in FlowXMLMessageListener.java

**File:** `src/main/java/com/solace/spring/cloud/stream/binder/inbound/queue/FlowXMLMessageListener.java`

In the `watchdog()` method (lines 111–138):
- Add per-message time-based warning: if `(now - messageInProgress.startMillis) > watchdogTimeoutMs`, log a warning once per message
- Use the existing `MessageInProgress.warned` flag (line 200) to ensure single warning per message
- Remove or reduce reliance on `WatchdogLogger.warnIfNecessary()` for queue-size-based warnings
- Keep metrics recording unchanged (`recordQueueSize`, `recordActiveMessages`, `recordQueueBackpressure`)

**Example logic to add:**
```java
for (MessageInProgress messageInProgress : activeMessages) {
    long timeInProcessing = currentTimeMillis - messageInProgress.startMillis;
    maxTimeInProcessing = Math.max(maxTimeInProcessing, timeInProcessing);

    // Deadlock detection: warn once if processing exceeds timeout
    if (timeInProcessing > watchdogTimeoutMs && !messageInProgress.isWarned()) {
        log.warn("Message processing exceeded {} ms (potential deadlock): thread={}, messageId={}, destination={}",
                watchdogTimeoutMs,
                messageInProgress.getThreadName(),
                messageInProgress.getBytesXMLMessage().getMessageId(),
                messageInProgress.getBytesXMLMessage().getDestination().getName());
        messageInProgress.setWarned(true);
    }
}
```

---

### 2. Update configuration defaults in SolaceConsumerProperties.java

**File:** `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceConsumerProperties.java`

- Change `watchdogTimeoutMs` default from `2000` to `300000` (5 minutes) on line 123
- Update JavaDoc to clearly describe it as deadlock detection timeout:

```java
/**
 * Time in milliseconds before a long-running message processing thread is logged as a warning.
 * This is used to detect potential deadlocks or stuck threads.
 * A warning is logged once per message when processing time exceeds this threshold.
 * Default: 300000 (5 minutes)
 */
private long watchdogTimeoutMs = 300000;
```

- Mark or remove deprecated `maxProcessingTimeMs` property (line 108) - it's already marked `@Deprecated`

---

### 3. Simplify WatchdogLogger.java

**File:** `src/main/java/com/solace/spring/cloud/stream/binder/util/WatchdogLogger.java`

Options:
- **Option A (Recommended):** Deprecate `logUrgent()` and `logRelaxed()` methods, keep for backwards compatibility for one major release
- **Option B:** Remove entirely if breaking change is acceptable

If deprecating, update the class:
```java
/**
 * @deprecated Queue-size-based warnings are deprecated.
 * Use metrics-based monitoring for backpressure detection instead.
 * See solace.message.queue.backpressure metric.
 */
@Deprecated
public void warnIfNecessary(...) { ... }
```

---

### 4. Update Watchdog documentation in API.adoc

**File:** `API.adoc` (lines 1290–1314)

Rewrite the Watchdog section:

```asciidoc
== Watchdog

=== Purpose

The watchdog thread monitors message processing to detect **deadlocked or stuck threads**.
It does NOT detect backpressure - use metrics for that (see <<Backpressure Monitoring>>).

=== Deadlock Detection

When a message has been processing for longer than `watchdogTimeoutMs` (default: 5 minutes),
a warning is logged once per message:

[source,log]
----
WARN Message processing exceeded 300000 ms (potential deadlock): thread=binding-0, messageId=xxx, destination=topic/name
----

This indicates a thread may be stuck and requires investigation.

=== Configuration

watchdogTimeoutMs::
Time in milliseconds before a long-running message processing thread triggers a warning.
Used to detect potential deadlocks or stuck threads.
+
Default: `300000` (5 minutes)
+
NOTE: Set this higher than your expected maximum message processing time.

=== Backpressure Monitoring

For backpressure detection, use the `solace.message.queue.backpressure` metric instead of log warnings.
See <<Solace Binder Metrics>> and <<Example Dashboard>>.
```

---

### 5. Extend metrics documentation in API.adoc

**File:** `API.adoc` (lines 1241–1269)

Add missing metrics to the table and document SLO approach:

```asciidoc
| solace.message.queue.size
| `DistributionSummary`

Base Units: `messages`
|* `name: <bindingName>`
| Internal message queue size.

Number of messages waiting in the binder's internal queue to be processed by worker threads.

| solace.message.active.size
| `DistributionSummary`

Base Units: `messages`
|* `name: <bindingName>`
| Messages currently being processed.

Number of messages actively being processed by worker threads.

| solace.message.queue.backpressure
| `DistributionSummary`

Base Units: `milliseconds`
|* `name: <bindingName>`
| Age of the oldest message being processed.

The time in milliseconds that the oldest currently-processing message has been in processing.
Use this metric to detect backpressure: high values indicate slow processing or potential issues.

| solace.message.processing.time
| `DistributionSummary`

Base Units: `milliseconds`
|* `name: <bindingName>`
| Message processing duration.

How long each message took to process, measured from when the worker thread received the message until processing completed.
```

Add SLO guidance section:

```asciidoc
=== Backpressure SLO Recommendations

To monitor backpressure effectively, configure alerts based on the following metrics:

. **solace.message.queue.backpressure** - Alert when p99 exceeds your SLO threshold
. **solace.message.queue.size** - Alert when consistently higher than `concurrency` setting
. **Broker queue depth** - Monitor via Solace SEMP API or exporter

Example Prometheus alert:
[source,yaml]
----
- alert: SolaceBinderBackpressure
  expr: solace_message_queue_backpressure{quantile="0.99"} > 60000
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "High backpressure on {{ $labels.name }}"
----
```

---

### 6. Create example Grafana dashboard

**File:** `doc/dashboard-example.json`

Create a Grafana dashboard JSON with panels for:
- `solace.message.queue.size` (gauge/timeseries)
- `solace.message.active.size` (gauge/timeseries)
- `solace.message.queue.backpressure` (histogram/heatmap)
- `solace.message.processing.time` (histogram/heatmap)
- `solace.message.size.total` (counter)
- `solace.message.size.payload` (counter)
- Example panel for Solace broker queue metrics (placeholder)

Add reference in API.adoc:

```asciidoc
=== Example Dashboard

An example Grafana dashboard is provided in link:doc/dashboard-example.json[doc/dashboard-example.json].

Import this dashboard into Grafana and adjust the data source to your Prometheus/metrics backend.
The dashboard includes panels for all Solace binder metrics and example thresholds for backpressure alerting.
```

---

## Test Updates Required

**File:** `src/test/java/com/solace/spring/cloud/stream/binder/inbound/queue/FlowXMLMessageListenerTest.java`

- Update `testStartReceiverThreads_WatchdogLogsWarningForCongestedQueue` test (lines 117-162)
- Add new test for deadlock detection warning
- If `logRelaxed`/`logUrgent` are deprecated, update or remove corresponding test verifications

---

## Further Considerations

1. **Backwards compatibility:** Should `WatchdogLogger` queue-size warnings be kept behind a feature flag, or fully removed?
   - **Recommendation:** Deprecate but keep for one major release.

2. **Metric naming:** Currently `solace.message.queue.backpressure` records "oldest message processing time" – should the description be clarified or renamed to avoid confusion with queue depth backpressure?
   - **Recommendation:** Update description only, keep metric name for compatibility.

3. **Test updates:** Tests for `logRelaxed`/`logUrgent` will need updating if those methods are changed.

4. **Configuration migration:** Document migration path for users relying on current queue-size warnings.

