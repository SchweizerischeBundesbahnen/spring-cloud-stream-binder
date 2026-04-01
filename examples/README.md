# Spring Cloud Stream Binder for Solace - Examples

This repository contains **20 distinct, production-ready examples** demonstrating how to properly configure and use the Solace PubSub+ Spring Cloud Stream Binder for various architectural patterns.

Every example is built to be "copy-paste clean": they demonstrate pure Spring Framework configurations and are explicitly free of testing-framework hacks, countdown latches, or unexplainable artificial delays within the core application logic.

## Prerequisites
To run these examples locally without the integration test framework, you will need access to a Solace PubSub+ Event Broker.
You can easily spin one up via Docker:

```bash
docker run -d -p 8080:8080 -p 55555:55555 --shm-size=2g \
  --env username_admin_globalaccesslevel=admin --env username_admin_password=admin \
  --name=solace solace/solace-pubsub-standard:latest
```

## How to Run an Example
1. Navigate into the specific example directory (e.g., `cd basic-publish-subscribe`).
2. Run the application using the Spring Boot Maven Plugin:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
   ```

## Example Index

### Messaging Basics
* [**basic-publish-subscribe**](./basic-publish-subscribe/README.md): The simplest introduction to binding a Publisher (`Supplier`) and a Consumer to a Solace Topic.
* [**nonpersistent-messaging**](./nonpersistent-messaging/README.md): Demonstrates Quality of Service (QoS) by sending and receiving strictly Direct (At-Most-Once) non-persistent messages.

### Reliability & Resiliency
* [**consumer-groups**](./consumer-groups/README.md): Shows how setting a `group` inside `application.yml` automatically triggers Solace to explicitly provision a durable Endpoint Queue, guaranteeing delivery even across offline periods.
* [**consumer-concurrency**](./consumer-concurrency/README.md): Configure `concurrency: X` to transparently spin up multiple Solace receiver threads sharing identical Queue bindings.
* [**manual-acknowledgment**](./manual-acknowledgment/README.md): Disable auto-acking and capture `AcknowledgmentCallback` headers to explicitly `accept()`, `reject()`, or `requeue()` messages conditionally.
* [**publisher-confirms**](./publisher-confirms/README.md): Intercept Solace JCSMP producer correlation IDs to natively track whether messages published to the Broker successfully arrived.

### Error Handling
* [**error-handling-redelivery**](./error-handling-redelivery/README.md): Demonstrates how to effectively configure and interact with Spring Retry to absorb transient failures.
* [**error-handling-error-queue**](./error-handling-error-queue/README.md): Shows how `autoBindErrorQueue: true` instructs the Binder to automatically provision a Solace Dead Letter Queue and route permanent failures to it natively.

### Advanced Provisioning & Routing
* [**dynamic-destinations**](./dynamic-destinations/README.md): Demonstrates programmatic publishing directly to calculated string destinations via Spring `StreamBridge`.
* [**queue-provisioning-options**](./queue-provisioning-options/README.md): Shows advanced Solace queue tuning via YAML metadata properties (`errorMsgRejected`, access types, etc).
* [**partitioned-queues**](./partitioned-queues/README.md): Ensure strict ordering across concurrent consumers using native Solace Partitioned Queues matched strictly to your payload's partition key. 
* [**solace-headers**](./solace-headers/README.md): Detailed instruction on mapping and reading proprietary Solace-specific headers directly onto standard Spring `Message<?>` envelopes.

### Architecture & Workflows
* [**processor-pipeline**](./processor-pipeline/README.md): Using Spring `Function` definitions to seamlessly read, transform, and republish messages downstream.
* [**multi-binder**](./multi-binder/README.md): Shows how to instruct a single application to connect out simultaneously to two independent Solace Event Brokers natively.
* [**large-message-chunking**](./large-message-chunking/README.md): Demonstrates how to send payloads scaling up into Megabytes natively despite underlying MTU limits.
* [**pause-resume-bindings**](./pause-resume-bindings/README.md): Leverage Spring Cloud Stream actuator hooks to safely suspend queue traffic natively on the fly without breaking backend client queue bindings.

### Observability & Security
* [**metrics-monitoring**](./metrics-monitoring/README.md): Exposes intrinsic Spring Binder and Solace native statistics via Micrometer to monitor payload traffic flow natively.
* [**micrometer-tracing**](./micrometer-tracing/README.md): Automatically pushes OpenTelemetry headers directly inline with Solace SMF envelopes.
* [**health-indicator**](./health-indicator/README.md): Binds the Session health to `/actuator/health` natively alerting orchestrators on transport disconnects.
* [**oauth2-authentication**](./oauth2-authentication/README.md): Provides detailed code implementations replacing static basic-auth credentials with rotating OAuth2 tokens.
