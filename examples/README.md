# Spring Cloud Stream Binder for Solace - Examples

This repository contains **21 distinct, production-ready examples** demonstrating how to properly configure and use the Solace PubSub+ Spring Cloud Stream Binder for various architectural patterns.

Unless an example is explicitly demonstrating a different transport behavior, the publisher samples send outbound messages with a 30 second TTL and `solace_dmqEligible=true` so they stay aligned with the binder header guidance in [API.md](../API.md).

## Prerequisites
To run these examples locally without the integration test framework, you will need access to a Solace PubSub+ Event Broker.
You can easily spin one up via Docker:

```bash
docker run -d -p 8081:8080 -p 55555:55555 --shm-size=2g \
  --env username_admin_globalaccesslevel=admin --env username_admin_password=admin \
  --name=solace solace/solace-pubsub-standard:latest
```

This keeps the broker management UI on `http://localhost:8081` so the web- and actuator-based examples can keep using `http://localhost:8080` for the Spring Boot application itself.

## How to Run an Example
1. Navigate into the specific example directory (for example `cd examples/basic-publish-subscribe` from the repository root).
2. For most single-binder examples, run the application using the Spring Boot Maven Plugin:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
   ```
3. Check the module README before starting `multi-binder` or `oauth2-authentication`; those two samples require additional binder- or security-specific arguments.

## Example Index

### Messaging Basics
* [**basic-publish-subscribe**](./basic-publish-subscribe/README.md): The simplest introduction to binding a Publisher and a Consumer to a Solace Topic.
* [**nonpersistent-messaging**](./nonpersistent-messaging/README.md): Demonstrates Quality of Service (QoS) by sending and receiving strictly Direct (At-Most-Once) non-persistent messages.

### Reliability & Resiliency
* [**consumer-groups**](./consumer-groups/README.md): Shows how setting a `group` inside `application.yml` automatically triggers Solace to explicitly provision a durable Endpoint Queue, guaranteeing delivery even across offline periods.
* [**consumer-concurrency**](./consumer-concurrency/README.md): Configure `concurrency: X` to transparently spin up multiple Solace receiver threads sharing identical Queue bindings.
* [**max-unacknowledged-messages**](./max-unacknowledged-messages/README.md): Shows how the broker queue's `maxDeliveredUnackedMsgsPerFlow=1` setting, configured via SEMP before startup, keeps more of the backlog available to faster consumers on the same queue.
* [**manual-acknowledgment**](./manual-acknowledgment/README.md): Capture the `AcknowledgmentCallback`, disable auto-ack, and explicitly `ACCEPT` messages while showing where `REJECT` and `REQUEUE` fit.
* [**publisher-confirms**](./publisher-confirms/README.md): Intercept Solace JCSMP producer correlation IDs to natively track whether messages published to the Broker successfully arrived.

### Error Handling
* [**error-handling-redelivery**](./error-handling-redelivery/README.md): Demonstrates local Spring Retry attempts first, then broker-level redelivery once those retries are exhausted.
* [**error-handling-error-queue**](./error-handling-error-queue/README.md): Shows how `autoBindErrorQueue: true` instructs the Binder to automatically provision a Solace Dead Letter Queue and route permanent failures to it natively.

### Advanced Provisioning & Routing
* [**dynamic-destinations**](./dynamic-destinations/README.md): Demonstrates programmatic publishing directly to calculated string destinations via Spring `StreamBridge`.
* [**queue-provisioning-options**](./queue-provisioning-options/README.md): Shows advanced Solace queue tuning via YAML metadata properties (`errorMsgRejected`, access types, etc).
* [**partitioned-queues**](./partitioned-queues/README.md): Shows how to publish partition keys and provision the example queue as partitioned via SEMP before starting the consumer binding.
* [**default-headers**](./default-headers/README.md): Detailed instruction on mapping and reading proprietary or custom default headers directly onto standard Spring `Message<?>` envelopes before they are published.
* [**solace-headers**](./solace-headers/README.md): Detailed instruction on mapping and reading proprietary Solace-specific headers directly onto standard Spring `Message<?>` envelopes.

### Architecture & Workflows
* [**multi-binder**](./multi-binder/README.md): Shows how one application can keep bindings isolated across two independent Solace brokers.
* [**large-message-chunking**](./large-message-chunking/README.md): Demonstrates how to send payloads scaling up into Megabytes natively despite underlying MTU limits.
* [**pause-resume-bindings**](./pause-resume-bindings/README.md): Leverage Spring Cloud Stream actuator hooks to safely suspend queue traffic natively on the fly without breaking backend client queue bindings.
* [**programmatic-binding-control**](./programmatic-binding-control/README.md): Uses `BindingsLifecycleController` directly from application code to start, stop, and restart a consumer binding.

### Observability & Security
* [**metrics-monitoring**](./metrics-monitoring/README.md): Exposes intrinsic Spring Binder and Solace native statistics via Micrometer to monitor payload traffic flow natively.
* [**micrometer-tracing**](./micrometer-tracing/README.md): Automatically pushes OpenTelemetry headers directly inline with Solace SMF envelopes.
* [**health-indicator**](./health-indicator/README.md): Binds the Session health to `/actuator/health` natively alerting orchestrators on transport disconnects.
* [**oauth2-authentication**](./oauth2-authentication/README.md): Shows the binder-side OAuth2 client configuration pattern and includes a lightweight auto-configuration test for the token provider.
