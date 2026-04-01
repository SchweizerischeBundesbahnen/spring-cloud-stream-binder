# Large Message Chunking

## Overview
Demonstrates how to send payloads scaling up into Megabytes natively despite underlying broker MTU limits, via the Sender Chunking mechanism.

## Key Properties (`application.yml`)
- Publish huge blobs natively.
- Use `SolaceBinderHeaders.LARGE_MESSAGE_SUPPORT = true` or `application.yml` configurations `large-message-support: true` on the binding level.
- Ensure the consumer uses a properly mapped Partitioned Queue (or exclusive logic) for exact ordered stream reassembly.

## Running Locally
In the `examples/large-message-chunking` directory run:

```bash
mvn spring-boot:run \
-Dspring-boot.run.arguments="--solace.java.host=tcp://localhost:55555 --solace.java.msgVpn=default --solace.java.client-username=default --solace.java.client-password=default"
```

## Expected Behavior
The publisher will send a single, extremely large payload (e.g. 20MB). The Solace Binder automatically fragments it, sends the packets chunked, and the receiving Solace Binder seamlessly reassembles it back out as a single byte array on the consumer side.
